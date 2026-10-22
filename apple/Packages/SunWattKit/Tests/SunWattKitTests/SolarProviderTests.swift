import Foundation
import Testing
@testable import SunWattKit

private let edgeKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
private let pvKey = "0123456789abcdef0123456789abcdef01234567"
private let providerNow = madrid(2026, 6, 1, 12, 35)

/// An error whose message contains the API key, to check that it is redacted.
private struct LeakyError: LocalizedError {
    var errorDescription: String? { "failed: https://monitoringapi.solaredge.com/site/1/overview?api_key=\(edgeKey)" }
}

private func sampleCredentials(_ id: String = "1234567", _ key: String = edgeKey) -> SolarAPICredentials {
    SolarAPICredentials(siteID: id, apiKey: key)!
}

// MARK: Accounts and settings

@Suite struct SolarAccountTests {
    @Test func credentialsAreTrimmedAndValidated() {
        let c = SolarAPICredentials(siteID: " 1234567\n", apiKey: "  \(edgeKey) ")
        #expect(c?.siteID == "1234567")
        #expect(c?.apiKey == edgeKey)
        #expect(SolarAPICredentials(siteID: "", apiKey: edgeKey) == nil)
        #expect(SolarAPICredentials(siteID: "12a4", apiKey: edgeKey) == nil)
        #expect(SolarAPICredentials(siteID: "1234567", apiKey: "short") == nil)
        #expect(SolarAPICredentials(siteID: "1234567", apiKey: "ABCD-EFGH-1234") == nil)
        #expect(SolarAPICredentials(siteID: "1234567", apiKey: "ABCDÉFGH1234") == nil)
        #expect(SolarAPICredentials(siteID: "١٢٣", apiKey: edgeKey) == nil)
    }

    @Test func descriptionsHideTheKey() {
        let c = sampleCredentials()
        for text in [c.description, c.debugDescription, String(reflecting: c), "\(c)"] {
            #expect(!text.contains(edgeKey))
        }
        var dumped = ""
        dump(c, to: &dumped)
        #expect(!dumped.contains(edgeKey))
    }

    @Test func accountFollowsTheSelectedProvider() {
        var settings = SunWattSettings(kioskURL: testKioskURL, solarSiteID: "1234567", solarAPIKey: edgeKey)
        #expect(SolarAccount(settings: settings)?.provider == .fusionSolar)
        settings.solarProvider = .solarEdge
        #expect(SolarAccount(settings: settings) == .solarEdge(sampleCredentials()))
        settings.solarProvider = .pvOutput
        #expect(SolarAccount(settings: settings) == .pvOutput(sampleCredentials()))
        settings.solarAPIKey = "bad key!"
        #expect(SolarAccount(settings: settings) == nil)
        settings.solarProvider = .fusionSolar
        settings.kioskURL = "nope"
        #expect(SolarAccount(settings: settings) == nil)
    }

    @Test func cacheIDsAreStableDistinctAndSecretFree() {
        let kiosk = SolarAccount.fusionSolar(KioskEndpoint(parsing: testKioskURL)!)
        #expect(kiosk.cacheID == testKioskID)
        let edge = SolarAccount.solarEdge(sampleCredentials())
        let pv = SolarAccount.pvOutput(sampleCredentials())
        #expect(edge.cacheID == SolarAccount.solarEdge(sampleCredentials()).cacheID)
        #expect(edge.cacheID != pv.cacheID)
        #expect(edge.cacheID != SolarAccount.solarEdge(sampleCredentials("7654321")).cacheID)
        #expect(edge.cacheID.count == 64)
        #expect(!edge.cacheID.contains(edgeKey) && !edge.cacheID.contains("1234567"))
    }

    @Test func oldSettingsDecodeAsFusionSolar() throws {
        let json = Data(#"{"kioskURL":"x","showTomorrowPrices":false,"priceRegion":"portugal","onboardingCompleted":true}"#.utf8)
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: json)
        #expect(settings.solarProvider == .fusionSolar)
        #expect(settings.solarSiteID.isEmpty && settings.solarAPIKey.isEmpty)
        #expect(settings.kioskURL == "x" && settings.onboardingCompleted)
    }

