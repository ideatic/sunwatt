import Foundation
#if os(watchOS)
import WatchConnectivity
import WidgetKit
#endif

/// Settings transport between the iPhone app and the watch: the iPhone sends the encoded `SunWattSettings`
/// as WatchConnectivity application context; the watch saves it into its own App Group store.
public enum WatchSettingsSync {
    public static let settingsKey = "sunwatt.settings"
    public static let sentAtKey = "sunwatt.sentAt"

    /// Application context for `WCSession.updateApplicationContext`. `sentAt` makes every push distinct.
    public static func context(for settings: SunWattSettings, sentAt: Date = Date()) -> [String: Any] {
        guard let data = try? JSONEncoder().encode(settings) else { return [:] }
        return [settingsKey: data, sentAtKey: sentAt.timeIntervalSince1970]
    }

    /// Settings in a received context; nil when the key is absent or cannot be decoded.
    public static func settings(from context: [String: Any]) -> SunWattSettings? {
        guard let data = context[settingsKey] as? Data else { return nil }
        return try? JSONDecoder().decode(SunWattSettings.self, from: data)
    }
}

/// Watch side, platform-neutral so macOS tests reach it: saves received settings into the store, runs `afterSave`
/// (complication reload) and tells the app so it can refresh.
/// A change that arrives while nobody listens yet (the system delivers the stored context at activation, before the
/// UI registers) is kept and delivered when the listener registers.
public final class WatchSettingsInbox: @unchecked Sendable {
    private let store: AppGroupStore
    private let afterSave: @Sendable () -> Void
    private let lock = NSLock()
    private var handler: (@Sendable () -> Void)?
    private var changePending = false

    /// The store is fixed at creation: `apply` can run on any thread without racing a later assignment.
    public init(store: AppGroupStore = .shared, afterSave: @escaping @Sendable () -> Void = {}) {
        self.store = store
        self.afterSave = afterSave
    }

    /// Called (on an arbitrary thread) after new settings were saved. When a change is already pending, the
    /// handler runs once right away.
    public func onSettingsChange(_ handler: @escaping @Sendable () -> Void) {
        lock.lock()
        self.handler = handler
        let pending = changePending
        changePending = false
        lock.unlock()
        if pending { handler() }
    }

    /// Saves settings from `context`. Returns true when the stored settings changed.
    @discardableResult
    public func apply(_ context: [String: Any]) -> Bool {
        guard let settings = WatchSettingsSync.settings(from: context), settings != store.settings else { return false }
        store.settings = settings
        afterSave()
        lock.lock()
        let callback = handler
        if callback == nil { changePending = true }
        lock.unlock()
        callback?()
        return true
    }
}

#if os(watchOS)
/// Watch side: activates the session and hands received contexts to the inbox.
public final class WatchSettingsReceiver: NSObject, WCSessionDelegate, @unchecked Sendable {
    public static let shared = WatchSettingsReceiver()

    private let inbox: WatchSettingsInbox

    public init(store: AppGroupStore = .shared) {
        inbox = WatchSettingsInbox(store: store) { WidgetCenter.shared.reloadAllTimelines() }
        super.init()
    }

    /// Called (on an arbitrary thread) after new settings were saved; also for a change that arrived before.
    public func onSettingsChange(_ handler: @escaping @Sendable () -> Void) {
        inbox.onSettingsChange(handler)
    }

    public func start() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    public func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        // A context that arrived while the app was not running is kept by the system.
        if activationState == .activated { inbox.apply(session.receivedApplicationContext) }
    }

    public func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        inbox.apply(applicationContext)
    }
}
#endif
