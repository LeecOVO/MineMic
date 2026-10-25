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
    #if DEBUG
    private var diagnosticSubscription: MicStateSubscription?
    private var nextDiagnosticAt: UInt64 = 0
    private var diagnosticPhase = ""
    #endif
    init() {
        let platform = IOSPhonePlatform()
        self.platform = platform
        controller = MicController(platform: platform)
        liveActivity = LiveActivityCoordinator(controller: controller)
        #if DEBUG
        diagnosticSubscription = controller.observeSessionState { [weak self] state in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let now = DispatchTime.now().uptimeNanoseconds
                let phase = String(describing: state.phase)
                guard phase != self.diagnosticPhase || now >= self.nextDiagnosticAt else { return }
                self.diagnosticPhase = phase; self.nextDiagnosticAt = now + 1_000_000_000
                let message = "phase=\(phase) recording=\(state.recording) ownsPC=\(state.ownsPc) message=\(state.message) processing=\(state.enhancementMessage)"
                print("[MineMic state] \(message)")
                MineMicAudioDiagnostics.record(topic: "session", message: message)
            }
        }
        if let code = ProcessInfo.processInfo.environment["MINEMIC_DIAGNOSTIC_PAIR_CODE"],
           code.count == 6, code.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }) {
            // The same controller backs Compose and the actual audio pipeline.
            // This opt-in test entry never stores or prints pairing credentials.
            UIApplication.shared.isIdleTimerDisabled = true
            controller.setEchoCancellation(value: true)
            controller.connect(code: code, host: "")
        }
        #endif
    }
    deinit {
        #if DEBUG
        diagnosticSubscription?.cancel()
        #endif
        controller.close()
    }
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