    @Test func unknownProviderFallsBackToFusionSolar() throws {
        let json = Data(#"{"kioskURL":"x","solarProvider":"enphase","solarSiteID":"1"}"#.utf8)
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: json)
        #expect(settings.solarProvider == .fusionSolar)
        #expect(settings.solarSiteID == "1")
    }

    @Test func settingsRoundTrip() throws {
        let settings = SunWattSettings(priceRegion: .germany, solarProvider: .pvOutput, solarSiteID: "42", solarAPIKey: pvKey)
        let decoded = try JSONDecoder().decode(SunWattSettings.self, from: JSONEncoder().encode(settings))
        #expect(decoded == settings)
    }

    @Test func inputStateFollowsTheProvider() {
        var settings = SunWattSettings(kioskURL: testKioskURL)
        #expect(settings.hasSolarInput)
        #expect(settings.invalidSolarInputError == .invalidKioskURL)
        settings.solarProvider = .solarEdge
        #expect(!settings.hasSolarInput)
        settings.solarSiteID = "  "
        #expect(!settings.hasSolarInput)
        settings.solarAPIKey = "x"
        #expect(settings.hasSolarInput)
        #expect(settings.invalidSolarInputError == .invalidSolarCredentials)
    }
}

// MARK: SolarEdge

@Suite struct SolarEdgeClientTests {
    private func parsed() throws -> SolarSnapshot {
        try SolarEdgeClient.parse(
            overview: Fixture.data("solaredge-overview-synthetic.json"),
            power: Fixture.data("solaredge-power-synthetic.json"),
            fetchedAt: providerNow, calendar: Constants.madridCalendar)
    }

    @Test func parsesOverviewInKilo() throws {
        let s = try parsed()
        #expect(abs((s.currentPowerKW ?? 0) - 4.2105) < 1e-9)
        #expect(abs((s.dailyEnergyKWh ?? 0) - 27.35) < 1e-9)
        #expect(abs((s.yearEnergyKWh ?? 0) - 3120.45) < 1e-9)
        #expect(abs((s.cumulativeEnergyKWh ?? 0) - 48210.345) < 1e-6)
        #expect(s.stationName == nil)
        #expect(s.fetchedAt == providerNow)
        #expect(s.dataAsOf == Constants.madridCalendar.date(from: DateComponents(year: 2026, month: 6, day: 1, hour: 12, minute: 28, second: 41)))
    }

    @Test func parsesQuarterHourCurve() throws {
        let s = try parsed()
        #expect(s.curve.count == 96)
        #expect(s.curve.first?.time == madrid(2026, 6, 1, 0, 0))
        #expect(s.curve.first?.kW == nil)
        #expect(s.latestPoint?.time == madrid(2026, 6, 1, 12, 30))
        #expect(abs((s.latestPoint?.kW ?? 0) - 4.830679) < 1e-9)
        #expect(s.curve.filter { $0.kW != nil }.count == 27)
        #expect(zip(s.curve, s.curve.dropFirst()).allSatisfy { $0.time < $1.time })
    }

    @Test func futurePointsLoseTheirValue() throws {
        let early = madrid(2026, 6, 1, 9, 0)
        let s = try SolarEdgeClient.parse(
            overview: Fixture.data("solaredge-overview-synthetic.json"),
            power: Fixture.data("solaredge-power-synthetic.json"),
            fetchedAt: early, calendar: Constants.madridCalendar)
        #expect(s.latestPoint?.time == madrid(2026, 6, 1, 9, 0))
        // The last update time is never later than the fetch.
        #expect(s.dataAsOf == early)
    }

