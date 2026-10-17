import Foundation
import Testing
@testable import SunWattKit

private final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    func bump() { lock.lock(); value += 1; lock.unlock() }
    var count: Int { lock.lock(); defer { lock.unlock() }; return value }
}

private func context(_ region: PriceRegion, kiosk: String = "") -> [String: Any] {
    WatchSettingsSync.context(for: SunWattSettings(kioskURL: kiosk, showTomorrowPrices: true, priceRegion: region))
}

@Suite struct WatchInboxTests {
    @Test func applySavesIntoTheInjectedStoreAndNotifies() {
        let (store, _) = storeTempStore()
        let saved = Counter(), notified = Counter()
        let inbox = WatchSettingsInbox(store: store, afterSave: { saved.bump() })
        inbox.onSettingsChange { notified.bump() }
        #expect(inbox.apply(context(.germany)))
        #expect(store.settings.priceRegion == .germany)
        #expect(saved.count == 1 && notified.count == 1)
    }

    @Test func sameSettingsAreNotAChange() {
        let (store, _) = storeTempStore()
        let saved = Counter(), notified = Counter()
        let inbox = WatchSettingsInbox(store: store, afterSave: { saved.bump() })
        inbox.onSettingsChange { notified.bump() }
        #expect(inbox.apply(context(.france)))
        #expect(!inbox.apply(context(.france)))
        #expect(saved.count == 1 && notified.count == 1)
    }

    @Test func brokenContextIsIgnored() {
        let (store, _) = storeTempStore()
        let notified = Counter()
        let inbox = WatchSettingsInbox(store: store)
        inbox.onSettingsChange { notified.bump() }
        #expect(!inbox.apply([:]))
        #expect(!inbox.apply([WatchSettingsSync.settingsKey: Data("nope".utf8)]))
        #expect(store.settings == SunWattSettings())
        #expect(notified.count == 0)
    }

    /// The system delivers the stored context at activation, before the UI registers its handler.
    @Test func aChangeBeforeTheHandlerExistsIsDeliveredOnceWhenItRegisters() {
        let (store, _) = storeTempStore()
        let inbox = WatchSettingsInbox(store: store)
        #expect(inbox.apply(context(.austria)))
        let notified = Counter()
        inbox.onSettingsChange { notified.bump() }
        #expect(notified.count == 1)
        // Registering again does not repeat it.
        inbox.onSettingsChange { notified.bump() }
        #expect(notified.count == 1)
        // New changes after that go straight to the handler.
        #expect(inbox.apply(context(.belgium)))
        #expect(notified.count == 2)
    }

    @Test func nothingPendingMeansNoCallOnRegistration() {
        let (store, _) = storeTempStore()
        let inbox = WatchSettingsInbox(store: store)
        let notified = Counter()
        inbox.onSettingsChange { notified.bump() }
        #expect(notified.count == 0)
    }

    @Test func aChangeSeenByTheHandlerIsNotKeptForLater() {
        let (store, _) = storeTempStore()
        let inbox = WatchSettingsInbox(store: store)
        let first = Counter(), second = Counter()
        inbox.onSettingsChange { first.bump() }
        #expect(inbox.apply(context(.italyNorth)))
        inbox.onSettingsChange { second.bump() }
        #expect(first.count == 1 && second.count == 0)
    }

    @Test func parallelAppliesAndRegistrationAreSafe() async {
        let (store, _) = storeTempStore()
        let inbox = WatchSettingsInbox(store: store)
        let notified = Counter()
        let regions = PriceRegion.allCases
        await withTaskGroup(of: Void.self) { group in
            for i in 0..<40 {
                group.addTask { _ = inbox.apply(context(regions[i % regions.count], kiosk: "https://h.test/x?kk=\(i)")) }
                if i == 20 { group.addTask { inbox.onSettingsChange { notified.bump() } } }
            }
        }
        #expect(regions.contains(store.settings.priceRegion))
        // Every change was either seen by the handler or kept for it; at least the ones after registration arrived.
        #expect(notified.count >= 1)
    }
}
