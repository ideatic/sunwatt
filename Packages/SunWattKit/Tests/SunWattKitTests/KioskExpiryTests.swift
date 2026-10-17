import Foundation
import Testing
@testable import SunWattKit

/// Kiosk links expire one year after Huawei creates them: detection (rejected for 30 minutes) and the advance notice.
@Suite struct KioskExpiryTests {
    private let now = madrid(2026, 6, 1, 12, 30)

    private func loader(_ store: AppGroupStore, solar: Result<SolarSnapshot, SunWattError>, at date: Date) -> SunWattLoader {
        SunWattLoader(store: store, makeSolar: { _ in FakeSolar(result: solar) }, prices: FakePlain(result: .success(storeSamplePrices())), now: { date })
    }

    private func kioskStore() -> AppGroupStore {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false)
        store.saveSolar(storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 11, 0)))
        return store
    }

    @Test func rejectionAndForbiddenStatusesThrowLinkRejected() async {
        for status in [401, 403, 404] {
            let http = StubHTTPClient(.ok(Data(), status: status))
            let client = FusionSolarKioskClient(endpoint: KioskEndpoint(parsing: testKioskURL)!, http: http)
            await #expect(throws: SunWattError.kioskLinkRejected) { try await client.fetch() }
        }
        let http = StubHTTPClient(.ok(Data(), status: 500))
        let client = FusionSolarKioskClient(endpoint: KioskEndpoint(parsing: testKioskURL)!, http: http)
        await #expect(throws: SunWattError.http(status: 500)) { try await client.fetch() }
    }

    @Test func firstRejectionsKeepTheCachedData() async {
        let store = kioskStore()
        let data = await loader(store, solar: .failure(.kioskLinkRejected), at: now).load()
        #expect(!data.solarLinkExpired)
        #expect(data.solar != nil)
        #expect(data.solarStatus == .stale(since: madrid(2026, 6, 1, 11, 0)))
        // 20 minutes later: still within the grace time.
        let later = await loader(store, solar: .failure(.kioskLinkRejected), at: now.addingTimeInterval(20 * 60)).load()
        #expect(!later.solarLinkExpired)
    }

    @Test func rejectedForThirtyMinutesCountsAsExpired() async {
        let store = kioskStore()
        _ = await loader(store, solar: .failure(.kioskLinkRejected), at: now).load()
        let data = await loader(store, solar: .failure(.kioskLinkRejected), at: now.addingTimeInterval(31 * 60)).load()
        #expect(data.solarLinkExpired)
        #expect(data.solar == nil)
        #expect(data.solarStatus == .error(message: SunWattError.kioskLinkRejected.localizedDescription))
        // Placeholders and snapshots (no network) agree.
        let cached = loader(store, solar: .failure(.kioskLinkRejected), at: now.addingTimeInterval(32 * 60)).cached()
        #expect(cached.solarLinkExpired && cached.solar == nil)
        // Prices are unaffected.
        #expect(data.priceStatus == .fresh)
    }

    @Test func otherErrorsDoNotCount() async {
        let store = kioskStore()
        _ = await loader(store, solar: .failure(.network("offline")), at: now).load()
        let data = await loader(store, solar: .failure(.network("offline")), at: now.addingTimeInterval(60 * 60)).load()
        #expect(!data.solarLinkExpired)
        #expect(data.solar != nil)
    }

    @Test func aSuccessEndsTheRejection() async {
        let store = kioskStore()
        _ = await loader(store, solar: .failure(.kioskLinkRejected), at: now).load()
        _ = await loader(store, solar: .success(storeSampleSolar(fetchedAt: now.addingTimeInterval(10 * 60), endpointID: nil)), at: now.addingTimeInterval(10 * 60)).load()
        let data = await loader(store, solar: .failure(.kioskLinkRejected), at: now.addingTimeInterval(45 * 60)).load()
        #expect(!data.solarLinkExpired)
    }

    @Test func aNewLinkStartsClean() async {
        let store = kioskStore()
        _ = await loader(store, solar: .failure(.kioskLinkRejected), at: now).load()
        store.settings.kioskURL = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=NEWLINK"
        let data = await loader(store, solar: .failure(.network("x")), at: now.addingTimeInterval(40 * 60)).load()
        #expect(!data.solarLinkExpired)
    }

    @Test func widgetShowsTheExpiredState() {
        var data = SunWattData(solar: nil, prices: nil, solarStatus: .error(message: "x"), priceStatus: .fresh)
        #expect(SolarModel(entry: SunWattEntry(date: now, data: data)).stateKind == .solarError)
        data.solarLinkExpired = true
        #expect(SolarModel(entry: SunWattEntry(date: now, data: data)).stateKind == .linkExpired)
        data.solarStatus = .notConfigured
        #expect(SolarModel(entry: SunWattEntry(date: now, data: data)).stateKind == .notConfigured)
    }

    @Test func siriSaysTheLinkExpired() throws {
        var data = SunWattData(solar: nil, prices: nil, solarStatus: .error(message: "x"), priceStatus: .fresh)
        data.solarLinkExpired = true
        let strings = try #require(IntentStrings.language("en"))
        let answer = SolarAnswer.make(from: data, now: now, strings: strings)
        #expect(answer.dialog.contains("expired"))
        let es = try #require(IntentStrings.language("es"))
        #expect(!SolarAnswer.make(from: data, now: now, strings: es).dialog.contains("expired"))
    }

    @Test func settingsWithoutExpiryFieldsStillDecode() throws {
        let json = Data(#"{"kioskURL":"x","showTomorrowPrices":true,"priceRegion":"spain"}"#.utf8)
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: json)
        #expect(settings.kioskLinkSavedAt == nil)
        #expect(!settings.kioskExpiryNoticeDismissed)
    }
}

