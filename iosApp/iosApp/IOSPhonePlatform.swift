import Foundation
import Network
import AVFoundation
import CryptoKit
import Security
import Shared

final class IOSPhonePlatform: NSObject, PhonePlatform, NetServiceBrowserDelegate, NetServiceDelegate {
    private let connectionState = IOSConnectionStateStorage()
    func loadConnectionState() -> String? { connectionState.load() }
    func saveConnectionState(state: String?) { connectionState.save(state) }
    private let queue = DispatchQueue(label: "MineMic.network", qos: .userInteractive)
    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private var found: ((LanComputer) -> Void)?
    private var discoveryError: ((String) -> Void)?
    private var control: NWConnection?
    private var audio: NWConnection?
    private var audioFailure: ((String) -> Void)?
    private let audioSendGate = IOSAudioSendGate()
    private var pending = Data()
    private let microphone = IOSMicrophoneSession()
    #if DEBUG
    private let waveformRecorder = MineMicWaveformRecorder()
    private var diagnosticSent = 0, diagnosticReferences = 0, diagnosticUnexpected = 0
    private var nextDiagnosticAt: UInt64 = 0
    private func traceAudioTransport() {
        let now = DispatchTime.now().uptimeNanoseconds
        guard now >= nextDiagnosticAt else { return }
        nextDiagnosticAt = now + 1_000_000_000
        // Counters only: never print packet bodies, microphone PCM or session credentials.
        let message = "sent=\(diagnosticSent) reference1040=\(diagnosticReferences) unexpectedDatagrams=\(diagnosticUnexpected)"
        print("[MineMic audio] \(message)")
        MineMicAudioDiagnostics.record(topic: "transport", message: message)
    }
    #endif
    func monotonicMicros() -> Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1000) }
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
        for address in (sender.addresses ?? []).prefix(16) {
            if let host = IOSNetworkAddress.numericHost(address) {
                _ = found?(LanComputer(host: host, port: Int32(sender.port), name: sender.name))
            }
        }
    }
    func openControl(host: String, port: Int32, onReady: @escaping () -> Void,
                     onLine: @escaping (String) -> Void, onClosed: @escaping () -> Void,
                     onError: @escaping (String) -> Void) {
        queue.async { [self] in
            self.control?.cancel(); self.pending.removeAll(keepingCapacity: true)
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            let endpoint: NWEndpoint.Host
            do { endpoint = try IOSNetworkAddress.host(host) }
            catch { _ = onError(error.localizedDescription); return }
            let connection = NWConnection(host: endpoint, port: NWEndpoint.Port(rawValue: UInt16(port))!,
                                          using: NWParameters(tls: nil, tcp: tcp))
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
                #if DEBUG
                if let json = try? JSONSerialization.jsonObject(with: line) as? [String: Any],
                   json["type"] as? String == "reference" {
                    let message = "state=\(json["state"] as? String ?? "unknown") detail=\(json["detail"] as? String ?? "")"
                    print("[MineMic reference] \(message)")
                    MineMicAudioDiagnostics.record(topic: "reference", message: message)
                }
                #endif
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
    func finishControl(line: String, timeoutMillis: Int32) { queue.async {
        guard let old = self.control else { return }
        self.control = nil; self.pending.removeAll()
        old.send(content: (line + "\n").data(using: .utf8), completion: .contentProcessed { _ in
            old.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { _ in old.cancel() })
        })
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + .milliseconds(Int(max(1, min(200, timeoutMillis))))) { old.cancel() }
    } }
    func openAudio(host: String, port: Int32, onReference: @escaping (KotlinByteArray) -> Void,
                   onError: @escaping (String) -> Void) { queue.async { [self] in
        let old = self.audio; self.audio = nil; old?.cancel()
        self.audioSendGate.reset()
        #if DEBUG
        self.diagnosticSent = 0; self.diagnosticReferences = 0; self.diagnosticUnexpected = 0; self.nextDiagnosticAt = 0
        #endif
        let parameters = NWParameters.udp
        parameters.serviceClass = .interactiveVoice
        let endpoint: NWEndpoint.Host
        do { endpoint = try IOSNetworkAddress.host(host) }
        catch { _ = onError(error.localizedDescription); return }
        let connection = NWConnection(host: endpoint, port: NWEndpoint.Port(rawValue: UInt16(port))!, using: parameters)
        self.audio = connection; self.audioFailure = onError
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            if case .failed(let error) = state { self.failAudio(connection, error) }
        }
        connection.start(queue: self.queue)
        self.receiveReference(connection, onReference)
    } }
    private func receiveReference(_ connection: NWConnection, _ callback: @escaping (KotlinByteArray) -> Void) {
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self, let connection, self.audio === connection else { return }
            #if DEBUG
            if let data {
                if data.count == 1040 { self.diagnosticReferences += 1 }
                else { self.diagnosticUnexpected += 1 }
                self.traceAudioTransport()
            }
            #endif
            if let data, data.count <= 1200 { callback(IosBytesKt.bytesFromData(data: data)) }
            if let error { self.failAudio(connection, error) }
            else { self.receiveReference(connection, callback) }
        }
    }
    func sendAudio(bytes: KotlinByteArray) {
        // Reserve before dispatching, so even a stalled network queue stays bounded.
        guard let generation = audioSendGate.reserve() else { return }
        let data = IosBytesKt.dataFromBytes(bytes: bytes)
        queue.async { [self] in
            guard self.audioSendGate.isCurrent(generation), let connection = self.audio else {
                self.audioSendGate.release(generation); return
            }
            connection.send(content: data, completion: .contentProcessed { [weak self, weak connection] error in
                guard let self else { return }
                self.audioSendGate.release(generation)
                #if DEBUG
                if error == nil && self.audio === connection {
                    self.diagnosticSent += 1; self.traceAudioTransport()
                }
                #endif
                if let error, let connection { self.failAudio(connection, error) }
            })
        }
    }
    private func failAudio(_ connection: NWConnection, _ error: Error) {
        guard audio === connection else { return }
        let callback = audioFailure
        audio = nil; audioFailure = nil; audioSendGate.reset(); connection.cancel()
        callback?(error.localizedDescription)
    }
    func closeAudio() { queue.async {
        let old = self.audio; self.audio = nil; self.audioFailure = nil; self.audioSendGate.reset(); old?.cancel()
    } }
    func startAudio(onFrame: @escaping (CapturedAudio) -> Void, onError: @escaping (String) -> Void,
                    onState: @escaping (KotlinBoolean, String) -> Void) {
        microphone.start(onFrame: { data, micros in onFrame(CapturedAudio(pcm: IosBytesKt.bytesFromData(data: data), capturedAtMicros: micros)) },
                         onError: { _ = onError($0) },
                         onState: { active, message in _ = onState(KotlinBoolean(bool: active), message) })
    }
    func stopAudio() {
        microphone.stop()
        #if DEBUG
        waveformRecorder.finish()
        #endif
    }
    func reportAudioDiagnostics(message: String) {
        #if DEBUG
        MineMicAudioDiagnostics.record(topic: "processing", message: message)
        #endif
    }
    func recordAudioDiagnosticFrame(kind: String, capturedAtMicros: Int64, pcm: KotlinByteArray) {
        #if DEBUG
        waveformRecorder.record(kind: kind, capturedAtMicros: capturedAtMicros, pcm: pcm)
        #endif
    }
    func applicationBecameActive() { microphone.becameActive() }
}

#if DEBUG
/// A small local snapshot survives debugger detachment. No audio or credentials.
enum MineMicAudioDiagnostics {
    private static let writer = DispatchQueue(label: "MineMic.debug-status", qos: .utility)
    private static var latest: [String: [String: Any]] = [:]
    static func record(topic: String, message: String) {
        guard ["transport", "reference", "session", "processing"].contains(topic) else { return }
        let text = String(message.prefix(600))
        let timestamp = Date().timeIntervalSince1970
        writer.async {
            latest[topic] = ["at": timestamp, "message": text]
            guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first,
                  let data = try? JSONSerialization.data(withJSONObject: latest, options: [.sortedKeys]) else { return }
            let folder = base.appendingPathComponent("MineMicDebug", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try? data.write(to: folder.appendingPathComponent("audio-status.json"), options: [.atomic])
        }
    }
}
#endif