    @Test func lastUpdateFromYesterdayIsIgnoredAfterMidnight() throws {
        // Overview last updated 2026-06-01 12:28; fetched at 00:30 the next day, before sunrise.
        let night = madrid(2026, 6, 2, 0, 30)
        let empty = Data(#"{"power":{"timeUnit":"QUARTER_OF_AN_HOUR","unit":"W","values":[{"date":"2026-06-02 00:00:00","value":null}]}}"#.utf8)
        let s = try SolarEdgeClient.parse(
            overview: Fixture.data("solaredge-overview-synthetic.json"), power: empty,
            fetchedAt: night, calendar: Constants.madridCalendar)
        #expect(s.dataAsOf == nil)
        let entry = SunWattEntry(date: night, data: SunWattData(solar: s, prices: nil, solarStatus: .fresh, priceStatus: .fresh))
        #expect(!entry.isSolarFromEarlierDay)
        #expect(!entry.isSolarStale)
    }

    @Test func badBodiesThrow() {
        #expect(throws: SunWattError.self) {
            try SolarEdgeClient.parse(overview: Data("{}".utf8), power: Data("{}".utf8), fetchedAt: providerNow, calendar: Constants.madridCalendar)
        }
        #expect(throws: SunWattError.self) {
            try SolarEdgeClient.parse(overview: Fixture.data("solaredge-overview-synthetic.json"), power: Data("[]".utf8), fetchedAt: providerNow, calendar: Constants.madridCalendar)
        }
    }

    @Test func fetchRequestsOverviewAndTodaysPower() async throws {
        let http = StubHTTPClient()
        http.on("/overview", .ok(try Fixture.data("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", .ok(try Fixture.data("solaredge-powerdetails-nometer-synthetic.json")))
        let client = SolarEdgeClient(credentials: sampleCredentials(), http: http, now: { providerNow }, calendar: Constants.madridCalendar)
        let s = try await client.fetch()
        #expect(s.curve.count == 96)
        let urls = http.requestedURLs.map(\.absoluteString)
        #expect(urls.count == 2)
        #expect(urls.allSatisfy { $0.hasPrefix("https://monitoringapi.solaredge.com/site/1234567/") && $0.contains("api_key=\(edgeKey)") })
        let power = try #require(http.requestedURLs.first { $0.path.hasSuffix("/powerDetails") })
        let items = URLComponents(url: power, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.first { $0.name == "startTime" }?.value == "2026-06-01 00:00:00")
        #expect(items.first { $0.name == "endTime" }?.value == "2026-06-01 23:59:59")
    }

    @Test func rejectedKeyIsAClearError() async {
        for status in [401, 403] {
            let http = StubHTTPClient(.ok(Data("Invalid token".utf8), status: status))
            let client = SolarEdgeClient(credentials: sampleCredentials(), http: http, now: { providerNow })
            await #expect(throws: SunWattError.solarCredentialsRejected) { try await client.fetch() }
        }
        let http = StubHTTPClient(.ok(Data(), status: 429))
        let client = SolarEdgeClient(credentials: sampleCredentials(), http: http, now: { providerNow })
        await #expect(throws: SunWattError.rateLimited) { try await client.fetch() }
    }

    @Test func networkErrorsNeverContainTheKey() async {
        let http = StubHTTPClient(.failure(LeakyError()))
        let client = SolarEdgeClient(credentials: sampleCredentials(), http: http, now: { providerNow })
        do {
            _ = try await client.fetch()
            Issue.record("expected failure")
        } catch {
            #expect(!error.localizedDescription.contains(edgeKey))
            #expect(!String(describing: error).contains(edgeKey))
        }
    }
}

// MARK: PVOutput

@Suite struct PVOutputClientTests {
    private func parsed(at date: Date = providerNow) throws -> SolarSnapshot {
        try PVOutputClient.parse(Fixture.data("pvoutput-status-synthetic.txt"), fetchedAt: date, calendar: Constants.madridCalendar)
    }

    @Test func parsesStatusHistory() throws {
        let s = try parsed()
        #expect(s.curve.count == 67)
        #expect(s.curve.first?.time == madrid(2026, 6, 1, 7, 0))
        #expect(abs((s.currentPowerKW ?? 0) - 4.831) < 1e-9)
        #expect(abs((s.dailyEnergyKWh ?? 0) - 15.318) < 1e-9)
        #expect(s.dataAsOf == madrid(2026, 6, 1, 12, 30))
        #expect(s.stationName == nil)
    }

    @Test func missingPowerUsesTheAverage() throws {
        let s = try parsed()
        // Record 20 (08:40) has "NaN" instantaneous power and a numeric average.
        #expect(s.curve[20].time == madrid(2026, 6, 1, 8, 40))
        #expect(s.curve[20].kW != nil)
    }

    @Test func oldLatestStatusMeansNoPowerNow() throws {
        let s = try parsed(at: madrid(2026, 6, 1, 14, 0))
        #expect(s.currentPowerKW == 0)
        #expect(abs((s.dailyEnergyKWh ?? 0) - 15.318) < 1e-9)
    }

    @Test func futureStatusesAreIgnored() throws {
        let s = try parsed(at: madrid(2026, 6, 1, 10, 0))
        #expect(s.latestPoint?.time == madrid(2026, 6, 1, 10, 5))
        #expect(s.curve.last?.kW == nil)
        #expect((s.dailyEnergyKWh ?? 99) < 15.318)
    }

    @Test func garbageThrows() {
        #expect(throws: SunWattError.self) {
            try PVOutputClient.parse(Data("hello".utf8), fetchedAt: providerNow, calendar: Constants.madridCalendar)
        }
        #expect(throws: SunWattError.self) {
            try PVOutputClient.parse(Data("20261301,10:00,1,1,1;20260601,25:00,1,1,1".utf8), fetchedAt: providerNow, calendar: Constants.madridCalendar)
        }
    }

    @Test func fetchSendsKeyInHeadersOnly() async throws {
        let http = StubHTTPClient(.ok(try Fixture.data("pvoutput-status-synthetic.txt")))
        let client = PVOutputClient(credentials: sampleCredentials("42", pvKey), http: http, now: { providerNow }, calendar: Constants.madridCalendar)
        let s = try await client.fetch()
        #expect(s.curve.count == 67)
        let request = try #require(http.requests.first)
        #expect(request.url?.host == "pvoutput.org")
        #expect(request.url?.path == "/service/r2/getstatus.jsp")
        #expect(!(request.url?.absoluteString.contains(pvKey) ?? true))
        #expect(request.value(forHTTPHeaderField: "X-Pvoutput-Apikey") == pvKey)
        #expect(request.value(forHTTPHeaderField: "X-Pvoutput-SystemId") == "42")
    }

    @Test func noStatusYetIsADayWithoutProduction() async throws {
        let http = StubHTTPClient(.ok(try Fixture.data("pvoutput-no-status.txt"), status: 400))
        let client = PVOutputClient(credentials: sampleCredentials("42", pvKey), http: http, now: { providerNow })
        let s = try await client.fetch()
        #expect(s.currentPowerKW == 0 && s.dailyEnergyKWh == 0)
        #expect(s.curve.isEmpty && s.dataAsOf == nil)
        #expect(s.isIdle)
    }

    @Test func errorsAreMapped() async {
        let cases: [(Int, String, SunWattError)] = [
            (401, "Unauthorized 401: Invalid API Key", .solarCredentialsRejected),
            (403, "Forbidden 403: Read access denied", .solarCredentialsRejected),
            (403, "Forbidden 403: Exceeded 60 requests per hour", .rateLimited),
            (400, "Bad request 400: Invalid system id", .http(status: 400)),
        ]
        for (status, body, expected) in cases {
            let http = StubHTTPClient(.ok(Data(body.utf8), status: status))
            let client = PVOutputClient(credentials: sampleCredentials("42", pvKey), http: http, now: { providerNow })
            await #expect(throws: expected) { try await client.fetch() }
        }
    }
}

