import Shared
import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    private let platform = IOSPhonePlatform()
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController(platform: platform)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
