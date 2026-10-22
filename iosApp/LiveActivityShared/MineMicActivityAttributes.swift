import ActivityKit
import Foundation

struct MineMicActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var pcName: String
        var phase: String
        var recording: Bool
        var muted: Bool
        var startedAt: Date

        var status: String {
            switch phase {
            case "disconnecting": return "正在断开连接"
            case "reconnecting": return "正在重连，原占用保留"
            default:
                if !recording { return "麦克风未就绪"
                }
                return muted ? "已静音" : "正在传输声音"
            }
        }
    }
    // An opaque per-ownership identifier, never the pairing code or auth token.
    var sessionID: String
}
