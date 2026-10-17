#if os(iOS) || os(macOS)
import Foundation
import Testing
@testable import SunWattKit

// MARK: Kiosk field draft

@MainActor
private final class Saved {
    var values: [String] = []
}

private let validLink = "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=abc123"

/// Never finishes unless cancelled: the debounce never fires on its own.
private let neverSleep: @Sendable (Duration) async throws -> Void = { _ in try await Task.sleep(for: .seconds(3600)) }
/// Finishes at once: the debounce fires as soon as the task runs.
private let noSleep: @Sendable (Duration) async throws -> Void = { _ in }

@MainActor
private func makeDraft(
    saved: String = "", sleep: @escaping @Sendable (Duration) async throws -> Void = neverSleep, into recorder: Saved
) -> KioskURLDraft {
    KioskURLDraft(saved: saved, sleep: sleep) { recorder.values.append($0) }
}

@MainActor @Suite struct KioskURLDraftTests {
    @Test func typingNeverSavesPerKeystroke() async {
        let recorder = Saved()
        let draft = makeDraft(sleep: noSleep, into: recorder)
        var typed = ""
        for character in validLink {
            typed.append(character)
            draft.update(typed)
        }
        // The loop never suspended, so no debounce task ran yet: nothing saved, text is all there.
        #expect(recorder.values.isEmpty)
        #expect(draft.text == validLink)
        // After the pause one save with the whole link, not one per keystroke.
        await draft.pending?.value
        #expect(recorder.values == [validLink])
    }

    @Test func aValidLinkIsSavedOnlyAfterTheDebounce() async {
        let recorder = Saved()
        let draft = makeDraft(into: recorder)
        draft.update(validLink)
        #expect(recorder.values.isEmpty)
        #expect(draft.pending != nil)
        draft.commitNow()
        #expect(recorder.values == [validLink])
        #expect(draft.pending == nil)
    }

    @Test func theDebounceUsesTheConfiguredPause() async {
        let recorder = Saved()
        let draft = KioskURLDraft(saved: "", debounce: .milliseconds(20)) { recorder.values.append($0) }
        draft.update(validLink)
        #expect(recorder.values.isEmpty)
        await draft.pending?.value
        #expect(recorder.values == [validLink])
    }

    @Test func aNewKeystrokeRestartsTheDebounce() async {
        let recorder = Saved()
        let draft = makeDraft(sleep: noSleep, into: recorder)
        draft.update(validLink)
        let first = draft.pending
        draft.update(validLink + "x")
        #expect(first?.isCancelled == true)
        await draft.pending?.value
        await first?.value
        #expect(recorder.values == [validLink + "x"])
    }

    @Test func textThatIsNotAValidLinkWaitsForSubmitOrFocusLoss() {
        let recorder = Saved()
        let draft = makeDraft(into: recorder)
        draft.update("https://eu5.fusionsolar")
        #expect(draft.pending == nil)
        #expect(recorder.values.isEmpty)
        #expect(draft.inputState == .invalid)
        draft.commitNow()
        #expect(recorder.values == ["https://eu5.fusionsolar"])
    }

    @Test func breakingAValidLinkCancelsThePendingSave() {
        let recorder = Saved()
        let draft = makeDraft(into: recorder)
        draft.update(validLink)
        #expect(draft.pending != nil)
        draft.update("https://eu5")
        #expect(draft.pending == nil)
        #expect(recorder.values.isEmpty)
    }

    @Test func anEmptyFieldSavesEmpty() {
        let recorder = Saved()
        let draft = makeDraft(saved: validLink, into: recorder)
        draft.update("")
        #expect(draft.pending == nil)
        #expect(recorder.values.isEmpty)
        #expect(draft.inputState == .empty)
        draft.commitNow()
        #expect(recorder.values == [""])
    }

    @Test func whitespaceOnlySavesEmptyAndTextIsTrimmed() {
        let recorder = Saved()
        let draft = makeDraft(saved: validLink, into: recorder)
        draft.update("  \n")
        draft.commitNow()
        #expect(recorder.values == [""])
        draft.update("  \(validLink)\n")
        draft.commitNow()
        #expect(recorder.values == ["", validLink])
    }

    @Test func pasteSavesAtOnceAndReplacesTheText() {
        let recorder = Saved()
        let draft = makeDraft(saved: "old", into: recorder)
        draft.update("half typed")
        draft.paste("  \(validLink)\n")
        #expect(recorder.values == [validLink])
        #expect(draft.text == validLink)
        #expect(draft.pending == nil)
        #expect(draft.inputState == .valid(host: "eu5.fusionsolar.huawei.com"))
    }

    @Test func nothingIsSavedTwiceOrWhenUnchanged() async {
        let recorder = Saved()
        let draft = makeDraft(saved: validLink, sleep: noSleep, into: recorder)
        draft.commitNow()
        draft.update(validLink)
        #expect(draft.pending == nil)
        draft.update(validLink + " ")   // same after trimming
        #expect(draft.pending == nil)
        draft.commitNow()
        #expect(recorder.values.isEmpty)
        draft.update(validLink + "9")
        draft.commitNow()
        draft.commitNow()
        draft.commitNow()
        await draft.pending?.value
        #expect(recorder.values == [validLink + "9"])
    }