@MainActor @Suite struct KioskExpiryNoticeTests {
    private func demo(savedDaysAgo days: Double, dismissed: Bool = false, expired: Bool = false) -> AppModel {
        var settings = SunWattSettings(kioskURL: testKioskURL)
        settings.kioskLinkSavedAt = SampleData.now.addingTimeInterval(-days * 24 * 3600)
        settings.kioskExpiryNoticeDismissed = dismissed
        return AppModel(demo: .day, settings: settings, kioskLinkExpired: expired)
    }

    @Test func noticeShowsInTheLastMonth() {
        #expect(demo(savedDaysAgo: 300).kioskExpiryNoticeDate == nil)
        let date = demo(savedDaysAgo: 350).kioskExpiryNoticeDate
        #expect(date != nil)
        #expect(Calendar.current.date(byAdding: .year, value: 1, to: SampleData.now.addingTimeInterval(-350 * 24 * 3600)) == date)
        // After the expected date it stays until the link is replaced.
        #expect(demo(savedDaysAgo: 380).kioskExpiryNoticeDate != nil)
    }

    @Test func noticeHidesWhenClosedOrAlreadyExpired() {
        #expect(demo(savedDaysAgo: 350, dismissed: true).kioskExpiryNoticeDate == nil)
        #expect(demo(savedDaysAgo: 350, expired: true).kioskExpiryNoticeDate == nil)
    }

    @Test func noticeOnlyForFusionSolar() {
        var settings = SunWattSettings(kioskURL: testKioskURL, solarProvider: .pvOutput)
        settings.kioskLinkSavedAt = SampleData.now.addingTimeInterval(-350 * 24 * 3600)
        #expect(AppModel(demo: .day, settings: settings).kioskExpiryNoticeDate == nil)
    }

    @Test func savingANewLinkRestartsTheYear() {
        let (store, _) = storeTempStore()
        var settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, onboardingCompleted: true)
        settings.kioskLinkSavedAt = Date().addingTimeInterval(-350 * 24 * 3600)
        settings.kioskExpiryNoticeDismissed = true
        store.settings = settings
        let loader = SunWattLoader(store: store, makeSolar: { _ in nil }, prices: FakePlain(result: .success(storeSamplePrices())))
        let model = AppModel(launch: AppLaunchOptions(), store: store, loader: loader)
        model.reloadWidgets = {}
        model.countWidgets = { 0 }
        model.updateSettings { $0.kioskURL = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=NEW" }
        #expect(model.settings.kioskLinkSavedAt.map { abs($0.timeIntervalSinceNow) < 60 } == true)
        #expect(!model.settings.kioskExpiryNoticeDismissed)
        model.updateSettings { $0.kioskURL = "" }
        #expect(model.settings.kioskLinkSavedAt == nil)
    }

    @Test func existingLinksGetADateOnFirstLaunch() {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, onboardingCompleted: true)
        let loader = SunWattLoader(store: store, makeSolar: { _ in nil }, prices: FakePlain(result: .success(storeSamplePrices())))
        let model = AppModel(launch: AppLaunchOptions(), store: store, loader: loader)
        #expect(model.settings.kioskLinkSavedAt != nil)
        #expect(store.settings.kioskLinkSavedAt != nil)
    }
}
