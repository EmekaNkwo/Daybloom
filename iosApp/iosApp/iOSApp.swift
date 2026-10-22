import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        // Starts Koin and installs the UNUserNotificationCenter delegate before any notification can be delivered.
        IosAppBootstrap.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
