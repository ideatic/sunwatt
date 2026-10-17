import Foundation
import Testing
@testable import SunWattKit

/// Counts fetches; answers with data stamped at `loaderNow`.
final class CountingSources: @unchecked Sendable {
    private let lock = NSLock()
    private var _solar = 0
    private var _prices = 0
    var solarCalls: Int { lock.lock(); defer { lock.unlock() }; return _solar }
    var priceCalls: Int { lock.lock(); defer { lock.unlock() }; return _prices }
    func countSolar() { lock.lock(); _solar += 1; lock.unlock() }
    func countPrices() { lock.lock(); _prices += 1; lock.unlock() }
}

private struct CountingSolar: SolarDataSource {
    let counter: CountingSources
    var fetchedAt: Date = loaderNow
    func fetch() async throws -> SolarSnapshot {
        counter.countSolar()
        return storeSampleSolar(fetchedAt: fetchedAt, endpointID: nil)
    }
}

private struct CountingPrices: PriceDataSource {
    let counter: CountingSources
    var region: PriceRegion = .spain
    var fetchedAt: Date = loaderNow
    func fetch(day: Date) async throws -> PriceSeries {
        counter.countPrices()
        var series = storeSamplePrices(fetchedAt: fetchedAt)
        series.region = region
        return series
    }
}

private func loader(_ store: AppGroupStore, counter: CountingSources, solar: Bool = true, now: Date = loaderNow) -> SunWattLoader {
    SunWattLoader(
        store: store,
        makeSolar: { _ in solar ? CountingSolar(counter: counter) : nil },
        makePrices: { region in CountingPrices(counter: counter, region: region) },
        now: { now })
}

@Suite struct CacheWindowTests {
    /// A store with solar and prices fetched `age` seconds before `loaderNow`.
    private func seeded(age: TimeInterval, endpointID: String? = testKioskID, region: PriceRegion = .spain) -> AppGroupStore {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: true, priceRegion: region)
        let fetched = loaderNow.addingTimeInterval(-age)
        store.saveSolar(storeSampleSolar(fetchedAt: fetched, endpointID: endpointID))
        var prices = storeSamplePrices(fetchedAt: fetched)
        prices.region = region
        store.savePrices(prices)
        return store
    }

    @Test func recentCacheAnswersWithoutNetwork() async {
        let counter = CountingSources()
        let store = seeded(age: 60)
        let data = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 0 && counter.priceCalls == 0)
        #expect(data.solarStatus == .fresh && data.priceStatus == .fresh)
        #expect(data.solar == store.loadSolar() && data.prices == store.loadPrices())
    }

    @Test func oldCacheFetchesBothSources() async {
        let counter = CountingSources()
        let store = seeded(age: 121)
        let data = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
        #expect(data.solarStatus == .fresh && data.priceStatus == .fresh)
    }

    @Test func defaultAlwaysFetches() async {
        let counter = CountingSources()
        let store = seeded(age: 1)
        _ = await loader(store, counter: counter).load()
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
        _ = await loader(store, counter: counter).load(maxCacheAge: 0)
        #expect(counter.solarCalls == 2 && counter.priceCalls == 2)
    }

    @Test func oneOldSourceFetchesBoth() async {
        let counter = CountingSources()
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: true)
        store.saveSolar(storeSampleSolar(fetchedAt: loaderNow.addingTimeInterval(-10)))
        store.savePrices(storeSamplePrices(fetchedAt: loaderNow.addingTimeInterval(-600)))
        _ = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
    }

    @Test func solarIsIgnoredWhenNotConfigured() async {
        let counter = CountingSources()
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: "", showTomorrowPrices: true)
        store.savePrices(storeSamplePrices(fetchedAt: loaderNow.addingTimeInterval(-30)))
        let data = await loader(store, counter: counter, solar: false).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 0 && counter.priceCalls == 0)
        #expect(data.solarStatus == .notConfigured)
        #expect(data.priceStatus == .fresh)
    }

    @Test func pricesOfAnotherRegionDoNotCount() async {
        let counter = CountingSources()
        let store = seeded(age: 10, region: .portugal)
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: true, priceRegion: .spain)
        _ = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.priceCalls == 1)
    }

    @Test func pricesThatDoNotCoverNowDoNotCount() async {
        let counter = CountingSources()
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: true)
        store.saveSolar(storeSampleSolar(fetchedAt: loaderNow.addingTimeInterval(-10)))
        // Yesterday's prices, fetched a moment ago: they exist but cover no hour of today.
        store.savePrices(storeSamplePrices(day: madrid(2026, 5, 31, 0, 0), fetchedAt: loaderNow.addingTimeInterval(-10)))
        _ = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.priceCalls == 1)
    }

    @Test func solarFromAnotherKioskLinkDoesNotCount() async {
        let counter = CountingSources()
        let store = seeded(age: 10, endpointID: KioskEndpoint(parsing: "https://other.test/x?kk=OTHER")!.cacheID)
        _ = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 1)
    }

    @Test func solarWithoutIdentifierDoesNotCount() async {
        let counter = CountingSources()
        let store = seeded(age: 10, endpointID: nil)
        _ = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 1)
    }

    @Test func solarFromYesterdayDoesNotCountEvenWithinTheWindow() async {
        let counter = CountingSources()
        let now = madrid(2026, 6, 2, 0, 0).addingTimeInterval(30)
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: true)
        store.saveSolar(storeSampleSolar(fetchedAt: now.addingTimeInterval(-60)))
        store.savePrices(storeSamplePrices(day: madrid(2026, 6, 2, 0, 0), fetchedAt: now.addingTimeInterval(-60)))
        _ = await loader(store, counter: counter, now: now).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 1)
    }

    @Test func aFetchTimeInTheFutureDoesNotCount() async {
        let counter = CountingSources()
        let store = seeded(age: -30)
        _ = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
    }

    /// The case behind the 429s: N widgets reload at once after a refresh. One fetch, then everyone reads the store.
    @Test func manyReloadsAfterOneFetchMakeNoRequests() async {
        let counter = CountingSources()
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: true)
        let first = await loader(store, counter: counter).load()
        #expect(first.solarStatus == .fresh)
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
        await withTaskGroup(of: SunWattData.self) { group in
            for _ in 0..<12 { group.addTask { await loader(store, counter: counter).load(maxCacheAge: 120) } }
            for await data in group {
                #expect(data.solarStatus == .fresh && data.priceStatus == .fresh)
                #expect(data.solar != nil && data.prices != nil)
            }
        }
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
    }

    @Test func explicitRefreshIgnoresTheWindow() async {
        let counter = CountingSources()
        let store = seeded(age: 5)
        // What RefreshWidgetIntent does: maxCacheAge 0.
        _ = await loader(store, counter: counter).load(deadline: 10, maxCacheAge: 0)
        #expect(counter.solarCalls == 1 && counter.priceCalls == 1)
    }

    @Test func controlsAndIntentsUseTheWindow() async {
        let counter = CountingSources()
        let store = seeded(age: 100)
        let source = IntentDataSource(loader: loader(store, counter: counter))
        #expect(source.maxCacheAge == SunWattLoader.sharedCacheWindow)
        // Price for the current hour is cached: answered without a request.
        _ = await source.data(for: .price)
        #expect(counter.priceCalls == 0)
    }
}

