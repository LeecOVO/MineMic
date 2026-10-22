import Shared
import SwiftUI
import UIKit

@MainActor final class MineMicSessionOwner: ObservableObject {
    let platform: IOSPhonePlatform
    let controller: MicController
    let liveActivity: LiveActivityCoordinator
    init() {
        let platform = IOSPhonePlatform()
        self.platform = platform
        controller = MicController(platform: platform)
        liveActivity = LiveActivityCoordinator(controller: controller)
    }
    deinit { controller.close() }
}

struct ComposeView: UIViewControllerRepresentable {
    let controller: MicController
    let lockScreenControls: LockScreenControls
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(controller: controller, lockScreenControls: lockScreenControls)
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    @ObservedObject var session: MineMicSessionOwner
    var body: some View {
        ComposeView(controller: session.controller, lockScreenControls: session.liveActivity.controls).ignoresSafeArea()
    }
}
