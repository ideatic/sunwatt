import Foundation
import Testing
@testable import HomeDashKit

private struct FakeSolar: SolarDataSource {
    var result: Result<SolarSnapshot, HomeDashError>
    func fetch() async throws -> SolarSnapshot { try result.get() }
}

private final class CallLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _flags: [Bool] = []
    private var _days: [Date] = []
    func flag(_ v: Bool) { lock.lock(); _flags.append(v); lock.unlock() }
    func day(_ d: Date) { lock.lock(); _days.append(d); lock.unlock() }
    var flags: [Bool] { lock.lock(); defer { lock.unlock() }; return _flags }
    var days: [Date] { lock.lock(); defer { lock.unlock() }; return _days }
}

private struct FakePlain: PriceDataSource {
    var result: Result<PriceSeries, HomeDashError>
    var log = CallLog()
    func fetch(day: Date) async throws -> PriceSeries { log.day(day); return try result.get() }
}

private struct FakeRange: PriceRangeDataSource {
    var result: Result<PriceSeries, HomeDashError>
    var log = CallLog()
    func fetch(day: Date) async throws -> PriceSeries { try result.get() }
    func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries { log.flag(includeTomorrow); return try result.get() }
}

private let loaderNow = madrid(2026, 6, 1, 12, 30)

private func makeLoader(store: AppGroupStore, solar: SolarDataSource?, prices: PriceDataSource) -> HomeDashLoader {
    HomeDashLoader(store: store, makeSolar: { _ in solar }, prices: prices, now: { loaderNow })
}

private func configured(_ store: AppGroupStore, tomorrow: Bool = true) {
    store.settings = HomeDashSettings(kioskURL: "https://example.test/kiosk", showTomorrowPrices: tomorrow)
}

@Suite struct LoaderTests {
    @Test func bothSucceedFreshAndSaved() async {
        let (store, _) = storeTempStore()
        configured(store)
        let solar = storeSampleSolar(), prices = storeSamplePrices()
        let data = await makeLoader(store: store, solar: FakeSolar(result: .success(solar)), prices: FakePlain(result: .success(prices))).load()
        #expect(data.solarStatus == .fresh)
        #expect(data.priceStatus == .fresh)
        #expect(data.solar == solar)
        #expect(data.prices == prices)
        #expect(store.loadSolar() == solar)
        #expect(store.loadPrices() == prices)
    }

    @Test func solarFailureWithCacheIsStale() async {
        let (store, _) = storeTempStore()
        configured(store)
        let cached = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 11, 0))
        store.saveSolar(cached)
        let data = await makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solarStatus == .stale(since: cached.fetchedAt))
        #expect(data.solar == cached)
        #expect(data.priceStatus == .fresh)
    }

    @Test func solarFailureWithoutCacheIsError() async {
        let (store, _) = storeTempStore()
        configured(store)
        let data = await makeLoader(store: store, solar: FakeSolar(result: .failure(.http(status: 500))), prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solarStatus == .error(message: HomeDashError.http(status: 500).localizedDescription))
        #expect(data.solar == nil)
    }

    @Test func emptyURLIsNotConfigured() async {
        let (store, _) = storeTempStore()
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solarStatus == .notConfigured)
    }

    @Test func invalidURLIsError() async {
        let (store, _) = storeTempStore()
        store.settings = HomeDashSettings(kioskURL: "not a kiosk url")
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solarStatus == .error(message: HomeDashError.invalidKioskURL.localizedDescription))
    }

    @Test func rangeSourceGetsIncludeTomorrowFlag() async {
        for flag in [true, false] {
            let (store, _) = storeTempStore()
            configured(store, tomorrow: flag)
            let fake = FakeRange(result: .success(storeSamplePrices()))
            let data = await makeLoader(store: store, solar: FakeSolar(result: .success(storeSampleSolar())), prices: fake).load()
            #expect(fake.log.flags == [flag])
            #expect(data.priceStatus == .fresh)
        }
    }

    @Test func plainSourceUsesFetchDayNow() async {
        let (store, _) = storeTempStore()
        configured(store)
        let fake = FakePlain(result: .success(storeSamplePrices()))
        _ = await makeLoader(store: store, solar: nil, prices: fake).load()
        #expect(fake.log.days == [loaderNow])
    }

    @Test func yesterdaysSolarCacheIsStale() async {
        let (store, _) = storeTempStore()
        configured(store)
        let old = storeSampleSolar(fetchedAt: madrid(2026, 5, 31, 20, 0))
        store.saveSolar(old)
        let data = await makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solarStatus == .stale(since: old.fetchedAt))
        #expect(data.solar == old)
    }

    @Test func priceFailureCacheForTodayIsStale() async {
        let (store, _) = storeTempStore()
        configured(store)
        let cached = storeSamplePrices(fetchedAt: madrid(2026, 6, 1, 8, 0))
        store.savePrices(cached)
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .failure(.network("x")))).load()
        #expect(data.priceStatus == .stale(since: cached.fetchedAt))
        #expect(data.prices == cached)
    }

    @Test func priceFailureOldCacheKeepsSeriesWithError() async {
        let (store, _) = storeTempStore()
        configured(store)
        let cached = storeSamplePrices(day: madrid(2026, 5, 30, 0, 0))
        store.savePrices(cached)
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .failure(.unavailable))).load()
        #expect(data.priceStatus == .error(message: HomeDashError.unavailable.localizedDescription))
        #expect(data.prices == cached)
    }

    @Test func priceFailureNoCacheIsError() async {
        let (store, _) = storeTempStore()
        configured(store)
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .failure(.http(status: 502)))).load()
        #expect(data.priceStatus == .error(message: HomeDashError.http(status: 502).localizedDescription))
        #expect(data.prices == nil)
    }

    @Test func cachedReturnsStoreData() {
        let (store, _) = storeTempStore()
        configured(store)
        let loader = makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .failure(.unavailable)))
        let empty = loader.cached()
        #expect(empty.solarStatus == .error(message: HomeDashError.unavailable.localizedDescription))
        let solar = storeSampleSolar(), prices = storeSamplePrices()
        store.saveSolar(solar)
        store.savePrices(prices)
        let full = loader.cached()
        #expect(full.solarStatus == .stale(since: solar.fetchedAt))
        #expect(full.priceStatus == .stale(since: prices.fetchedAt))
        #expect(full.solar == solar && full.prices == prices)
        let none = makeLoader(store: store, solar: nil, prices: FakePlain(result: .failure(.unavailable))).cached()
        #expect(none.solarStatus == .error(message: HomeDashError.invalidKioskURL.localizedDescription))
    }
}