    @Test func savingGoesThroughTheModelOncePerCommit() {
        let model = AppModel(launch: AppLaunchOptions(demo: true))
        let draft = KioskURLDraft(saved: model.settings.kioskURL) { value in
            model.updateSettings { $0.kioskURL = value }
        }
        draft.update("https://x")
        draft.update("https://x.test/a?kk=1")
        #expect(model.settings.kioskURL != "https://x.test/a?kk=1")
        draft.commitNow()
        #expect(model.settings.kioskURL == "https://x.test/a?kk=1")
    }
}

// MARK: Refresh

/// Price source that answers per region: `slow` regions wait (and notice cancellation), others answer at once.
private struct RegionalPrices: PriceDataSource {
    let region: PriceRegion
    let slow: Set<PriceRegion>
    let started: Started

    func fetch(day: Date) async throws -> PriceSeries {
        started.mark(region)
        if slow.contains(region) { try await Task.sleep(for: .seconds(30)) }
        let points = (0..<24).map {
            PricePoint(start: Constants.madridCalendar.startOfDay(for: Date()).addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: 100)
        }
        return PriceSeries(points: points, granularity: .hourly, fetchedAt: Date(), region: region)
    }
}

final class Started: @unchecked Sendable {
    private let lock = NSLock()
    private var regions: [PriceRegion] = []
    func mark(_ region: PriceRegion) { lock.lock(); regions.append(region); lock.unlock() }
    var all: [PriceRegion] { lock.lock(); defer { lock.unlock() }; return regions }
}

@MainActor
private func makeModel(slow: Set<PriceRegion>, region: PriceRegion = .spain) -> (AppModel, Started, ReloadCount) {
    let (store, _) = storeTempStore()
    store.settings = SunWattSettings(kioskURL: "", showTomorrowPrices: false, priceRegion: region, onboardingCompleted: true)
    let started = Started()
    let loader = SunWattLoader(store: store, makeSolar: { _ in nil }, makePrices: { RegionalPrices(region: $0, slow: slow, started: started) })
    let model = AppModel(launch: AppLaunchOptions(), store: store, loader: loader)
    let reloads = ReloadCount()
    model.reloadWidgets = { reloads.count += 1 }
    model.countWidgets = { 0 }
    return (model, started, reloads)
}

@MainActor final class ReloadCount { var count = 0 }

@MainActor
private func waitUntil(_ condition: () -> Bool) async {
    for _ in 0..<2000 where !condition() { try? await Task.sleep(for: .milliseconds(2)) }
}

@MainActor @Suite struct AppModelRefreshTests {
    @Test func refreshLoadsAndReloadsWidgetsOnce() async {
        let (model, _, reloads) = makeModel(slow: [])
        await model.refresh()
        #expect(model.data?.prices?.region == .spain)
        #expect(model.hasLoaded && !model.isLoading)
        #expect(reloads.count == 1)
    }

    @Test func aSecondRefreshTakesOverFromASlowOne() async {
        let (model, started, reloads) = makeModel(slow: [.spain])
        let slowRefresh = Task { await model.refresh() }
        await waitUntil { started.all.count == 1 }
        #expect(model.isLoading)
        // Not dropped any more: this one cancels the slow load and runs.
        model.updateSettings { $0.priceRegion = .france }
        await model.refresh()
        #expect(started.all == [.spain, .france])
        #expect(model.data?.prices?.region == .france)
        #expect(model.hasLoaded && !model.isLoading)
        await slowRefresh.value
        // The cancelled load left nothing behind.
        #expect(model.data?.prices?.region == .france)
        #expect(!model.isLoading)
        #expect(reloads.count == 1)
    }

    @Test func refreshAgainWithoutSettingsChangeSupersedesTheRunningLoad() async {
        let (model, started, _) = makeModel(slow: [.spain])
        let first = Task { await model.refresh() }
        await waitUntil { started.all.count == 1 }
        let second = Task { await model.refresh() }
        await waitUntil { started.all.count == 2 }
        // Both are slow; cancelling the model-level work ends them without a result.
        first.cancel()
        second.cancel()
        await first.value
        await second.value
        #expect(!model.isLoading)
        #expect(model.data == nil && !model.hasLoaded)
    }

    @Test func aRegionChangeDuringASlowLoadEndsWithTheNewRegion() async {
        let (model, started, _) = makeModel(slow: [.spain])
        let slowRefresh = Task { await model.refresh() }
        await waitUntil { started.all.count == 1 }
        model.updateSettings { $0.priceRegion = .germany }
        // The old load no longer counts: the model is not "loading" and shows no old-region data.
        #expect(!model.isLoading)
        #expect(model.data?.prices?.region != .spain)
        await model.refresh()
        await slowRefresh.value
        #expect(model.data?.prices?.region == .germany)
        #expect(model.settings.priceRegion == .germany)
    }

    @Test func widgetsReloadAfterTheDataIsStoredNotBeforeWhenDataMustBeFetched() async {
        let (model, _, reloads) = makeModel(slow: [])
        model.updateSettings { $0.priceRegion = .portugal }
        #expect(reloads.count == 0)
        await model.refresh()
        #expect(reloads.count == 1)
        // A setting that needs no fetch reloads at once.
        model.updateSettings { $0.onboardingCompleted = false }
        #expect(reloads.count == 2)
    }

    @Test func automaticRefreshReusesDataTheWidgetsJustStored() async {
        let (model, started, _) = makeModel(slow: [])
        await model.refresh()
        #expect(started.all.count == 1)
        await model.start()
        #expect(started.all.count == 1)
        // An explicit refresh always fetches.
        await model.refresh()
        #expect(started.all.count == 2)
    }
}
#endif