// MARK: Loader and app

@Suite struct SolarProviderLoaderTests {
    @Test func invalidCredentialsAreAnErrorWithoutNetwork() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(solarProvider: .solarEdge, solarSiteID: "abc", solarAPIKey: edgeKey)
        let loader = SunWattLoader(store: store, prices: FakePlain(result: .success(storeSamplePrices())), now: { loaderNow })
        let data = await loader.load()
        #expect(data.solarStatus == .error(message: SunWattError.invalidSolarCredentials.localizedDescription))
    }

    @Test func emptyCredentialsAreNotConfigured() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, solarProvider: .pvOutput)
        let loader = SunWattLoader(store: store, prices: FakePlain(result: .success(storeSamplePrices())), now: { loaderNow })
        #expect(await loader.load().solarStatus == .notConfigured)
    }

    @Test func snapshotIsTiedToTheAccount() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(solarProvider: .solarEdge, solarSiteID: "1234567", solarAPIKey: edgeKey)
        let solar = storeSampleSolar(endpointID: nil)
        let prices = FakePlain(result: .success(storeSamplePrices()))
        let data = await makeLoader(store: store, solar: FakeSolar(result: .success(solar)), prices: prices).load()
        #expect(data.solarStatus == .fresh)
        #expect(store.loadSolar()?.endpointID == SolarAccount.solarEdge(sampleCredentials()).cacheID)
        #expect(makeLoader(store: store, solar: FakeSolar(result: .success(solar)), prices: prices).cached().solar != nil)

        // Another provider with the same ID and key: the cached snapshot does not apply.
        store.settings.solarProvider = .pvOutput
        let failing = makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: prices)
        let after = await failing.load()
        #expect(after.solar == nil)
        #expect(after.solarStatus == .error(message: SunWattError.unavailable.localizedDescription))
    }

    @Test func solarEdgeReusesDataYoungerThanItsFetchInterval() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(solarProvider: .solarEdge, solarSiteID: "1234567", solarAPIKey: edgeKey)
        let id = SolarAccount.solarEdge(sampleCredentials()).cacheID
        let prices = FakePlain(result: .success(storeSamplePrices()))
        let newer = storeSampleSolar(fetchedAt: loaderNow, endpointID: nil)

        // 10 minutes old: reused without fetching, even with no cache window (explicit refresh).
        let recent = storeSampleSolar(fetchedAt: loaderNow.addingTimeInterval(-10 * 60), endpointID: id)
        store.saveSolar(recent)
        let reused = await makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: prices).load()
        #expect(reused.solarStatus == .fresh)
        #expect(reused.solar?.fetchedAt == recent.fetchedAt)

        // 25 minutes old: fetched again. A fresh store: `saveSolar` never replaces a newer snapshot with an older one.
        let (oldStore, _) = storeTempStore()
        oldStore.settings = store.settings
        oldStore.saveSolar(storeSampleSolar(fetchedAt: loaderNow.addingTimeInterval(-25 * 60), endpointID: id))
        let fetched = await makeLoader(store: oldStore, solar: FakeSolar(result: .success(newer)), prices: prices).load()
        #expect(fetched.solar?.fetchedAt == loaderNow)

        // PVOutput has no limit.
        #expect(SolarProvider.pvOutput.minimumFetchInterval == 0)
        #expect(SolarProvider.fusionSolar.minimumFetchInterval == 0)
    }

    @Test func defaultSolarBuildsTheProviderClient() {
        var settings = SunWattSettings(kioskURL: testKioskURL)
        #expect(SunWattLoader.defaultSolar(settings) is FusionSolarKioskClient)
        settings.solarProvider = .solarEdge
        #expect(SunWattLoader.defaultSolar(settings) == nil)
        settings.solarSiteID = "1234567"
        settings.solarAPIKey = edgeKey
        #expect(SunWattLoader.defaultSolar(settings) is SolarEdgeClient)
        settings.solarProvider = .pvOutput
        #expect(SunWattLoader.defaultSolar(settings) is PVOutputClient)
    }

    @MainActor @Test func demoTestConnectionChecksTheFields() async {
        let model = AppModel(launch: AppLaunchOptions(demo: true))
        if case .failure(let error) = await model.testSolarAPI(provider: .solarEdge, siteID: "x", apiKey: edgeKey) {
            #expect(error as? SunWattError == .invalidSolarCredentials)
        } else { Issue.record("expected failure") }
        if case .failure = await model.testSolarAPI(provider: .fusionSolar, siteID: "1", apiKey: edgeKey) {} else {
            Issue.record("FusionSolar has no API key")
        }
        if case .success(let solar) = await model.testSolarAPI(provider: .pvOutput, siteID: "42", apiKey: pvKey) {
            #expect(solar.currentPowerKW != nil)
        } else { Issue.record("expected success in demo") }
    }
}

