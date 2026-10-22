import SwiftUI

@main
struct iOSApp: App {
    @StateObject private var session = MineMicSessionOwner()
    @Environment(\.scenePhase) private var scenePhase
    var body: some Scene {
        WindowGroup { ContentView(session: session) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { session.platform.applicationBecameActive() }
                session.liveActivity.applicationStateChanged(active: phase == .active)
            }
    }
}
