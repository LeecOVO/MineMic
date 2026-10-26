import Foundation
import Network
import CryptoKit
import Darwin

func check(_ condition: Bool, _ message: String) {
    guard condition else { fatalError(message) }
}
let loopback = try IOSNetworkAddress.host("0:0:0:0:0:0:0:1")
if case .ipv6 = loopback {} else { fatalError("IPv6 literal must stay IPv6") }
check(String(describing: try IOSNetworkAddress.host("fe80::1", wifiInterface: { "en0" })).contains("%en0"), "unscoped link-local uses phone Wi-Fi")
check(String(describing: try IOSNetworkAddress.host("fe80::1%lo0", wifiInterface: { nil })).contains("%lo0"), "explicit local zone retained")
do { _ = try IOSNetworkAddress.host("fe80::1", wifiInterface: { nil }); fatalError("missing Wi-Fi zone must fail") }
catch { check(error.localizedDescription.contains("Wi-Fi"), "missing Wi-Fi has actionable error") }
var address = sockaddr_in6()
address.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size); address.sin6_family = sa_family_t(AF_INET6)
check(inet_pton(AF_INET6, "fd12:3456::a", &address.sin6_addr) == 1, "test IPv6 parsed")
let bytes = withUnsafeBytes(of: address) { Data($0) }
check(IOSNetworkAddress.numericHost(bytes) == "fd12:3456::a", "Bonjour AAAA sockaddr resolves numerically")
check(IOSNetworkAddress.numericHost(Data()) == nil && IOSNetworkAddress.numericHost(bytes.prefix(8)) == nil, "short sockaddr is rejected")

final class Control {
    let connection: NWConnection
    private var pending = Data()
    init(host: String, port: UInt16) throws {
        let options = NWProtocolTCP.Options(); options.noDelay = true
        connection = NWConnection(host: try IOSNetworkAddress.host(host), port: NWEndpoint.Port(rawValue: port)!, using: NWParameters(tls: nil, tcp: options))
        let ready = DispatchSemaphore(value: 0)
        connection.stateUpdateHandler = { state in
            if case .ready = state { ready.signal() }
            if case .failed = state { ready.signal() }
        }
        connection.start(queue: .global(qos: .userInitiated))
        check(ready.wait(timeout: .now() + 4) == .success, "Network.framework TCP ready")
        check(connection.state == .ready, "TCP connected over selected family")
    }
    func rpc(_ fields: [String: String]) throws -> [String: String] {
        var request = fields; request["app"] = "MineMic"; request["v"] = "2"
        var data = try JSONSerialization.data(withJSONObject: request); data.append(10)
        connection.send(content: data, completion: .contentProcessed { error in check(error == nil, "TCP send") })
        for _ in 0..<8 {
            while !pending.contains(10) {
                let received = DispatchSemaphore(value: 0)
                connection.receive(minimumIncompleteLength: 1, maximumLength: 2048) { data, _, _, error in
                    check(error == nil && data != nil, "TCP receives a control line")
                    if let data { self.pending.append(data) }; received.signal()
                }
                check(received.wait(timeout: .now() + 4) == .success, "bounded control receive")
            }
            let newline = pending.firstIndex(of: 10)!
            let line = pending.prefix(upTo: newline); pending.removeSubrange(...newline)
            let response = try JSONSerialization.jsonObject(with: line) as! [String: String]
            if ["welcome", "reference", "disconnected", "error"].contains(response["type"] ?? "") { return response }
        }
        fatalError("too many control messages")
    }
}

final class Discovery: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    let browser = NetServiceBrowser()
    let port: Int
    var services: [NetService] = []
    var ipv6Host: String?
    init(port: Int) { self.port = port; super.init(); browser.delegate = self }
    func start() { browser.searchForServices(ofType: "_minemic._tcp.", inDomain: "local.") }
    func stop() { browser.stop(); services.forEach { $0.stop() } }
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard services.count < 16 else { return }
        services.append(service); service.delegate = self; service.resolve(withTimeout: 4)
    }
    func netServiceDidResolveAddress(_ service: NetService) {
        guard service.port == port else { return }
        for data in service.addresses ?? [] {
            if let host = IOSNetworkAddress.numericHost(data), host.contains(":") { ipv6Host = host }
        }
    }
}