@Suite struct SolarEndpointCacheTests {
    private func settings(_ url: String = testKioskURL) -> SunWattSettings {
        SunWattSettings(kioskURL: url, showTomorrowPrices: true)
    }
    private let otherURL = "https://other.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=OTHERKK"

    @Test func fetchedSnapshotIsStoredWithTheEndpointIDNotTheToken() async throws {
        let counter = CountingSources()
        let (store, directory) = storeTempStore()
        store.settings = settings()
        _ = await loader(store, counter: counter).load()
        #expect(store.loadSolar()?.endpointID == testKioskID)
        let raw = try String(contentsOf: directory.appendingPathComponent("solar.json"), encoding: .utf8)
        #expect(!raw.contains("TESTKK"))
        #expect(raw.contains(testKioskID))
    }

    @Test func cacheOfAnotherLinkIsIgnoredWhenTheFetchFails() async {
        let (store, _) = storeTempStore()
        store.settings = settings(otherURL)
        store.saveSolar(storeSampleSolar(endpointID: testKioskID))
        let data = await makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solar == nil)
        #expect(data.solarStatus == .error(message: SunWattError.unavailable.localizedDescription))
    }

    @Test func cacheOfTheSameLinkIsStillUsedWhenTheFetchFails() async {
        let (store, _) = storeTempStore()
        store.settings = settings()
        let cached = storeSampleSolar()
        store.saveSolar(cached)
        let data = await makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solar == cached)
        #expect(data.solarStatus == .stale(since: cached.fetchedAt))
    }

    @Test func cacheWithoutIdentifierNeverMatches() async {
        let (store, _) = storeTempStore()
        store.settings = settings()
        store.saveSolar(storeSampleSolar(endpointID: nil))
        let loader = makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .failure(.unavailable)))
        #expect(loader.cached().solar == nil)
        #expect(await loader.load().solar == nil)
    }

    @Test func cachedIgnoresAnotherLinkAndKeepsTheRightOne() {
        let (store, _) = storeTempStore()
        store.settings = settings()
        let right = storeSampleSolar()
        store.saveSolar(right)
        let loader = makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .failure(.unavailable)))
        #expect(loader.cached().solar == right)
        store.settings = settings(otherURL)
        let other = loader.cached()
        #expect(other.solar == nil)
        #expect(other.solarStatus == .error(message: SunWattError.unavailable.localizedDescription))
    }

    @Test func changingTheLinkThenFetchingReplacesTheSnapshot() async {
        let counter = CountingSources()
        let (store, _) = storeTempStore()
        store.settings = settings()
        _ = await loader(store, counter: counter).load()
        store.settings = settings(otherURL)
        let data = await loader(store, counter: counter).load(maxCacheAge: 120)
        #expect(counter.solarCalls == 2)
        #expect(data.solar?.endpointID == KioskEndpoint(parsing: otherURL)?.cacheID)
        #expect(store.loadSolar()?.endpointID == KioskEndpoint(parsing: otherURL)?.cacheID)
    }

    @Test func oldCacheFilesDecodeWithoutIdentifier() throws {
        let json = #"{"curve":[],"fetchedAt":"2026-06-01T10:00:00Z"}"#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let snapshot = try decoder.decode(SolarSnapshot.self, from: Data(json.utf8))
        #expect(snapshot.endpointID == nil)
    }

    @Test func cacheIDDependsOnHostAndTokenAndHidesThem() {
        let a = KioskEndpoint(host: "h.test", kk: "abc").cacheID
        #expect(a == KioskEndpoint(host: "h.test", kk: "abc").cacheID)
        #expect(a != KioskEndpoint(host: "h.test", kk: "abd").cacheID)
        #expect(a != KioskEndpoint(host: "i.test", kk: "abc").cacheID)
        #expect(a.count == 64 && !a.contains("abc") && !a.contains("h.test"))
    }
}
