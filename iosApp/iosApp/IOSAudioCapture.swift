import AVFoundation
import Foundation
import os

/// A bounded handoff from the hardware render thread to the capture worker.
/// The render block only copies into preallocated storage and signals the worker:
/// no converter, Kotlin callback, crypto, network operation, or blocking lock.
final class IOSAudioCapture {
    private static let capacity: AVAudioFrameCount = 8192
    private final class Block {
        let pcm: AVAudioPCMBuffer
        var sampleTime: Double = 0
        var hostMicros: Int64 = 0
        init(format: AVAudioFormat) throws {
            guard let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: IOSAudioCapture.capacity) else { throw CaptureError.format }
            self.pcm = pcm
        }
    }
    private struct Ring {
        var read = 0, write = 0, count = 0
        var frames: AVAudioFrameCount = 0
    }
    private let blocks: [Block]
    private let ring = OSAllocatedUnfairLock(initialState: Ring())
    private let signal: DispatchSourceUserDataAdd
    private let converter: AVAudioConverter
    private let converted: AVAudioPCMBuffer
    private let onFrame: (Data, Int64) -> Void
    private var pendingMicros: Int64?
    private let maxQueuedFrames: AVAudioFrameCount
    private var pending = Data()
    private var expectedSampleTime: Double?
    private var active = true // Accessed only on the capture worker.

    init(format: AVAudioFormat, queue: DispatchQueue, onFrame: @escaping (Data, Int64) -> Void) throws {
        guard format.sampleRate > 0, format.channelCount > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 48000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: format, to: target),
              let output = AVAudioPCMBuffer(pcmFormat: target,
                  frameCapacity: AVAudioFrameCount(ceil(Double(Self.capacity) * 48000 / format.sampleRate)) + 32)
        else { throw CaptureError.format }
        blocks = try (0..<32).map { _ in try Block(format: format) }
        self.converter = converter; converted = output; self.onFrame = onFrame
        maxQueuedFrames = AVAudioFrameCount(format.sampleRate * 0.16)
        pending.reserveCapacity(Int(output.frameCapacity) * 2 + 960)
        signal = DispatchSource.makeUserDataAddSource(queue: queue)
        signal.setEventHandler { [weak self] in self?.drain() }
        signal.resume()
    }

    /// Called on the real-time thread. Never waits for the worker.
    func receive(timestamp: Double, hostMicros: Int64? = nil, frames: AVAudioFrameCount, buffers: UnsafePointer<AudioBufferList>) {
        guard frames > 0, frames <= Self.capacity else { return }
        // The closure runs synchronously; hardware pointers never escape this call.
        let copied = ring.withLockIfAvailableUnchecked { state -> Bool in
            guard state.count < blocks.count, state.frames + frames <= maxQueuedFrames else { return false }
            let block = blocks[state.write]
            let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffers))
            let destination = UnsafeMutableAudioBufferListPointer(block.pcm.mutableAudioBufferList)
            guard source.count == destination.count else { return false }
            // Check all bounds before writing a slot; never retain the hardware pointers.
            for i in 0..<source.count {
                guard source[i].mData != nil, destination[i].mData != nil,
                      source[i].mDataByteSize <= Self.capacity * block.pcm.format.streamDescription.pointee.mBytesPerFrame else { return false }
            }
            block.pcm.frameLength = frames; block.sampleTime = timestamp
            block.hostMicros = hostMicros ?? Int64(timestamp * 1000000 / block.pcm.format.sampleRate)
            for i in 0..<source.count {
                memcpy(destination[i].mData!, source[i].mData!, Int(source[i].mDataByteSize))
            }
            state.frames += frames; state.count += 1; state.write = (state.write + 1) % blocks.count
            return true
        } ?? false
        if copied { signal.add(data: 1) }
    }

    private func drain() {
        guard active else { return }
        // Bounded work per wakeup, even if the producer keeps running.
        for _ in blocks.indices {
            guard let block = ring.withLockUnchecked({ state in state.count == 0 ? nil : blocks[state.read] }) else { break }
            // The read slot stays occupied until conversion completes, so capture
            // cannot overwrite samples while the converter reads them.
            if let expectedSampleTime, abs(block.sampleTime - expectedSampleTime) > 1 {
                converter.reset(); pending.removeAll(keepingCapacity: true); pendingMicros = nil
            }
            expectedSampleTime = block.sampleTime + Double(block.pcm.frameLength)
            // Re-anchor to hardware time, not the worker's arrival time or an
            // indefinitely extrapolated nominal 48 kHz clock. Account for the
            // output samples already waiting to complete a packet.
            pendingMicros = block.hostMicros > 0 ? block.hostMicros - Int64(pending.count / 2) * 1000000 / 48000 : 0
            convert(block.pcm)
            ring.withLockUnchecked { state in
                state.frames -= block.pcm.frameLength; state.count -= 1; state.read = (state.read + 1) % blocks.count
            }
        }
        if ring.withLockUnchecked({ $0.count > 0 }) { signal.add(data: 1) }
    }

    private func convert(_ input: AVAudioPCMBuffer) {
        var supplied = false
        var error: NSError?
        converted.frameLength = 0
        converter.convert(to: converted, error: &error) { _, status in
            if supplied { status.pointee = .noDataNow; return nil }
            supplied = true; status.pointee = .haveData; return input
        }
        guard error == nil, let samples = converted.int16ChannelData?.pointee else { return }
        pending.append(UnsafeBufferPointer(start: UnsafeRawPointer(samples).assumingMemoryBound(to: UInt8.self), count: Int(converted.frameLength) * 2))
        var offset = 0
        while pending.count - offset >= 960 {
            let start = pending.startIndex + offset
            onFrame(Data(pending[start..<(start + 960)]), pendingMicros ?? 0); pendingMicros = (pendingMicros ?? 0) + 10000; offset += 960
        }
        if offset > 0 { pending.removeFirst(offset) }
    }

    /// Call on the worker after engine.stop() has stopped producing blocks.
    func stop() { active = false; signal.cancel() }
    deinit { signal.cancel() }
    private enum CaptureError: Error { case format }
}
