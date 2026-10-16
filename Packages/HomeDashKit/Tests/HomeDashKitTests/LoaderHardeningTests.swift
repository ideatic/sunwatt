import Foundation
import Testing
@testable import HomeDashKit

/// Never answers and ignores cancellation (worst case for the deadline).
private struct HangingPrices: PriceDataSource {
    func fetch(day: Date) async throws -> PriceSeries {
        await withCheckedContinuation { (_: CheckedContinuation<Void, Never>) in }
        throw CancellationError()
    }
    func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries { try await fetch(day: Date()) }
}

private struct HangingSolar: SolarDataSource {
    func fetch() async throws -> SolarSnapshot {
        try await Task.sleep(for: .seconds(60))
        throw CancellationError()
    }
}

private func series(days: Int, from start: Date, region: PriceRegion = .spain, fetchedAt: Date = madrid(2026, 6, 1, 8)) -> PriceSeries {
    let points = (0..<(24 * days)).map { PricePoint(start: start.addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: 100 + Double($0)) }
    return PriceSeries(points: points, granularity: .hourly, fetchedAt: fetchedAt, region: region)
}

@Suite struct LoaderDeadlineTests {
    @Test func hangingSourcesReturnWithinTheDeadline() async {
        let (store, _) = storeTempStore()
        configured(store)
        let loader = makeLoader(store: store, solar: HangingSolar(), prices: HangingPrices())
        let started = Date()
        let data = await loader.load(deadline: 0.3)
        let elapsed = Date().timeIntervalSince(started)
        #expect(elapsed < 3)
        #expect(elapsed >= 0.25)
        guard case .error = data.solarStatus else { Issue.record("solar: \(data.solarStatus)"); return }
        guard case .error = data.priceStatus else { Issue.record("prices: \(data.priceStatus)"); return }
    }

    @Test func timeoutFallsBackToStaleCache() async {
        let (store, _) = storeTempStore()
        configured(store)
        let solar = storeSampleSolar(), prices = storeSamplePrices()
        store.saveSolar(solar)
        store.savePrices(prices)
        let loader = makeLoader(store: store, solar: HangingSolar(), prices: HangingPrices())
        let data = await loader.load(deadline: 0.2)
        #expect(data.solarStatus == .stale(since: solar.fetchedAt))
        #expect(data.priceStatus == .stale(since: prices.fetchedAt))
        #expect(data.solar == solar && data.prices == prices)
    }

    @Test func oneSlowSourceDoesNotBlockTheOther() async {
        let (store, _) = storeTempStore()
        configured(store)
        let good = storeSamplePrices()
        let data = await makeLoader(store: store, solar: HangingSolar(), prices: FakePlain(result: .success(good))).load(deadline: 0.2)
        #expect(data.priceStatus == .fresh)
        #expect(data.prices == good)
        guard case .error = data.solarStatus else { Issue.record("solar: \(data.solarStatus)"); return }
    }

    @Test func cancellationReturnsCachedAndSavesNothing() async {
        let (store, _) = storeTempStore()
        configured(store)
        let loader = makeLoader(store: store, solar: FakeSolar(result: .success(storeSampleSolar())), prices: HangingPrices())
        let task = Task { await loader.load(deadline: 30) }
        try? await Task.sleep(for: .milliseconds(100))
        let started = Date()
        task.cancel()
        let data = await task.value
        #expect(Date().timeIntervalSince(started) < 3)
        #expect(data == loader.cached())
        #expect(store.loadSolar() == nil)
        #expect(store.loadPrices() == nil)
        #expect(data.priceStatus == .error(message: HomeDashError.unavailable.localizedDescription))
    }

    @Test func alreadyCancelledTaskReturnsCached() async {
        let (store, _) = storeTempStore()
        configured(store)
        let cachedPrices = storeSamplePrices()
        store.savePrices(cachedPrices)
        let loader = makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(storeSamplePrices(fetchedAt: madrid(2026, 6, 1, 12)))))
        let task = Task { () -> HomeDashData in
            while !Task.isCancelled { await Task.yield() }
            return await loader.load()
        }
        task.cancel()
        let data = await task.value
        #expect(data.prices == cachedPrices)
        #expect(store.loadPrices() == cachedPrices)
    }

    @Test func cancelledSourceErrorIsNotReportedAsStatus() async {
        let (store, _) = storeTempStore()
        configured(store)
        struct Cancelling: PriceDataSource {
            func fetch(day: Date) async throws -> PriceSeries { throw CancellationError() }
        }
        let cached = storeSamplePrices()
        store.savePrices(cached)
        let data = await makeLoader(store: store, solar: nil, prices: Cancelling()).load()
        #expect(data == makeLoader(store: store, solar: nil, prices: Cancelling()).cached())
    }
}

