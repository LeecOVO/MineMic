#if DEBUG
import Foundation
import Shared
import os

/// Developer-triggered, local, bounded capture. Absent from Release builds.
/// A request in the app's private cache starts at most 20 seconds; the audio
/// thread only reserves bounded queue space and copies an already owned frame.
final class MineMicWaveformRecorder {
    private struct Gate { var active = false; var generation: UInt64 = 0; var deadline: UInt64 = 0; var pending = 0; var dropped = 0 }
    private let gate = OSAllocatedUnfairLock(initialState: Gate())
    private let writer = DispatchQueue(label: "MineMic.waveform", qos: .utility)
    private let timer: DispatchSourceTimer
    private let folder: URL
    private var file: FileHandle?
    private var generation: UInt64 = 0
    private var counts: [String: Int] = [:]
    private var requestConsumed = false

    init() {
        folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("MineMicDebug", isDirectory: true)
        timer = DispatchSource.makeTimerSource(queue: writer)
        timer.schedule(deadline: .now(), repeating: .seconds(1))
        timer.setEventHandler { [weak self] in self?.checkRequest() }
        timer.resume()
    }

    private func checkRequest() {
        let request = folder.appendingPathComponent("waveform-request.json")
        guard !requestConsumed, file == nil, let bytes = try? Data(contentsOf: request),
              let json = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let seconds = json["seconds"] as? Int, (1...20).contains(seconds) else { return }
        requestConsumed = true
        // CoreDevice verifies the copied file after transferring it. Retain the
        // trigger briefly so its metadata verification cannot race consumption.
        writer.asyncAfter(deadline: .now() + .seconds(2)) { [weak self] in
            try? FileManager.default.removeItem(at: request); self?.requestConsumed = false
        }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let output = folder.appendingPathComponent("waveform-take.bin")
            try Data("MMWF1\n".utf8).write(to: output, options: .atomic)
            file = try FileHandle(forWritingTo: output)
            try file?.seekToEnd()
            generation &+= 1; let current = generation; counts = [:]
            gate.withLock {
                $0.active = true; $0.generation = current; $0.dropped = 0
                $0.deadline = DispatchTime.now().uptimeNanoseconds + UInt64(seconds)*1_000_000_000
            }
            writeStatus("recording", seconds: seconds)
            writer.asyncAfter(deadline: .now() + .seconds(seconds)) { [weak self] in
                guard let self, self.generation == current else { return }
                self.finishOnWriter()
            }
        } catch { file = nil; writeStatus("failed", seconds: 0) }
    }

    func record(kind: String, capturedAtMicros: Int64, pcm: KotlinByteArray) {
        let tag: UInt8
        switch kind {
        case "input": tag = 1
        case "reference": tag = 2
        case "output-echo": tag = 3
        case "output-bypass": tag = 4
        default: return
        }
        let size = Int(pcm.size) + 13
        guard size <= 1933, let reservation = gate.withLock({ value -> UInt64? in
            guard value.active && DispatchTime.now().uptimeNanoseconds < value.deadline else { return nil }
            guard value.pending + size <= 256 * 1024 else { value.dropped += 1; return nil }
            value.pending += size; return value.generation
        }) else { return }
        let data = IosBytesKt.dataFromBytes(bytes: pcm)
        writer.async { [self] in
            defer { gate.withLock { $0.pending -= size } }
            guard let file, generation == reservation else { return }
            var record = Data([tag])
            var timestamp = capturedAtMicros.littleEndian
            var length = UInt32(data.count).littleEndian
            withUnsafeBytes(of: &timestamp) { record.append(contentsOf: $0) }
            withUnsafeBytes(of: &length) { record.append(contentsOf: $0) }
            record.append(data)
            do { try file.write(contentsOf: record); counts[kind, default: 0] += 1 }
            catch { finishOnWriter(state: "failed") }
        }
    }

    func finish() { writer.async { [weak self] in self?.finishOnWriter() } }

    private func finishOnWriter(state: String = "complete") {
        gate.withLock { $0.active = false }
        guard let file else { return }
        try? file.close(); self.file = nil; generation &+= 1
        writeStatus(state, seconds: 0)
    }

    private func writeStatus(_ phase: String, seconds: Int) {
        let json: [String: Any] = ["phase": phase, "seconds": seconds, "frames": counts,
                                   "dropped": gate.withLock { $0.dropped }]
        if let bytes = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) {
            try? bytes.write(to: folder.appendingPathComponent("waveform-state.json"), options: .atomic)
        }
    }

    deinit { timer.cancel(); try? file?.close() }
}
#endif
