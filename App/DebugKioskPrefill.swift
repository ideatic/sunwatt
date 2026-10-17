#if DEBUG
import Foundation
import HomeDashKit

/// Debug builds only: when the kiosk URL setting is empty, fills it from `kiosk.local`, which the build copies
/// from the git-ignored `Config/kiosk.local` into the app bundle (see the pre-build script in project.yml).
/// Release builds neither contain the file nor this code. The value is never logged.
enum DebugKioskPrefill {
    static func apply() {
        guard !AppLaunchOptions.current.demo else { return }
        let store = AppGroupStore.shared
        var settings = store.settings
        guard settings.kioskURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let url = Bundle.main.url(forResource: "kiosk", withExtension: "local"),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        settings.kioskURL = trimmed
        store.settings = settings
    }
}
#endif
