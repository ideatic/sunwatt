import SwiftUI
import SunWattKit

@main
struct SunWattApp: App {
    @State private var model: AppModel

    init() {
        AppGroupStore.shared.adoptSettings(from: .standard)
        AppGroupStore.shared.forgetSecretsOfEarlierInstall()
        AppGroupStore.shared.moveSecretsToKeychain()
        #if DEBUG
        DebugKioskPrefill.apply()
        #endif
        let model = AppModel()
        #if os(iOS)
        if !model.isDemo {
            PhoneWatchSync.shared.start()
            model.onSettingsSaved = { PhoneWatchSync.shared.push($0) }
        }
        #endif
        _model = State(initialValue: model)
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
