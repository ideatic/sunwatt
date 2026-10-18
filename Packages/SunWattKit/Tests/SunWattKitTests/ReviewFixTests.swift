import Foundation
import Testing
@testable import SunWattKit

/// Fixes from the full code review (findings #2, #5, #7, #8, #10, #11, #12, #14).

/// Solar source that counts its fetches.
final class CallCountingSolar: SolarDataSource, @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    let result: Result<SolarSnapshot, SunWattError>
    init(_ result: Result<SolarSnapshot, SunWattError>) { self.result = result }
    var count: Int { lock.withLock { calls } }
    func fetch() async throws -> SolarSnapshot {
        lock.withLock { calls += 1 }
        return try result.get()
    }
}

/// In-memory keychain stand-in; `works: false` behaves like a build without keychain access.
final class MemorySecrets: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    let works: Bool
    init(works: Bool = true) { self.works = works }
    func secret(_ name: String) -> String? { lock.withLock { works ? values[name] : nil } }
    func setSecret(_ value: String, for name: String) -> Bool {
        guard works else { return false }
        lock.withLock { values[name] = value.isEmpty ? nil : value }
        return true
    }
    func removeAllSecrets() { lock.withLock { values.removeAll() } }
}

private let edgeSettings = SunWattSettings(showTomorrowPrices: false, solarProvider: .solarEdge, solarSiteID: "1234567", solarAPIKey: "ABCDEFGH12345678")

@Suite struct ReviewFixTests {
    private let now = madrid(2026, 6, 1, 12, 30)

    /// Prices covering `now`, fetched `age` seconds before it.
    private func prices(age: TimeInterval) -> PriceSeries {
        storeSamplePrices(fetchedAt: now.addingTimeInterval(-age))
    }

    // #2: SolarEdge solar younger than its fetch interval counts for the shared cache window.
    @Test func sharedWindowAcceptsSolarEdgeReuse() async {
        let (store, _) = storeTempStore()
        store.settings = edgeSettings
        let id = SolarAccount(settings: edgeSettings)!.cacheID
        store.saveSolar(storeSampleSolar(fetchedAt: now.addingTimeInterval(-8 * 60), endpointID: id))
        store.savePrices(prices(age: 30))
        let solar = CallCountingSolar(.failure(.unavailable))
        let fakePrices = FakePlain(result: .success(prices(age: 0)))
        let data = await SunWattLoader(store: store, makeSolar: { _ in solar }, prices: fakePrices, now: { now })
            .load(maxCacheAge: SunWattLoader.sharedCacheWindow)
        #expect(data.solarStatus == .fresh && data.priceStatus == .fresh)
        #expect(solar.count == 0)
        #expect(fakePrices.log.days.isEmpty)
    }

    // #10: a SolarEdge failure is reused for the fetch interval instead of asking again.
    @Test func solarEdgeFailureIsNotRetriedAtOnce() async {
        let (store, _) = storeTempStore()
        store.settings = edgeSettings
        let solar = CallCountingSolar(.failure(.http(status: 429)))
        let loader = { (date: Date) in
            SunWattLoader(store: store, makeSolar: { _ in solar }, prices: FakePlain(result: .success(storeSamplePrices())), now: { date })
        }
        let first = await loader(now).load()
        #expect(solar.count == 1)
        let second = await loader(now.addingTimeInterval(5 * 60)).load()
        #expect(solar.count == 1)
        #expect(second.solarStatus == first.solarStatus)
        #expect(second.solarStatus == .error(message: SunWattError.http(status: 429).localizedDescription))
        _ = await loader(now.addingTimeInterval(21 * 60)).load()
        #expect(solar.count == 2)
    }

