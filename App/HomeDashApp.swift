import SwiftUI
import HomeDashKit

@main
struct HomeDashApp: App {
    @State private var model: AppModel

    init() {
        #if DEBUG
        DebugKioskPrefill.apply()
        #endif
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        #if os(macOS)
        WindowGroup {
            AppRootView(model: model)
        }
        .defaultSize(width: 900, height: 640)
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(after: .toolbar) {
                AppRefreshButton(model: model)
            }
        }
        Settings {
            AppSettingsWindowView(model: model)
        }
        #else
        WindowGroup {
            AppRootView(model: model)
        }
        #endif
    }
}
