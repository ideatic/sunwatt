import Foundation
import SunWattKit
#if os(iOS)
import WatchConnectivity
#endif

/// iPhone side of the watch settings sync. Call `PhoneWatchSync.shared.start()` once at launch and
/// `push(_:)` whenever the settings change. On macOS both calls do nothing.
final class PhoneWatchSync: NSObject, @unchecked Sendable {
    static let shared = PhoneWatchSync()

    #if os(iOS)
    private let lock = NSLock()
    private var pending: SunWattSettings?

    /// Activates the WatchConnectivity session and sends the stored settings.
    func start() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
        push(AppGroupStore.shared.settings)
    }

    /// Sends `settings` to the watch as application context (the system keeps only the latest).
    /// Before the session is active the latest value waits and is sent on activation.
    func push(_ settings: SunWattSettings) {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        guard session.activationState == .activated else {
            lock.lock(); pending = settings; lock.unlock()
            return
        }
        send(settings, on: session)
    }

    private func send(_ settings: SunWattSettings, on session: WCSession) {
        guard session.isPaired, session.isWatchAppInstalled else { return }
        try? session.updateApplicationContext(WatchSettingsSync.context(for: settings))
    }
    #else
    func start() {}
    func push(_ settings: SunWattSettings) {}
    #endif
}

#if os(iOS)
extension PhoneWatchSync: WCSessionDelegate {
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        guard activationState == .activated else { return }
        lock.lock(); let settings = pending ?? AppGroupStore.shared.settings; pending = nil; lock.unlock()
        send(settings, on: session)
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    /// The watch app was just installed: send the settings it has not seen yet.
    func sessionWatchStateDidChange(_ session: WCSession) {
        guard session.activationState == .activated else { return }
        send(AppGroupStore.shared.settings, on: session)
    }
}
#endif
