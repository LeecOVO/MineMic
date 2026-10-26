import Foundation
import Network
import Darwin

enum IOSNetworkAddress {
    /// A link-local destination needs the phone's Wi-Fi zone, never the PC's zone.
    static func host(_ literal: String, wifiInterface: () -> String? = activeWifiInterface) throws -> NWEndpoint.Host {
        let address = literal.split(separator: "%", maxSplits: 1).first.map(String.init) ?? literal
        guard let ipv6 = IPv6Address(address), ipv6.isLinkLocal, !literal.contains("%") else {
            return NWEndpoint.Host(literal)
        }
        guard let interface = wifiInterface() else {
            throw NSError(domain: "MineMic.Network", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "无法确定 Wi-Fi IPv6 接口，请连接 Wi-Fi 或在地址后附加 %en0"])
        }
        return NWEndpoint.Host("\(literal)%\(interface)")
    }

    static func numericHost(_ data: Data) -> String? {
        data.withUnsafeBytes { raw in
            guard data.count >= MemoryLayout<sockaddr>.size,
                  let pointer = raw.baseAddress?.assumingMemoryBound(to: sockaddr.self) else { return nil }
            let family = Int32(pointer.pointee.sa_family)
            guard (family == AF_INET && data.count >= MemoryLayout<sockaddr_in>.size) ||
                  (family == AF_INET6 && data.count >= MemoryLayout<sockaddr_in6>.size) else { return nil }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(pointer, socklen_t(data.count), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { return nil }
            return String(cString: host)
        }
    }

    private static func activeWifiInterface() -> String? {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return nil }
        defer { freeifaddrs(first) }
        var current = first
        while let item = current {
            defer { current = item.pointee.ifa_next }
            let value = item.pointee
            guard let address = value.ifa_addr, Int32(address.pointee.sa_family) == AF_INET6,
                  (value.ifa_flags & UInt32(IFF_UP)) != 0,
                  String(cString: value.ifa_name) == "en0" else { continue }
            return "en0"
        }
        return nil
    }
}
