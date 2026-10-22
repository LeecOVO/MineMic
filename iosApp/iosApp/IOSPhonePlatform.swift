import Foundation
import Network
import AVFoundation
import CryptoKit
import Security
import Shared
import os

final class IOSPhonePlatform: NSObject, PhonePlatform, NetServiceBrowserDelegate, NetServiceDelegate {
    private let queue = DispatchQueue(label: "MineMic.network", qos: .userInteractive)
    private var browser: NetServiceBrowser?
    private var services: [NetService] = []
    private var found: ((LanComputer) -> Void)?
    private var discoveryError: ((String) -> Void)?
    private var control: NWConnection?
    private var audio: NWConnection?
    private let audioInFlight = OSAllocatedUnfairLock(initialState: 0)
    private var pending = Data()
    private let microphone = IOSMicrophoneSession()
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
    func openAudio(host: String, port: Int32, onReference: @escaping (KotlinByteArray) -> Void) { queue.async {
        self.audio?.cancel()
        let connection = NWConnection(host: .init(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: .udp)
        self.audio = connection; connection.start(queue: self.queue)
        self.receiveReference(connection, onReference)
    } }
    private func receiveReference(_ connection: NWConnection, _ callback: @escaping (KotlinByteArray) -> Void) {
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self, let connection, self.audio === connection else { return }
            if let data, data.count <= 1200 { callback(IosBytesKt.bytesFromData(data: data)) }
            if error == nil { self.receiveReference(connection, callback) }
        }
    }
    func sendAudio(bytes: KotlinByteArray) {
        // Reserve before dispatching, so even a stalled network queue stays bounded.
        guard audioInFlight.withLock({ count in
            guard count < 16 else { return false }; count += 1; return true
        }) else { return }
        let data = IosBytesKt.dataFromBytes(bytes: bytes)
        queue.async { [self] in
            guard let connection = self.audio else { self.releaseAudioSend(); return }
            connection.send(content: data, completion: .contentProcessed { [weak self] _ in
                self?.releaseAudioSend()
            })
        }
    }
    private func releaseAudioSend() { audioInFlight.withLock { $0 -= 1 } }
    func closeAudio() { queue.async { self.audio?.cancel(); self.audio = nil } }
    func startAudio(onFrame: @escaping (CapturedAudio) -> Void, onError: @escaping (String) -> Void,
                    onState: @escaping (KotlinBoolean, String) -> Void) {
        microphone.start(onFrame: { data, micros in onFrame(CapturedAudio(pcm: IosBytesKt.bytesFromData(data: data), capturedAtMicros: micros)) },
                         onError: { _ = onError($0) },
                         onState: { active, message in _ = onState(KotlinBoolean(bool: active), message) })
    }
    func stopAudio() { microphone.stop() }
    func applicationBecameActive() { microphone.becameActive() }
}
