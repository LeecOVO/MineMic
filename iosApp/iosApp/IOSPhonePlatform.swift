import Foundation
import Network
import AVFoundation
import CryptoKit
import Security
import Shared

final class IOSPhonePlatform: NSObject, PhonePlatform, NetServiceBrowserDelegate, NetServiceDelegate {
    private let queue = DispatchQueue(label: "MineMic.network")
    private let capture = DispatchQueue(label: "MineMic.capture")
    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private var found: ((LanComputer) -> Void)?
    private var discoveryError: ((String) -> Void)?
    private var control: NWConnection?
    private var audio: NWConnection?
    private var pending = Data()
    private var engine: AVAudioEngine?
    private var interruption: NSObjectProtocol?
    private var audioGeneration = 0
    private var frameData = Data()
    func deviceName() -> String { "iPhone" }
    func randomBytes(count: Int32) -> KotlinByteArray {
        var data = Data(count: Int(count))
        _ = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, Int(count), $0.baseAddress!) }
        return IosBytesKt.bytesFromData(data: data)
    }
    func hmac(body: KotlinByteArray, key: KotlinByteArray) -> KotlinByteArray {
        let data = IosBytesKt.dataFromBytes(bytes: body)
        let secret = IosBytesKt.dataFromBytes(bytes: key)
        return IosBytesKt.bytesFromData(data: Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: secret))))
    }
    func discover(onFound: @escaping (LanComputer) -> Void, onError: @escaping (String) -> Void) {
        DispatchQueue.main.async {
            self.stopBrowser(); self.found = onFound; self.discoveryError = onError
            let browser = NetServiceBrowser(); browser.delegate = self; self.browser = browser
            browser.searchForServices(ofType: "_minemic._tcp.", inDomain: "local.")
        }
    }
    private func stopBrowser() {
        browser?.stop(); browser?.delegate = nil; browser = nil
        services.forEach { $0.stop(); $0.delegate = nil }; services.removeAll()
        found = nil; discoveryError = nil
    }
    func stopDiscovery() { DispatchQueue.main.async { self.stopBrowser() } }
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard browser === self.browser, services.count < 16 else { return }
        services.append(service); service.delegate = self; service.resolve(withTimeout: 5)
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        guard browser === self.browser else { return }; _ = discoveryError?("无法发现 PC，请检查局域网权限")
    }
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard services.contains(where: { $0 === sender }), sender.port > 0 else { return }
        for address in sender.addresses ?? [] {
            address.withUnsafeBytes { raw in
                guard let pointer = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self), pointer.pointee.sa_family == sa_family_t(AF_INET) else { return }
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(pointer, socklen_t(address.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                    _ = found?(LanComputer(host: String(cString: host), port: Int32(sender.port), name: sender.name))
                }
            }
        }
    }
    func openControl(host: String, port: Int32, onReady: @escaping () -> Void,
                     onLine: @escaping (String) -> Void, onClosed: @escaping () -> Void,
                     onError: @escaping (String) -> Void) {
        queue.async { [self] in
            self.control?.cancel(); self.pending.removeAll(keepingCapacity: true)
            let connection = NWConnection(host: .init(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: .tcp)
            self.control = connection
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                guard let self, let connection, self.control === connection else { return }
                switch state {
                case .ready: _ = onReady(); self.receive(connection, onLine, onClosed, onError)
                case .failed(let error): _ = onError(error.localizedDescription)
                default: break
                }
            }
            connection.start(queue: self.queue)
        }
    }
    private func receive(_ connection: NWConnection, _ onLine: @escaping (String) -> Void,
                         _ onClosed: @escaping () -> Void, _ onError: @escaping (String) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { [weak self, weak connection] data, _, complete, error in
            guard let self, let connection, self.control === connection else { return }
            if let data { self.pending.append(data) }
            guard self.pending.count <= 4096 else { _ = onError("PC 响应过长"); connection.cancel(); return }
            while let newline = self.pending.firstIndex(of: 10) {
                let line = self.pending.prefix(upTo: newline)
                guard line.count <= 1024, let text = String(data: line, encoding: .utf8) else {
                    _ = onError("PC 响应格式错误"); connection.cancel(); return
                }
                self.pending.removeSubrange(...newline); _ = onLine(text)
            }
            if let error { _ = onError(error.localizedDescription) }
            else if complete { _ = onClosed() }
            else { self.receive(connection, onLine, onClosed, onError) }
        }
    }
    func sendControl(line: String) { queue.async {
        self.control?.send(content: (line + "\n").data(using: .utf8), completion: .contentProcessed { _ in })
    } }
    func closeControl() { queue.async { let old = self.control; self.control = nil; old?.cancel(); self.pending.removeAll() } }
    func openAudio(host: String, port: Int32) { queue.async {
        self.audio?.cancel()
        let connection = NWConnection(host: .init(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: .udp)
        self.audio = connection; connection.start(queue: self.queue)
    } }
    func sendAudio(bytes: KotlinByteArray) {
        let data = IosBytesKt.dataFromBytes(bytes: bytes)
        queue.async { self.audio?.send(content: data, completion: .contentProcessed { _ in }) }
    }
    func closeAudio() { queue.async { self.audio?.cancel(); self.audio = nil } }
    func startAudio(onFrame: @escaping (KotlinByteArray) -> Void, onError: @escaping (String) -> Void) {
        capture.async { [self] in
            self.stopEngine(); let epoch = self.audioGeneration
            AVAudioApplication.requestRecordPermission { [self] allowed in self.capture.async { [self] in
                guard epoch == self.audioGeneration else { return }
                guard allowed else { _ = onError("请在系统设置中允许麦克风权限"); return }
                do {
                    let session = AVAudioSession.sharedInstance()
                    try session.setCategory(.record, mode: .measurement, options: [])
                    try session.setPreferredSampleRate(48000); try session.setPreferredIOBufferDuration(0.01); try session.setActive(true)
                    let engine = AVAudioEngine(), input = engine.inputNode
                    let source = input.outputFormat(forBus: 0)
                    guard source.sampleRate > 0, source.channelCount > 0,
                          let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 48000, channels: 1, interleaved: false),
                          let converter = AVAudioConverter(from: source, to: target) else { throw CaptureError.format }
                    self.frameData.removeAll(keepingCapacity: true)
                    input.installTap(onBus: 0, bufferSize: 480, format: source) { buffer, _ in
                        let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * 48000 / source.sampleRate) + 32)
                        guard let converted = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
                        var supplied = false; var conversionError: NSError?
                        converter.convert(to: converted, error: &conversionError) { _, status in
                            if supplied { status.pointee = .noDataNow; return nil }
                            supplied = true; status.pointee = .haveData; return buffer
                        }
                        guard conversionError == nil, let samples = converted.int16ChannelData?.pointee else { return }
                        self.frameData.append(UnsafeBufferPointer(start: UnsafeRawPointer(samples).assumingMemoryBound(to: UInt8.self), count: Int(converted.frameLength) * 2))
                        while self.frameData.count >= 960 {
                            let frame = Data(self.frameData.prefix(960)); self.frameData.removeFirst(960)
                            _ = onFrame(IosBytesKt.bytesFromData(data: frame))
                        }
                    }
                    self.engine = engine
                    self.interruption = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: nil) { [weak self] note in
                        guard (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
                        self?.capture.async { self?.stopEngine(); _ = onError("麦克风被来电或其他音频打断，请返回应用重新启用") }
                    }
                    engine.prepare(); try engine.start()
                } catch { self.stopEngine(); _ = onError(error.localizedDescription) }
            } }
        }
    }
    private func stopEngine() {
        audioGeneration += 1
        if let interruption { NotificationCenter.default.removeObserver(interruption); self.interruption = nil }
        if let engine { engine.stop(); engine.inputNode.removeTap(onBus: 0); self.engine = nil }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
    func stopAudio() { capture.async { self.stopEngine() } }
    private enum CaptureError: Error { case format }
}