// MARK: Home consumption (only with a meter at home)

@Suite struct HomeEnergyTests {
    private let calendar = Constants.madridCalendar
    private let now = madrid(2026, 6, 1, 12, 35)

    private func edge(details: String) throws -> SolarSnapshot {
        try SolarEdgeClient.parse(
            overview: Fixture.data("solaredge-overview-synthetic.json"), power: Fixture.data(details),
            fetchedAt: now, calendar: calendar)
    }

    @Test func solarEdgeMeterGivesConsumptionAndGrid() throws {
        let s = try edge(details: "solaredge-powerdetails-meter-synthetic.json")
        // Production still comes from the details.
        #expect(s.curve.count == 96)
        #expect(s.latestPoint?.time == madrid(2026, 6, 1, 12, 30))
        let home = try #require(s.home)
        #expect(home.consumptionKW == 0.6)
        #expect(abs((home.gridKW ?? 0) - -4.230679) < 1e-9)
        #expect(abs((home.dailyConsumptionKWh ?? 0) - 8.55) < 1e-9)
        #expect(abs((home.dailyImportKWh ?? 0) - 5.278748) < 1e-6)
        #expect(abs((home.dailyExportKWh ?? 0) - 12.445927) < 1e-6)
        #expect(abs((home.selfSufficiency ?? 0) - (1 - 5.278748 / 8.55)) < 1e-6)
        #expect(home.curve.count == 96)
    }

