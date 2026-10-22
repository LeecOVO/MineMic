import ActivityKit
import AppIntents

@MainActor
enum MineMicLiveActionRouter {
    // Only the running app installs this handler; no intent creates a microphone
    // session or reconnects a PC after process termination.
    static var handler: ((String, Bool?) async -> Bool)?

    static func run(sessionID: String, muted: Bool?) async {
        if await handler?(sessionID, muted) == true { return }
        for activity in Activity<MineMicActivityAttributes>.activities
        where activity.attributes.sessionID == sessionID {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}

struct SetMineMicMuteIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "设置 MineMic 静音"
    static let openAppWhenRun = false
    static let authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed
    @Parameter(title: "会话") var sessionID: String
    @Parameter(title: "静音") var muted: Bool
    init() {}
    init(sessionID: String, muted: Bool) { self.sessionID = sessionID; self.muted = muted }
    @MainActor func perform() async throws -> some IntentResult {
        await MineMicLiveActionRouter.run(sessionID: sessionID, muted: muted)
        return .result()
    }
}

struct DisconnectMineMicIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "断开 MineMic"
    static let openAppWhenRun = false
    static let authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed
    @Parameter(title: "会话") var sessionID: String
    init() {}
    init(sessionID: String) { self.sessionID = sessionID }
    @MainActor func perform() async throws -> some IntentResult {
        await MineMicLiveActionRouter.run(sessionID: sessionID, muted: nil)
        return .result()
    }
}
