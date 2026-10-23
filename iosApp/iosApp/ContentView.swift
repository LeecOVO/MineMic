import Shared
import SwiftUI
import UIKit

private final class IOSGlassEffects: NSObject, NativeGlassEffectFactory {
    func create(interactive: Bool) -> UIVisualEffect? {
        guard #available(iOS 26.0, *) else { return nil }
        let effect = UIGlassEffect(style: .regular)
        // UIKit applies the user's Liquid Glass and accessibility preferences without a custom tint.
        effect.isInteractive = interactive
        return effect
    }
}

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
        MainViewControllerKt.MainViewController(controller: controller, lockScreenControls: lockScreenControls,
                                               glassEffects: IOSGlassEffects())
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    @ObservedObject var session: MineMicSessionOwner
    var body: some View {
        ComposeView(controller: session.controller, lockScreenControls: session.liveActivity.controls).ignoresSafeArea()
    }
}