if let fixture = ProcessInfo.processInfo.environment["MINEMIC_PC_TEST_SERVER"] {
    let process = Process(); let pipe = Pipe()
    process.executableURL = URL(fileURLWithPath: fixture); process.arguments = ["--serve-reference"]
    process.standardOutput = pipe; try process.run()
    defer { process.terminate(); process.waitUntilExit() }
    var first = Data()
    while !first.contains(10) {
        let byte = pipe.fileHandleForReading.readData(ofLength: 1)
        check(!byte.isEmpty, "C++ fixture starts"); first.append(byte)
    }
    let info = String(data: first, encoding: .utf8)!.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ")
    let port = UInt16(info[0])!; let code = String(info[1])
    var hosts = ["::1", "127.0.0.1"]
    if ProcessInfo.processInfo.environment["MINEMIC_TEST_MDNS"] == "1" {
        let discovery = Discovery(port: Int(port)); discovery.start()
        defer { discovery.stop() }
        let deadline = Date().addingTimeInterval(8)
        while discovery.ipv6Host == nil && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.02)) }
        check(discovery.ipv6Host != nil, "Bonjour resolves actual PC AAAA records")
        hosts.append(discovery.ipv6Host!)
        if discovery.ipv6Host!.contains("%") { hosts.append(String(discovery.ipv6Host!.split(separator: "%")[0])) }
        print("Bonjour IPv6 discovery and resolved-interface connection enabled")
    }
    for host in hosts {
        let control = try Control(host: host, port: port)
        defer { control.connection.cancel() }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let nonce = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let welcome = try control.rpc(["type": "connect", "mode": "code", "code": code, "clientId": id, "nonce": nonce, "name": "Swift IPv6 test"])
        check(welcome["type"] == "welcome", "native code pairing over \(host)")
        let keyString = welcome["key"]!
        let key = Data(stride(from: 0, to: 32, by: 2).map { offset in
            UInt8(keyString.dropFirst(offset).prefix(2), radix: 16)!
        })
        let udp = NWConnection(host: try IOSNetworkAddress.host(host), port: NWEndpoint.Port(rawValue: port)!, using: .udp)
        defer { udp.cancel() }
        let ready = DispatchSemaphore(value: 0)
        udp.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        udp.start(queue: .global(qos: .userInitiated))
        check(ready.wait(timeout: .now() + 4) == .success, "native IPv4/IPv6 UDP ready")
        var body = Data(repeating: 0, count: 992)
        body.replaceSubrange(0..<4, with: Data("MMIC".utf8)); body[4] = 2; body[5] = 3
        func put(_ offset: Int, _ value: UInt32, _ count: Int = 4) {
            for i in 0..<count { body[offset+i] = UInt8(truncatingIfNeeded: value >> ((count-1-i)*8)) }
        }
        put(8, UInt32(welcome["session"]!)!); put(20, 48000); put(24, 480, 2); body[26] = 1; body[27] = 1; put(28, 960, 2)
        let packet = body + Data(HMAC<SHA256>.authenticationCode(for: body, using: SymmetricKey(data: key)))
        let sent = DispatchSemaphore(value: 0)
        udp.send(content: packet, completion: .contentProcessed { error in check(error == nil, "native UDP microphone send"); sent.signal() })
        check(sent.wait(timeout: .now() + 4) == .success, "bounded UDP send")
        let reference = try control.rpc(["type": "reference", "enabled": "1"])
        check(reference["state"] == "starting", "native reference requested")
        let received = DispatchSemaphore(value: 0)
        udp.receiveMessage { data, _, _, error in
            check(error == nil && data?.count == 1040, "native IPv4/IPv6 reference returned")
            let data = data!
            check(data.prefix(4) == Data("MREF".utf8), "reference format")
            check(HMAC<SHA256>.isValidAuthenticationCode(data.suffix(32), authenticating: data.prefix(1008), using: SymmetricKey(data: key)), "reference authentication")
            received.signal()
        }
        check(received.wait(timeout: .now() + 4) == .success, "bounded native reference receive")
        check(try control.rpc(["type": "disconnect", "clientId": id, "token": welcome["token"]!])["type"] == "disconnected", "native disconnect releases IPv4/IPv6 owner")
    }
    print("Native Network.framework IPv6 and IPv4 pairing, microphone upload, reference return and release passed")
} else { print("Set MINEMIC_PC_TEST_SERVER for native Network.framework integration") }
print("iOS address and Wi-Fi scope tests passed")
