import AVFoundation
import Foundation

func check(_ value: @autoclosure () -> Bool, _ message: String) {
    if !value() { fatalError(message) }
}

// Exercise the same preallocated handoff/converter used on the phone without a
// microphone or a signing identity. Includes 44.1 -> 48 kHz and partial packets.
for rate in [48000.0, 44100.0] {
    let queue = DispatchQueue(label: "audio.test")
    let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
    let blockFrames = AVAudioFrameCount(rate / 100)
    let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: blockFrames)!
    input.frameLength = blockFrames
    for i in 0..<Int(blockFrames) { input.floatChannelData![0][i] = 0.25 }
    let done = DispatchSemaphore(value: 0)
    var packets: [Data] = [] // Only accessed on queue, then inspected after queue.sync.
    let capture = try IOSAudioCapture(format: format, queue: queue) { frame in
        packets.append(frame); done.signal()
    }
    // Block the worker to reproduce a native batch. All eight input blocks must
    // survive, regardless of whether the worker can run during receive().
    let release = DispatchSemaphore(value: 0), started = DispatchSemaphore(value: 0)
    queue.async { started.signal(); release.wait() }
    started.wait()
    for n in 0..<8 {
        capture.receive(timestamp: Double(n) * Double(blockFrames), frames: blockFrames, buffers: input.audioBufferList)
    }
    release.signal()
    for _ in 0..<7 { check(done.wait(timeout: .now() + 3) == .success, "lost a block at \(rate) Hz") }
    queue.sync {
        check(packets.count >= 7 && packets.count <= 8, "resampler output duration")
        for frame in packets {
            check(frame.count == 960, "packet must contain 480 PCM16 samples")
            let samples = frame.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
            // Ignore resampler priming; steady samples retain the source amplitude.
            check(samples.suffix(100).allSatisfy { abs(Int($0) - 8192) <= 8 }, "PCM amplitude/order")
        }
        capture.stop()
    }
}

// Partial 128-frame hardware blocks must accumulate without losing samples.
do {
    let queue = DispatchQueue(label: "audio.partial")
    let format = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1)!
    let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 128)!
    input.frameLength = 128
    let done = DispatchSemaphore(value: 0)
    var packets = 0
    let capture = try IOSAudioCapture(format: format, queue: queue) { frame in
        check(frame.count == 960, "partial packet size"); packets += 1; done.signal()
    }
    for n in 0..<15 {
        for i in 0..<128 { input.floatChannelData![0][i] = 0.25 }
        capture.receive(timestamp: Double(n * 128), frames: 128, buffers: input.audioBufferList)
        // Drain between iterations, without relying on wall-clock audio timing.
        queue.sync {}
    }
    for _ in 0..<4 { check(done.wait(timeout: .now() + 3) == .success, "partial blocks lost samples") }
    queue.sync { check(packets == 4, "1920 source frames must make four packets"); capture.stop() }
}
print("iOS capture handoff, batching, resampling and partial-packet tests passed")

// A suspended worker must not build an unlimited audio backlog. Feed one second
// of input at once; only the bounded 160 ms may be retained. The next live block
// must still be accepted after the backlog drains.
do {
    let queue = DispatchQueue(label: "audio.overload")
    let format = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1)!
    let input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480)!
    input.frameLength = 480
    for i in 0..<480 { input.floatChannelData![0][i] = 0.25 }
    let release = DispatchSemaphore(value: 0), started = DispatchSemaphore(value: 0)
    let packet = DispatchSemaphore(value: 0)
    var packets = 0
    let capture = try IOSAudioCapture(format: format, queue: queue) { _ in packets += 1; packet.signal() }
    queue.async { started.signal(); release.wait() }; started.wait()
    for n in 0..<100 { capture.receive(timestamp: Double(n * 480), frames: 480, buffers: input.audioBufferList) }
    release.signal()
    for _ in 0..<16 { check(packet.wait(timeout: .now() + 3) == .success, "overload lost retained audio") }
    queue.sync { check(packets == 16, "queue exceeded its 160 ms bound") }
    capture.receive(timestamp: 48000, frames: 480, buffers: input.audioBufferList)
    check(packet.wait(timeout: .now() + 3) == .success, "capture did not recover after overload")
    queue.sync { capture.stop() }
    capture.receive(timestamp: 48480, frames: 480, buffers: input.audioBufferList)
    queue.sync { check(packets == 17, "callback continued after stop") }
}
print("iOS bounded backlog, recovery and stop tests passed")