    @Test func solarEdgeWithoutMeterShowsNoConsumption() throws {
        let s = try edge(details: "solaredge-powerdetails-nometer-synthetic.json")
        #expect(s.home == nil)
        #expect(s.curve.count == 96)
        // The plain `power` body (older format) has no meters either.
        #expect(try edge(details: "solaredge-power-synthetic.json").home == nil)
    }

    @Test func solarEdgeFetchUsesPowerDetails() async throws {
        let http = StubHTTPClient()
        http.on("/overview", .ok(try Fixture.data("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", .ok(try Fixture.data("solaredge-powerdetails-meter-synthetic.json")))
        let client = SolarEdgeClient(credentials: SolarAPICredentials(siteID: "1", apiKey: "ABCDEFGH12345678")!, http: http, now: { madrid(2026, 6, 1, 12, 35) }, calendar: Constants.madridCalendar)
        let s = try await client.fetch()
        #expect(s.home != nil)
        #expect(http.requestedURLs.count == 2)
        #expect(http.requestedURLs.contains { $0.path.hasSuffix("/powerDetails") })
    }

    @Test func solarEdgeFallsBackToPowerWithoutProductionMeter() async throws {
        let http = StubHTTPClient()
        http.on("/overview", .ok(try Fixture.data("solaredge-overview-synthetic.json")))
        http.on("/powerDetails", .ok(Data(#"{"powerDetails":{"timeUnit":"QUARTER_OF_AN_HOUR","unit":"W","meters":[]}}"#.utf8)))
        http.on("/power", .ok(try Fixture.data("solaredge-power-synthetic.json")))
        let client = SolarEdgeClient(credentials: SolarAPICredentials(siteID: "1", apiKey: "ABCDEFGH12345678")!, http: http, now: { madrid(2026, 6, 1, 12, 35) }, calendar: Constants.madridCalendar)
        let s = try await client.fetch()
        #expect(s.curve.count == 96)
        #expect(s.home == nil)
        #expect(http.requestedURLs.count == 3)
    }

    @Test func pvOutputConsumptionColumns() throws {
        let s = try PVOutputClient.parse(Fixture.data("pvoutput-status-consumption-synthetic.txt"), fetchedAt: now, calendar: calendar)
        let home = try #require(s.home)
        #expect(home.consumptionKW == 0.7)
        #expect(abs((home.gridKW ?? 0) - -4.131) < 1e-9)
        #expect(abs((home.dailyConsumptionKWh ?? 0) - 3.908) < 1e-9)
        #expect(abs((home.dailyImportKWh ?? 0) - 0.216875) < 1e-6)
        #expect(abs((home.dailyExportKWh ?? 0) - 11.483167) < 1e-6)
        #expect(home.curve.count == 67)
    }

    @Test func pvOutputWithoutConsumptionShowsNone() throws {
        let s = try PVOutputClient.parse(Fixture.data("pvoutput-status-synthetic.txt"), fetchedAt: now, calendar: calendar)
        #expect(s.home == nil)
    }

    @Test func oldConsumptionIsNotNow() throws {
        let s = try PVOutputClient.parse(Fixture.data("pvoutput-status-consumption-synthetic.txt"), fetchedAt: madrid(2026, 6, 1, 14, 0), calendar: calendar)
        #expect(s.home?.consumptionKW == nil)
        #expect(s.home?.gridKW == nil)
        #expect(s.home?.dailyConsumptionKWh != nil)
    }

    @Test func kioskHasNoConsumption() throws {
        let s = try FusionSolarKioskClient.parse(Fixture.data("kiosk-night.json"), fetchedAt: now, day: now)
        #expect(s.home == nil)
    }

    @Test func snapshotsWithoutHomeStillDecode() throws {
        var snapshot = storeSampleSolar()
        snapshot.home = nil
        var json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot)) as! [String: Any]
        json.removeValue(forKey: "home")
        let decoded = try JSONDecoder().decode(SolarSnapshot.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(decoded.home == nil)
        #expect(decoded.curve == snapshot.curve)
    }

    @Test func homeIsClearedAfterMidnightLikeTheCurve() throws {
        var snapshot = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 20, 0))
        snapshot.home = HomeEnergy(consumptionKW: 0.5, gridKW: 0.5, dailyConsumptionKWh: 9)
        let entry = SunWattEntry(date: madrid(2026, 6, 2, 0, 30), data: SunWattData(solar: snapshot, prices: nil, solarStatus: .fresh, priceStatus: .fresh))
        #expect(entry.solarForDisplay?.home == nil)
    }

    @Test func selfSufficiencyNeedsImportAndUse() {
        #expect(HomeEnergy(consumptionKW: nil, gridKW: nil, dailyConsumptionKWh: 10, dailyImportKWh: 4).selfSufficiency == 0.6)
        #expect(HomeEnergy(consumptionKW: nil, gridKW: nil, dailyConsumptionKWh: 10).selfSufficiency == nil)
        #expect(HomeEnergy(consumptionKW: nil, gridKW: nil, dailyConsumptionKWh: 0, dailyImportKWh: 0).selfSufficiency == nil)
        #expect(HomeEnergy(consumptionKW: nil, gridKW: nil, dailyConsumptionKWh: 1, dailyImportKWh: 3).selfSufficiency == 0)
    }

    @Test func widgetCaptionOnlyWithAMeter() {
        var snapshot = storeSampleSolar()
        let entry = { SunWattEntry(date: madrid(2026, 6, 1, 12, 0), data: SunWattData(solar: snapshot, prices: nil, solarStatus: .fresh, priceStatus: .fresh)) }
        #expect(SolarModel(entry: entry()).homeCaption == nil)
        snapshot.home = HomeEnergy(consumptionKW: 1.2, gridKW: -0.8, dailyConsumptionKWh: 5)
        let caption = SolarModel(entry: entry()).homeCaption
        #expect(caption != nil)
        #expect(caption?.contains("1") == true)
    }
}
