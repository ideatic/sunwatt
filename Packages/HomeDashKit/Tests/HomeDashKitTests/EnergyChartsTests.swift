import Foundation
import Testing
@testable import HomeDashKit

private func iso(_ text: String) -> Date { PVPCClient.parseDate(text)! }

private func json(unix: [Int], prices: [String], unit: String = "EUR / MWh") -> Data {
    let u = unix.map(String.init).joined(separator: ",")
    return Data(#"{"license_info":"x","unix_seconds":[\#(u)],"price":[\#(prices.joined(separator: ","))],"unit":"\#(unit)","deprecated":false}"#.utf8)
}

@Suite struct EnergyChartsParsingTests {
    @Test func germanyFixture() throws {
        let s = try EnergyChartsClient.parse(Fixture.data("energycharts-DE-LU-2026-10-17.json"), region: .germany, fetchedAt: madrid(2026, 10, 17, 10))
        #expect(s.points.count == 96)
        #expect(s.granularity == .quarterHourly)
        #expect(s.points.allSatisfy { $0.duration == 900 })
        #expect(s.region == .germany)
        #expect(s.points[0].start == iso("2026-10-16T22:00:00Z"))
        #expect(s.points[0].start == madrid(2026, 10, 17))
        #expect(s.points == s.points.sorted { $0.start < $1.start })
        #expect(s.hourly().count == 24)
        #expect(s.hours(onDayOf: madrid(2026, 10, 17, 12)).count == 24)
        #expect(s.stats(onDayOf: madrid(2026, 10, 17, 12)) != nil)
        #expect(s.sourceUpdatedAt == nil)
    }

    @Test func portugalFixtureStartsAtLisbonMidnight() throws {
        let s = try EnergyChartsClient.parse(Fixture.data("energycharts-PT-2026-10-17.json"), region: .portugal, fetchedAt: Date())
        #expect(s.points.count == 92)
        #expect(s.points[0].start == iso("2026-10-16T23:00:00Z"))
        #expect(s.region == .portugal)
        #expect(s.region.calendar.startOfDay(for: s.points[0].start) == s.points[0].start)
    }

    @Test func otherZoneFixturesParse() throws {
        for (name, region) in [("NL", PriceRegion.netherlands), ("AT", .austria), ("IT-North", .italyNorth)] {
            let s = try EnergyChartsClient.parse(Fixture.data("energycharts-\(name)-2026-10-17.json"), region: region, fetchedAt: Date())
            #expect(s.points.count == 96)
            #expect(s.region == region)
        }
    }

    @Test func springForwardDayHas92Points() throws {
        let data = try Fixture.data("energycharts-DE-LU-2026-03-29-dst92.json")
        let s = try EnergyChartsClient.parse(data, region: .germany, fetchedAt: Date())
        #expect(s.points.count == 92)
        #expect(s.hours(onDayOf: madrid(2026, 3, 29, 12)).count == 23)
    }

    @Test func nullPricesAreSkipped() throws {
        let s = try EnergyChartsClient.parse(json(unix: [0, 900, 1800, 2700], prices: ["1.5", "null", "3", "null"]), region: .france, fetchedAt: Date())
        #expect(s.points.map(\.eurPerMWh) == [1.5, 3])
        #expect(s.points.map(\.start.timeIntervalSince1970) == [0, 1800])
    }

    @Test func hourlyResponseInfersHourlyDuration() throws {
        let s = try EnergyChartsClient.parse(json(unix: [0, 3600, 7200], prices: ["1", "2", "3"]), region: .belgium, fetchedAt: Date())
        #expect(s.granularity == .hourly)
        #expect(s.points.allSatisfy { $0.duration == 3600 })
    }

    @Test func otherUnitIsBadResponse() {
        for unit in ["EUR / kWh", "USD / MWh", ""] {
            do {
                _ = try EnergyChartsClient.parse(json(unix: [0], prices: ["1"], unit: unit), region: .france, fetchedAt: Date())
                Issue.record("expected throw for \(unit)")
            } catch HomeDashError.badResponse {
            } catch { Issue.record("wrong error \(error)") }
        }
        #expect(throws: HomeDashError.self) { try EnergyChartsClient.parse(Data(#"{"unix_seconds":[0],"price":[1]}"#.utf8), region: .france, fetchedAt: Date()) }
    }

    @Test func unitSpacingAndCaseAreTolerated() throws {
        let s = try EnergyChartsClient.parse(json(unix: [0], prices: ["1"], unit: "eur/MWh"), region: .france, fetchedAt: Date())
        #expect(s.points.count == 1)
    }

    @Test func emptyOrAllNullIsUnavailable() {
        #expect(throws: HomeDashError.unavailable) { try EnergyChartsClient.parse(json(unix: [], prices: []), region: .france, fetchedAt: Date()) }
        #expect(throws: HomeDashError.unavailable) { try EnergyChartsClient.parse(json(unix: [0, 900], prices: ["null", "null"]), region: .france, fetchedAt: Date()) }
    }

    @Test func garbageIsBadResponse() {
        #expect(throws: HomeDashError.self) { try EnergyChartsClient.parse(Data("nope".utf8), region: .france, fetchedAt: Date()) }
        #expect(throws: HomeDashError.self) { try EnergyChartsClient.parse(Data(#"{"detail":"x","unit":"EUR / MWh"}"#.utf8), region: .france, fetchedAt: Date()) }
    }

    @Test func windowTrimsPoints() throws {
        let data = json(unix: [0, 900, 1800, 2700], prices: ["1", "2", "3", "4"])
        let window = DateInterval(start: Date(timeIntervalSince1970: 900), end: Date(timeIntervalSince1970: 2700))
        let s = try EnergyChartsClient.parse(data, region: .france, fetchedAt: Date(), window: window)
        #expect(s.points.map(\.eurPerMWh) == [2, 3])
    }
}

@Suite struct EnergyChartsFetchTests {
    private func client(_ region: PriceRegion, _ stub: StubHTTPClient, now: Date) -> EnergyChartsClient {
        EnergyChartsClient(region: region, http: stub, now: { now })
    }

    @Test func fetchBuildsLocalDayURL() async throws {
        let stub = StubHTTPClient()
        stub.on("api.energy-charts.info", .ok(try Fixture.data("energycharts-DE-LU-2026-10-17.json")))
        let s = try await client(.germany, stub, now: madrid(2026, 10, 17, 10)).fetch(day: madrid(2026, 10, 17, 18, 30))
        #expect(s.points.count == 96)
        let url = try #require(stub.requestedURLs.first)
        #expect(url.absoluteString == "https://api.energy-charts.info/price?bzn=DE-LU&start=2026-10-17&end=2026-10-17")
        #expect(stub.requests.first?.cachePolicy == .reloadIgnoringLocalCacheData)
    }

    @Test func dayBoundaryDiffersBetweenPortugalAndGermany() async throws {
        // 22:30Z: 23:30 on 17 Oct in Lisbon (UTC+1), 00:30 on 18 Oct in Berlin (UTC+2).
        let now = iso("2026-10-17T22:30:00Z")
        let pt = StubHTTPClient()
        pt.on("api.energy-charts.info", .ok(try Fixture.data("energycharts-PT-2026-10-17.json")))
        _ = try await client(.portugal, pt, now: now).fetch(day: now)
        #expect(pt.requestedURLs.first?.absoluteString == "https://api.energy-charts.info/price?bzn=PT&start=2026-10-17&end=2026-10-17")

        let de = StubHTTPClient()
        de.on("api.energy-charts.info", .ok(try Fixture.data("energycharts-DE-LU-2026-10-17.json")))
        // The fixture holds 17 Oct; asking for the 18th gives nothing in the window.
        await #expect(throws: HomeDashError.unavailable) { try await client(.germany, de, now: now).fetch(day: now) }
        #expect(de.requestedURLs.first?.absoluteString == "https://api.energy-charts.info/price?bzn=DE-LU&start=2026-10-18&end=2026-10-18")
    }

    @Test func todayAndTomorrowIsOneRequest() async throws {
        let now = iso("2026-10-16T10:00:00Z") // 12:00 Madrid, 11:00 Lisbon on 16 Oct
        let stub = StubHTTPClient()
        stub.on("api.energy-charts.info", .ok(try Fixture.data("energycharts-PT-2026-10-16-to-17.json")))
        let both = try await client(.portugal, stub, now: now).fetchTodayAndTomorrow(includeTomorrow: true)
        #expect(stub.requestedURLs.count == 1)
        #expect(stub.requestedURLs.first?.absoluteString == "https://api.energy-charts.info/price?bzn=PT&start=2026-10-16&end=2026-10-17")
        #expect(both.points.count == 188)
        #expect(both.fetchedAt == now)
        #expect(both.region == .portugal)
        #expect(both.hours(onDayOf: iso("2026-10-16T12:00:00Z")).count == 24)

        let todayStub = StubHTTPClient()
        todayStub.on("api.energy-charts.info", .ok(try Fixture.data("energycharts-PT-2026-10-16-to-17.json")))
        let only = try await client(.portugal, todayStub, now: now).fetchTodayAndTomorrow(includeTomorrow: false)
        #expect(only.points.count == 96)
        #expect(only.points.last?.start == iso("2026-10-16T22:45:00Z"))
        #expect(todayStub.requestedURLs.count == 1)
    }

    @Test func tomorrowNotPublishedStillGivesToday() async throws {
        // The API answers an end date that is not published yet with today's data only.
        let stub = StubHTTPClient()
        stub.on("api.energy-charts.info", .ok(try Fixture.data("energycharts-DE-LU-2026-10-17.json")))
        let s = try await client(.germany, stub, now: madrid(2026, 10, 17, 10)).fetchTodayAndTomorrow(includeTomorrow: true)
        #expect(s.points.count == 96)
        #expect(stub.requestedURLs.count == 1)
    }

    @Test func httpStatusMapping() async {
        let now = madrid(2026, 10, 17, 10)
        let notFound = StubHTTPClient()
        notFound.on("energy-charts", .ok(Data("{}".utf8), status: 404))
        await #expect(throws: HomeDashError.unavailable) { try await client(.france, notFound, now: now).fetch(day: now) }
        await #expect(throws: HomeDashError.unavailable) { try await client(.france, notFound, now: now).fetchTodayAndTomorrow(includeTomorrow: true) }

        let limited = StubHTTPClient()
        limited.on("energy-charts", .ok(Data("slow down".utf8), status: 429))
        await #expect(throws: HomeDashError.http(status: 429)) { try await client(.france, limited, now: now).fetch(day: now) }

        let broken = StubHTTPClient()
        broken.on("energy-charts", .ok(Data(), status: 500))
        await #expect(throws: HomeDashError.http(status: 500)) { try await client(.france, broken, now: now).fetch(day: now) }

        let emptyDay = StubHTTPClient()
        emptyDay.on("energy-charts", .ok(json(unix: [], prices: []), status: 200))
        await #expect(throws: HomeDashError.unavailable) { try await client(.france, emptyDay, now: now).fetch(day: now) }

        let offline = StubHTTPClient(.failure(URLError(.notConnectedToInternet)))
        do { _ = try await client(.france, offline, now: now).fetch(day: now) } catch HomeDashError.network {} catch { Issue.record("\(error)") }
    }

    @Test func zoneCodes() {
        #expect(PriceRegion.spain.energyChartsZone == nil)
        #expect(PriceRegion.germany.energyChartsZone == "DE-LU")
        #expect(PriceRegion.france.energyChartsZone == "FR")
        #expect(PriceRegion.portugal.energyChartsZone == "PT")
        #expect(PriceRegion.belgium.energyChartsZone == "BE")
        #expect(PriceRegion.netherlands.energyChartsZone == "NL")
        #expect(PriceRegion.austria.energyChartsZone == "AT")
        #expect(PriceRegion.italyNorth.energyChartsZone == "IT-North")
    }

    @Test func spainIsNotServedByEnergyCharts() async {
        await #expect(throws: HomeDashError.self) { try await EnergyChartsClient(region: .spain, http: StubHTTPClient()).fetch(day: Date()) }
    }

    @Test func factoryPicksTheRightClient() {
        #expect(PriceDataSourceFactory.client(for: .spain) is PVPCClient)
        #expect(PriceRegion.client(for: .spain) is PVPCClient)
        for region in PriceRegion.allCases where region != .spain {
            let client = PriceDataSourceFactory.client(for: region)
            #expect((client as? EnergyChartsClient)?.region == region)
        }
    }

    @Test func regionNames() {
        #expect(PriceRegion.spain.tariffName == "PVPC")
        #expect(PriceRegion.spain.sourceName == "Red Eléctrica")
        #expect(PriceRegion.germany.sourceName == "Energy-Charts (Fraunhofer ISE)")
        let names = PriceRegion.allCases.map(\.displayName)
        #expect(names.allSatisfy { !$0.isEmpty })
        #expect(Set(names).count == names.count)
        #expect(PriceRegion.germany.tariffName == L10n.string("Day-ahead price"))
        #expect(PriceRegion.italyNorth.displayName == L10n.string("Italy (North)"))
    }
}