@Suite struct LoaderPriceCacheTests {
    @Test func transientTomorrowFailureKeepsCachedTomorrow() async {
        let (store, _) = storeTempStore()
        configured(store)
        let day = madrid(2026, 6, 1)
        let withTomorrow = series(days: 2, from: day)
        store.savePrices(withTomorrow)
        let todayOnly = series(days: 1, from: day, fetchedAt: madrid(2026, 6, 1, 12))
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(todayOnly))).load()
        #expect(data.priceStatus == .fresh)
        #expect(data.prices?.points.count == 48)
        #expect(data.prices?.fetchedAt == todayOnly.fetchedAt)
        #expect(data.prices?.points.first == todayOnly.points.first)
        #expect(store.loadPrices()?.points.count == 48)
        // The new today values win; tomorrow comes from the old cache.
        #expect(data.prices?.points[30] == withTomorrow.points[30])
    }

    @Test func pastCachedPointsAreNotKept() async {
        let (store, _) = storeTempStore()
        configured(store)
        // Cache holds 30 May + 31 May; the new series is 1 Jun. Nothing of the cache starts after it.
        store.savePrices(series(days: 2, from: madrid(2026, 5, 30)))
        let fresh = series(days: 1, from: madrid(2026, 6, 1), fetchedAt: madrid(2026, 6, 1, 12))
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(fresh))).load()
        #expect(data.prices == fresh)
    }

    @Test func tomorrowIsNotKeptWhenSettingIsOff() async {
        let (store, _) = storeTempStore()
        configured(store, tomorrow: false)
        store.savePrices(series(days: 2, from: madrid(2026, 6, 1)))
        let today = series(days: 1, from: madrid(2026, 6, 1), fetchedAt: madrid(2026, 6, 1, 12))
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(today))).load()
        #expect(data.prices == today)
    }

    @Test func cachedPricesOfAnotherRegionAreIgnored() async {
        let (store, _) = storeTempStore()
        store.settings = HomeDashSettings(kioskURL: "", showTomorrowPrices: true, priceRegion: .spain)
        let portugal = series(days: 1, from: madrid(2026, 6, 1), region: .portugal)
        store.savePrices(portugal)
        let loader = makeLoader(store: store, solar: nil, prices: FakePlain(result: .failure(.network("x"))))
        let data = await loader.load()
        #expect(data.prices == nil)
        #expect(data.priceStatus == .error(message: HomeDashError.network("x").localizedDescription))
        #expect(loader.cached().prices == nil)

        store.settings = HomeDashSettings(kioskURL: "", showTomorrowPrices: true, priceRegion: .portugal)
        #expect(loader.cached().prices == portugal)
        let stale = await loader.load()
        #expect(stale.prices == portugal)
    }

    @Test func staleCachedTomorrowOfAnotherRegionIsNotMergedIn() async {
        let (store, _) = storeTempStore()
        store.settings = HomeDashSettings(kioskURL: "", showTomorrowPrices: true, priceRegion: .spain)
        store.savePrices(series(days: 2, from: madrid(2026, 6, 1), region: .france))
        let today = series(days: 1, from: madrid(2026, 6, 1), fetchedAt: madrid(2026, 6, 1, 12))
        let data = await makeLoader(store: store, solar: nil, prices: FakePlain(result: .success(today))).load()
        #expect(data.prices == today)
    }

    @Test func priceSourceIsBuiltFromTheRegionSetting() async {
        final class Seen: @unchecked Sendable {
            let lock = NSLock(); var regions: [PriceRegion] = []
            func add(_ r: PriceRegion) { lock.withLock { regions.append(r) } }
        }
        let seen = Seen()
        let (store, _) = storeTempStore()
        store.settings = HomeDashSettings(kioskURL: "", showTomorrowPrices: true, priceRegion: .austria)
        let loader = HomeDashLoader(store: store, makeSolar: { _ in nil }, makePrices: { region in
            seen.add(region)
            return FakePlain(result: .success(series(days: 1, from: madrid(2026, 6, 1), region: region)))
        }, now: { loaderNow })
        let data = await loader.load()
        #expect(seen.regions.contains(.austria))
        #expect(data.prices?.region == .austria)
        #expect(store.loadPrices()?.region == .austria)
    }

    @Test func defaultPriceSourcesFollowTheRegion() {
        #expect(HomeDashLoader.defaultPrices(.spain) is PVPCClient)
        #expect(HomeDashLoader.defaultPrices(.netherlands) is EnergyChartsClient)
    }

    @Test(arguments: [madrid(2026, 3, 29, 12), madrid(2026, 10, 25, 12)])
    func dstDayCacheCountsAsToday(now: Date) async {
        let (store, _) = storeTempStore()
        configured(store)
        let day = Constants.madridCalendar.startOfDay(for: now)
        let end = Constants.madridCalendar.dateInterval(of: .day, for: now)!.end
        let hours = Int(end.timeIntervalSince(day) / 3600)
        #expect(hours == 23 || hours == 25)
        let points = (0..<hours).map { PricePoint(start: day.addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: 50) }
        let cached = PriceSeries(points: points, granularity: .hourly, fetchedAt: day.addingTimeInterval(3600))
        store.savePrices(cached)
        let loader = HomeDashLoader(store: store, makeSolar: { _ in nil }, prices: FakePlain(result: .failure(.unavailable)), now: { now })
        let data = await loader.load()
        #expect(data.priceStatus == .stale(since: cached.fetchedAt))

        // Every hour of the day is covered, and the first hour after it is not.
        for p in points {
            let entry = HomeDashEntry(date: p.start.addingTimeInterval(1800), data: HomeDashData(solar: nil, prices: cached, solarStatus: .notConfigured, priceStatus: .fresh))
            #expect(!entry.isPriceStale)
        }
        let after = HomeDashEntry(date: end.addingTimeInterval(60), data: HomeDashData(solar: nil, prices: cached, solarStatus: .notConfigured, priceStatus: .fresh))
        #expect(after.isPriceStale)
    }
}