    @Test func otherProvidersRetryEveryTime() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false)
        let solar = CallCountingSolar(.failure(.http(status: 500)))
        for minutes in [0.0, 1, 2] {
            _ = await SunWattLoader(store: store, makeSolar: { _ in solar }, prices: FakePlain(result: .success(storeSamplePrices())), now: { now.addingTimeInterval(minutes * 60) }).load()
        }
        #expect(solar.count == 3)
    }

    // #5: a cheaper next day does not push the rest of today out of today's ranking.
    @Test func cheapestUpcomingStopsAtTheDayEnd() {
        let day = madrid(2026, 6, 1)
        let points = (0..<48).map { i in
            PricePoint(start: day.addingTimeInterval(Double(i) * 3600), duration: 3600, eurPerMWh: i < 24 ? 100 + Double(i) : 10 + Double(i))
        }
        let series = PriceSeries(points: points, granularity: .hourly, fetchedAt: day, sourceUpdatedAt: day)
        let from = madrid(2026, 6, 1, 15)
        #expect(series.cheapestUpcoming(from: from, count: 3).allSatisfy { $0.start >= madrid(2026, 6, 2) })
        let today = series.cheapestUpcoming(from: from, count: 3, until: madrid(2026, 6, 2))
        #expect(today.map(\.start) == [madrid(2026, 6, 1, 15), madrid(2026, 6, 1, 16), madrid(2026, 6, 1, 17)])
    }

    // #14: Siri says which hours are tomorrow.
    @Test func siriMarksTomorrowsHours() throws {
        let day = madrid(2026, 6, 1)
        let points = (0..<48).map { i in
            PricePoint(start: day.addingTimeInterval(Double(i) * 3600), duration: 3600, eurPerMWh: i < 24 ? 100 + Double(i) : 10 + Double(i))
        }
        let series = PriceSeries(points: points, granularity: .hourly, fetchedAt: day, sourceUpdatedAt: day)
        let data = SunWattData(solar: nil, prices: series, solarStatus: .notConfigured, priceStatus: .fresh)
        let en = try #require(IntentStrings.language("en"))
        let answer = CheapestHoursAnswer.make(from: data, now: madrid(2026, 6, 1, 20), strings: en)
        #expect(answer.dialog.contains("tomorrow"))
        let es = try #require(IntentStrings.language("es"))
        #expect(!CheapestHoursAnswer.make(from: data, now: madrid(2026, 6, 1, 20), strings: es).dialog.contains("tomorrow"))
    }

    // #8: all-negative prices keep 0 inside the chart.
    @Test func negativePricesKeepZeroInTheDomain() {
        let points = (0..<3).map { PricePoint(start: madrid(2026, 6, 1, $0), duration: 3600, eurPerMWh: -50 + Double($0) * 10) }
        let domain = ChartLayout.priceYDomain(points: points)
        #expect(domain.contains(0))
        #expect(domain.lowerBound <= -0.05)
    }

    // #11: last month's and last year's totals are not shown as this month's / year's.
    @Test func monthAndYearTotalsFollowTheCalendar() {
        var snapshot = storeSampleSolar(fetchedAt: madrid(2026, 10, 31, 20))
        snapshot.dataAsOf = madrid(2026, 10, 31, 19)
        snapshot.monthEnergyKWh = 450
        snapshot.yearEnergyKWh = 5000
        let data = SunWattData(solar: snapshot, prices: nil, solarStatus: .stale(since: snapshot.fetchedAt), priceStatus: .fresh)
        let nextDay = SunWattEntry(date: madrid(2026, 11, 1, 0, 20), data: data).solarForDisplay
        #expect(nextDay?.monthEnergyKWh == nil)
        #expect(nextDay?.yearEnergyKWh == 5000)
        snapshot.fetchedAt = madrid(2026, 12, 31, 20)
        snapshot.dataAsOf = madrid(2026, 12, 31, 19)
        let newYear = SunWattEntry(date: madrid(2027, 1, 1, 0, 20), data: SunWattData(solar: snapshot, prices: nil, solarStatus: .fresh, priceStatus: .fresh)).solarForDisplay
        #expect(newYear?.monthEnergyKWh == nil && newYear?.yearEnergyKWh == nil)
        // Same month, next day: the month total stays.
        snapshot.fetchedAt = madrid(2026, 10, 30, 20)
        snapshot.dataAsOf = madrid(2026, 10, 30, 19)
        let sameMonth = SunWattEntry(date: madrid(2026, 10, 31, 0, 20), data: SunWattData(solar: snapshot, prices: nil, solarStatus: .fresh, priceStatus: .fresh)).solarForDisplay
        #expect(sameMonth?.monthEnergyKWh == 450)
    }

    // #12: a stored time far in the future (clock moved back) no longer blocks writes; a near one still does.
    @Test func futureStampsFromAClockChangeAreReplaced() {
        let (store, _) = storeTempStore()
        // Whole seconds: the store keeps ISO 8601 dates without fractions.
        let real = Date(timeIntervalSince1970: Date().timeIntervalSince1970.rounded(.down))
        store.saveSolar(storeSampleSolar(fetchedAt: real.addingTimeInterval(2 * 86_400)))
        store.saveSolar(storeSampleSolar(fetchedAt: real))
        #expect(store.loadSolar()?.fetchedAt == real)
        store.saveSolar(storeSampleSolar(fetchedAt: real.addingTimeInterval(60)))
        store.saveSolar(storeSampleSolar(fetchedAt: real))
        #expect(store.loadSolar()?.fetchedAt == real.addingTimeInterval(60))
    }

    // #7: secrets live in the secret store, not in the settings plist.
    @Test func secretsStayOutOfThePlist() throws {
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sunwatt-test-\(UUID())")
        let secrets = MemorySecrets()
        let store = AppGroupStore(defaults: defaults, directory: dir, secrets: secrets)
        var settings = edgeSettings
        settings.kioskURL = testKioskURL
        store.settings = settings
        let raw = try #require(defaults.data(forKey: "sunwatt.settings"))
        let text = String(decoding: raw, as: UTF8.self)
        #expect(!text.contains("TESTKK") && !text.contains("ABCDEFGH12345678"))
        #expect(store.settings == settings)
        // Cleared values are removed from the secret store too.
        settings.kioskURL = ""
        store.settings = settings
        #expect(secrets.secret("kioskURL") == nil)
        #expect(store.settings.kioskURL.isEmpty)
    }

    @Test func withoutKeychainSecretsStayInThePlist() {
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sunwatt-test-\(UUID())")
        let store = AppGroupStore(defaults: defaults, directory: dir, secrets: MemorySecrets(works: false))
        store.settings = edgeSettings
        #expect(store.settings == edgeSettings)
    }

    @Test func plistSecretsAreReadUntilMoved() throws {
        // An older install: the link is in the plist only. It is still read, and moved on the next save.
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sunwatt-test-\(UUID())")
        defaults.set(try JSONEncoder().encode(SunWattSettings(kioskURL: testKioskURL)), forKey: "sunwatt.settings")
        let secrets = MemorySecrets()
        let store = AppGroupStore(defaults: defaults, directory: dir, secrets: secrets)
        #expect(store.settings.kioskURL == testKioskURL)
        store.settings = store.settings
        #expect(secrets.secret("kioskURL") == testKioskURL)
        #expect(!String(decoding: defaults.data(forKey: "sunwatt.settings")!, as: UTF8.self).contains("TESTKK"))
    }
}
