import Foundation
#if os(watchOS)
import WatchConnectivity
import WidgetKit
#endif

/// Settings transport between the iPhone app and the watch: the iPhone sends the encoded `HomeDashSettings`
/// as WatchConnectivity application context; the watch saves it into its own App Group store.
public enum WatchSettingsSync {
    public static let settingsKey = "homedash.settings"
    public static let sentAtKey = "homedash.sentAt"

    /// Application context for `WCSession.updateApplicationContext`. `sentAt` makes every push distinct.
    public static func context(for settings: HomeDashSettings, sentAt: Date = Date()) -> [String: Any] {
        guard let data = try? JSONEncoder().encode(settings) else { return [:] }
        return [settingsKey: data, sentAtKey: sentAt.timeIntervalSince1970]
    }

    /// Settings in a received context; nil when the key is absent or cannot be decoded.
    public static func settings(from context: [String: Any]) -> HomeDashSettings? {
        guard let data = context[settingsKey] as? Data else { return nil }
        return try? JSONDecoder().decode(HomeDashSettings.self, from: data)
    }
}

#if os(watchOS)
/// Watch side: activates the session, saves received settings into the watch App Group store,
/// reloads complications and tells the app so it can refresh.
public final class WatchSettingsReceiver: NSObject, WCSessionDelegate, @unchecked Sendable {
    public static let shared = WatchSettingsReceiver()

    private let lock = NSLock()
    private var handler: (@Sendable () -> Void)?
    private var store: AppGroupStore = .shared

    /// Called (on an arbitrary thread) after new settings were saved.
    public func onSettingsChange(_ handler: @escaping @Sendable () -> Void) {
        lock.lock(); self.handler = handler; lock.unlock()
    }

    public func start(store: AppGroupStore = .shared) {
        guard WCSession.isSupported() else { return }
        self.store = store
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    /// Saves settings from `context`. Returns true when the stored settings changed.
    @discardableResult
    func apply(_ context: [String: Any]) -> Bool {
        guard let settings = WatchSettingsSync.settings(from: context), settings != store.settings else { return false }
        store.settings = settings
        WidgetCenter.shared.reloadAllTimelines()
        lock.lock(); let callback = handler; lock.unlock()
        callback?()
        return true
    }

    public func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        // A context that arrived while the app was not running is kept by the system.
        if activationState == .activated { apply(session.receivedApplicationContext) }
    }

    public func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        apply(applicationContext)
    }
}
#endif