@Suite struct EntryStalenessTests {
    @Test func timelineSpanningMidnightWithoutTomorrowBecomesStale() {
        let day = madrid(2026, 6, 1)
        let prices = series(days: 1, from: day)
        let data = HomeDashData(solar: nil, prices: prices, solarStatus: .notConfigured, priceStatus: .fresh)
        let now = madrid(2026, 6, 1, 22, 30)
        let entries = TimelineBuilder.entries(for: data, now: now, hours: 6)
        let midnight = madrid(2026, 6, 2)
        for e in entries {
            #expect(e.isPriceStale == (e.date >= midnight))
        }
        #expect(entries.contains { $0.date >= midnight })
        #expect(entries.contains { $0.date < midnight })
    }

    @Test func timelineWithTomorrowStaysFreshAcrossMidnight() {
        let prices = series(days: 2, from: madrid(2026, 6, 1))
        let data = HomeDashData(solar: nil, prices: prices, solarStatus: .notConfigured, priceStatus: .fresh)
        let entries = TimelineBuilder.entries(for: data, now: madrid(2026, 6, 1, 22, 30), hours: 6)
        #expect(entries.allSatisfy { !$0.isPriceStale })
    }

    @Test func noPricesIsNotMarkedStale() {
        let data = HomeDashData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .error(message: "x"))
        #expect(!HomeDashEntry(date: Date(), data: data).isPriceStale)
    }

    @Test func solarFreshNowButStaleLater() {
        let now = madrid(2026, 6, 1, 12, 30)
        let solar = storeSampleSolar(fetchedAt: now)
        let data = HomeDashData(solar: solar, prices: nil, solarStatus: .fresh, priceStatus: .notConfigured)
        let entries = TimelineBuilder.entries(for: data, now: now, hours: 3)
        #expect(!entries[0].isSolarStale)
        // 13:00 is 30 min old: still fresh. 14:00 is 90 min old: stale.
        #expect(entries[1].date == madrid(2026, 6, 1, 13) && !entries[1].isSolarStale)
        #expect(entries[2].date == madrid(2026, 6, 1, 14) && entries[2].isSolarStale)
        #expect(entries.last?.isSolarStale == true)
    }

    @Test func dstTimelinesKeepHourBoundaries() {
        for start in [madrid(2026, 3, 29, 0, 30), madrid(2026, 10, 25, 0, 30)] {
            let day = Constants.madridCalendar.startOfDay(for: start)
            let end = Constants.madridCalendar.dateInterval(of: .day, for: start)!.end
            let points = stride(from: day, to: end, by: 3600).map { PricePoint(start: $0, duration: 3600, eurPerMWh: 1) }
            let prices = PriceSeries(points: points, granularity: .hourly, fetchedAt: start)
            let data = HomeDashData(solar: nil, prices: prices, solarStatus: .notConfigured, priceStatus: .fresh)
            let entries = TimelineBuilder.entries(for: data, now: start, hours: points.count - 1)
            let inside = entries.filter { $0.date < end }
            #expect(inside.allSatisfy { !$0.isPriceStale })
            #expect(inside.count == points.count)
        }
    }
}
