import SwiftUI
import JMailApp

/// The iOS application.
///
/// Everything visible comes from the shared Compose UI; this target exists to own the app
/// lifecycle, the launch screen and the `jmail://` URL scheme that completes an OAuth
/// sign-in.
@main
struct JMailApp: App {

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                // Compose draws its own keyboard-aware layout, so UIKit must not also
                // inset the view or the composer ends up double-padded.
                .ignoresSafeArea(.keyboard)
                .onOpenURL { url in
                    _ = MainViewControllerKt.handleDeepLink(url: url.absoluteString)
                }
        }
    }
}

/// Bridges the Kotlin `MainViewController()` into SwiftUI.
struct ComposeView: UIViewControllerRepresentable {

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
        // Compose manages its own state; nothing to push down from SwiftUI.
    }
}
