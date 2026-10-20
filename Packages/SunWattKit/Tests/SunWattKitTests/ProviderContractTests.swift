import Foundation
import Testing
@testable import SunWattKit

// The contracts written on `SolarDataSource`, `PriceDataSource` and `LocalInverterReader`, checked against every
// provider. A new provider adds one case to the lists below; the checks run on it unchanged.

private let contractKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
private let contractKiosk = KioskEndpoint(host: "host.example", kk: "TOKENSECRET1")

/// One solar provider set up against its fixtures.
struct SolarContractCase: Sendable, CustomTestStringConvertible {
    let name: String
    /// The client's clock.
    let now: Date
    /// URL fragment → fixture, first match wins; nil when the source does not use HTTP.
    let routes: [(String, String)]?
    /// The secret that must never appear in an error text.
    let secret: String?
    /// What HTTP 401 means for this provider (the account was rejected).
    let rejected: SunWattError?
    let expectsLive: Bool
    let make: @Sendable (StubHTTPClient, Date) -> SolarDataSource

    var testDescription: String { name }

    static let all: [SolarContractCase] = [
        SolarContractCase(
            name: "FusionSolar kiosk", now: madrid(2026, 10, 17, 14, 0),
            routes: [("station-kiosk-file", "kiosk-day-synthetic.json")], secret: contractKiosk.kk, rejected: .kioskLinkRejected,
            expectsLive: false) { http, now in FusionSolarKioskClient(endpoint: contractKiosk, http: http, now: { now }) },
        SolarContractCase(
            name: "SolarEdge", now: madrid(2026, 6, 1, 12, 35),
            routes: [("/overview", "solaredge-overview-synthetic.json"), ("/powerDetails", "solaredge-powerdetails-nometer-synthetic.json")],
            secret: contractKey, rejected: .solarCredentialsRejected, expectsLive: false) { http, now in
                SolarEdgeClient(credentials: SolarAPICredentials(siteID: "1234567", apiKey: contractKey)!, http: http, now: { now }, calendar: Constants.madridCalendar)
            },
        SolarContractCase(
            name: "SolarEdge with meter", now: madrid(2026, 6, 1, 12, 35),
            routes: [("/overview", "solaredge-overview-synthetic.json"), ("/powerDetails", "solaredge-powerdetails-meter-synthetic.json")],
            secret: contractKey, rejected: .solarCredentialsRejected, expectsLive: false) { http, now in
                SolarEdgeClient(credentials: SolarAPICredentials(siteID: "1234567", apiKey: contractKey)!, http: http, now: { now }, calendar: Constants.madridCalendar)
            },
        SolarContractCase(
            name: "PVOutput", now: madrid(2026, 6, 1, 12, 35),
            routes: [("pvoutput.org", "pvoutput-status-synthetic.txt")], secret: contractKey, rejected: .solarCredentialsRejected,
            expectsLive: false) { http, now in
                PVOutputClient(credentials: SolarAPICredentials(siteID: "42", apiKey: contractKey)!, http: http, now: { now }, calendar: Constants.madridCalendar)
            },
        SolarContractCase(
            name: "PVOutput with consumption", now: madrid(2026, 6, 1, 12, 35),
            routes: [("pvoutput.org", "pvoutput-status-consumption-synthetic.txt")], secret: contractKey, rejected: .solarCredentialsRejected,
            expectsLive: false) { http, now in
                PVOutputClient(credentials: SolarAPICredentials(siteID: "42", apiKey: contractKey)!, http: http, now: { now }, calendar: Constants.madridCalendar)
            },
        SolarContractCase(
            name: "FusionSolar API", now: madrid(2026, 10, 17, 13, 40),
            routes: northboundRoutes, secret: "SYSTEMCODE99", rejected: .northboundAccountRejected,
            expectsLive: false) { http, now in
                NorthboundClient(credentials: NorthboundCredentials(host: "", userName: "api_user", systemCode: "SYSTEMCODE99")!,
                                 http: http, now: { now }, sessions: NorthboundSessionStore())
            },
        SolarContractCase(
            name: "Huawei inverter", now: madrid(2026, 6, 1, 12, 35), routes: nil, secret: nil, rejected: nil,
            expectsLive: true) { _, now in
                LocalInverterSolarClient(local: HuaweiModbusClient(endpoint: ModbusEndpoint(host: "x"), makeTransport: { _ in
                    FakeModbusTransport(registers: fakeInverterRegisters(meterW: -500))
                }), cloud: nil, now: { now })
            },
        SolarContractCase(
            name: "SunSpec inverter", now: madrid(2026, 6, 1, 12, 35), routes: nil, secret: nil, rejected: nil,
            expectsLive: true) { _, now in
                LocalInverterSolarClient(local: SunSpecClient(endpoint: ModbusEndpoint(host: "x"), makeTransport: { _ in
                    FakeModbusTransport(registers: fakeSunSpecRegisters())
                }), cloud: nil, keepsCloudHome: true, now: { now })
            },
        SolarContractCase(
            name: "Fronius inverter", now: madrid(2026, 6, 1, 12, 35), routes: nil, secret: nil, rejected: nil,
            expectsLive: true) { _, now in
                LocalInverterSolarClient(local: FroniusSolarAPIClient(endpoint: ModbusEndpoint(host: "x", port: 80),
                                                                      http: froniusStub("fronius-powerflow-day-synthetic.json")),
                                         cloud: nil, now: { now })
            },
    ]

    /// Every reply carries a session token header (the FusionSolar API login sends it; others ignore it).
    func stub() throws -> StubHTTPClient {
        let http = StubHTTPClient()
        for (match, fixture) in routes ?? [] { http.on(match, .ok(try Fixture.data(fixture), headers: ["xsrf-token": "test-token"])) }
        return http
    }
}

/// FusionSolar API interfaces → fixtures.
let northboundRoutes: [(String, String)] = [
    ("/thirdData/login", "northbound-login-synthetic.json"),
    ("/thirdData/getStationList", "northbound-stationlist-synthetic.json"),
    ("/thirdData/getStationRealKpi", "northbound-realkpi-synthetic.json"),
    ("/thirdData/getDevList", "northbound-devlist-synthetic.json"),
    ("/thirdData/getDevRealKpi", "northbound-devrealkpi-synthetic.json"),
    ("/thirdData/getKpiStationHour", "northbound-hour-synthetic.json"),
]

@Suite struct SolarDataSourceContractTests {
    @Test(arguments: SolarContractCase.all)
    func snapshotShape(_ provider: SolarContractCase) async throws {
        let s = try await provider.make(try provider.stub(), provider.now).fetch()
        #expect(s.fetchedAt == provider.now, "fetchedAt is the client's clock")
        // Today's curve: sorted, no duplicate times, one local day.
        let times = s.curve.map(\.time)
        #expect(zip(times, times.dropFirst()).allSatisfy { $0 < $1 }, "curve sorted, no duplicates")
        if let first = times.first, let last = times.last {
            #expect(last.timeIntervalSince(first) <= 25 * 3600, "curve spans one day")
        }
        // Never negative.
        #expect(s.curve.allSatisfy { ($0.kW ?? 0) >= 0 })
        for (name, value) in [("current", s.currentPowerKW), ("today", s.dailyEnergyKWh), ("month", s.monthEnergyKWh),
                              ("year", s.yearEnergyKWh), ("total", s.cumulativeEnergyKWh)] {
            #expect((value ?? 0) >= 0, "\(name) not negative")
        }
        // Fields the loader owns.
        #expect(s.endpointID == nil, "endpointID is set by the loader")
        #expect((s.isLive == true) == provider.expectsLive)
        if !provider.expectsLive { #expect(s.cloudFetchedAt == nil) }
    }

    @Test(arguments: SolarContractCase.all.filter { $0.routes != nil })
    func networkFailureIsNetworkErrorWithoutSecrets(_ provider: SolarContractCase) async throws {
        let secret = try #require(provider.secret)
        let failure = NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "request for \(secret) failed"])
        let source = provider.make(StubHTTPClient(.failure(failure)), provider.now)
        do {
            _ = try await source.fetch()
            Issue.record("expected an error")
        } catch let error as SunWattError {
            guard case .network = error else { Issue.record("expected .network, got \(error)"); return }
            #expect(!error.localizedDescription.contains(secret), "secret redacted")
        } catch {
            Issue.record("only SunWattError or CancellationError may be thrown, got \(error)")
        }
    }

    @Test(arguments: SolarContractCase.all.filter { $0.routes != nil })
    func serverErrorsAndBadBodies(_ provider: SolarContractCase) async throws {
        await #expect(throws: SunWattError.http(status: 500)) {
            try await provider.make(StubHTTPClient(.ok(Data("oops".utf8), status: 500)), provider.now).fetch()
        }
        // 401: the account was rejected (expired link, wrong key).
        let rejected = try #require(provider.rejected)
        await #expect(throws: rejected) {
            try await provider.make(StubHTTPClient(.ok(Data(), status: 401)), provider.now).fetch()
        }
        // A body that cannot be read: a SunWattError, never a decoding error.
        do {
            _ = try await provider.make(StubHTTPClient(.ok(Data("<html>maintenance</html>".utf8))), provider.now).fetch()
            Issue.record("expected an error")
        } catch is SunWattError {
        } catch {
            Issue.record("expected SunWattError, got \(error)")
        }
    }

    @Test(arguments: SolarContractCase.all.filter { $0.routes != nil })
    func cancellationIsNotWrapped(_ provider: SolarContractCase) async {
        for failure: Error in [CancellationError(), URLError(.cancelled)] {
            await #expect(throws: CancellationError.self) {
                try await provider.make(StubHTTPClient(.failure(failure)), provider.now).fetch()
            }
        }
    }
}

// MARK: Prices

/// One price source set up against a fixture of 17 October 2026.
struct PriceContractCase: Sendable, CustomTestStringConvertible {
    let name: String
    let region: PriceRegion
    let fixture: String
    let match: String
    /// Tomorrow is a separate request (PVPC) that can fail on its own.
    let separateTomorrowRequest: Bool
    let make: @Sendable (StubHTTPClient, Date) -> PriceDataSource

    var testDescription: String { name }
    static let now = madrid(2026, 10, 17, 10)

    static let all: [PriceContractCase] = [
        PriceContractCase(name: "PVPC", region: .spain, fixture: "pvpc-2026-10-17.json", match: "apidatos", separateTomorrowRequest: true) { http, now in
            PVPCClient(http: http, now: { now })
        },
    ] + [
        (PriceRegion.germany, "energycharts-DE-LU-2026-10-17.json"),
        (.austria, "energycharts-AT-2026-10-17.json"),
        (.netherlands, "energycharts-NL-2026-10-17.json"),
        (.italyNorth, "energycharts-IT-North-2026-10-17.json"),
        (.portugal, "energycharts-PT-2026-10-17.json"),
    ].map { region, fixture in
        PriceContractCase(name: "Energy-Charts \(region.rawValue)", region: region, fixture: fixture, match: "api.energy-charts.info",
                          separateTomorrowRequest: false) { http, now in EnergyChartsClient(region: region, http: http, now: { now }) }
    }

    func stub() throws -> StubHTTPClient {
        let http = StubHTTPClient()
        http.on(match, .ok(try Fixture.data(fixture)))
        return http
    }
}

@Suite struct PriceDataSourceContractTests {
    private func checkShape(_ s: PriceSeries, _ source: PriceContractCase) {
        #expect(!s.points.isEmpty)
        #expect(s.region == source.region)
        #expect(s.fetchedAt == PriceContractCase.now)
        #expect(s.points.allSatisfy { $0.duration == 900 || $0.duration == 3600 })
        #expect(zip(s.points, s.points.dropFirst()).allSatisfy { $0.start.addingTimeInterval($0.duration) <= $1.start },
                "sorted, not overlapping")
    }

    @Test(arguments: PriceContractCase.all)
    func seriesShape(_ source: PriceContractCase) async throws {
        let client = source.make(try source.stub(), PriceContractCase.now)
        checkShape(try await client.fetch(day: PriceContractCase.now), source)
        checkShape(try await client.fetchTodayAndTomorrow(includeTomorrow: false), source)
    }

    @Test(arguments: PriceContractCase.all.filter(\.separateTomorrowRequest))
    func tomorrowFailingKeepsToday(_ source: PriceContractCase) async throws {
        let http = StubHTTPClient()
        http.on("2026-10-18", .ok(Data("oops".utf8), status: 500))
        http.on(source.match, .ok(try Fixture.data(source.fixture)))
        checkShape(try await source.make(http, PriceContractCase.now).fetchTodayAndTomorrow(includeTomorrow: true), source)
    }

    @Test(arguments: PriceContractCase.all)
    func errors(_ source: PriceContractCase) async throws {
        let now = PriceContractCase.now
        do {
            _ = try await source.make(StubHTTPClient(.failure(URLError(.notConnectedToInternet))), now).fetch(day: now)
            Issue.record("expected an error")
        } catch let error as SunWattError {
            guard case .network = error else { Issue.record("expected .network, got \(error)"); return }
        }
        await #expect(throws: SunWattError.http(status: 500)) {
            try await source.make(StubHTTPClient(.ok(Data("oops".utf8), status: 500)), now).fetch(day: now)
        }
        await #expect(throws: SunWattError.self) {
            try await source.make(StubHTTPClient(.ok(Data("<html>maintenance</html>".utf8))), now).fetch(day: now)
        }
        await #expect(throws: CancellationError.self) {
            try await source.make(StubHTTPClient(.failure(CancellationError())), now).fetch(day: now)
        }
    }
}

// MARK: Inverters on the home network

/// One inverter kind with a fake device that answers like it.
struct InverterContractCase: Sendable, CustomTestStringConvertible {
    let name: String
    let registers: [UInt16: UInt16]
    let make: @Sendable (ModbusEndpoint, @escaping @Sendable (ModbusEndpoint) -> ModbusTransport?) -> any LocalInverterReader

    var testDescription: String { name }

    static let all: [InverterContractCase] = [
        InverterContractCase(name: "Huawei", registers: fakeInverterRegisters(meterW: 1200)) { HuaweiModbusClient(endpoint: $0, makeTransport: $1) },
        InverterContractCase(name: "SunSpec integer", registers: fakeSunSpecRegisters()) { SunSpecClient(endpoint: $0, makeTransport: $1) },
        InverterContractCase(name: "SunSpec float", registers: fakeSunSpecRegisters(float: true)) { SunSpecClient(endpoint: $0, makeTransport: $1) },
    ]
}

@Suite struct LocalInverterReaderContractTests {
    @Test(arguments: InverterContractCase.all)
    func readingIsReadOnlyAndClosed(_ inverter: InverterContractCase) async throws {
        let transport = FakeModbusTransport(registers: inverter.registers)
        let reader = inverter.make(ModbusEndpoint(host: "x"), { _ in transport })
        let reading = try await reader.readLive()
        #expect(reading.activePowerKW.isFinite)
        #expect((reading.dailyEnergyKWh ?? 0) >= 0 && (reading.totalEnergyKWh ?? 0) >= 0)
        #expect(transport.closed, "one connection per call, closed afterwards")
        #expect(!transport.requests.isEmpty && transport.requests.allSatisfy { $0[7] == 0x03 }, "read holding registers only")
        let (_, identified) = try await reader.identifyLive()
        #expect(identified == reading)
    }

    @Test(arguments: InverterContractCase.all)
    func errors(_ inverter: InverterContractCase) async {
        let endpoint = ModbusEndpoint(host: "x")
        await #expect(throws: SunWattError.inverterUnreachable) {
            try await inverter.make(endpoint, { _ in FakeModbusTransport(registers: [:], reachable: false) }).readLive()
        }
        // No transport (watchOS).
        await #expect(throws: SunWattError.inverterUnreachable) { try await inverter.make(endpoint, { _ in nil }).readLive() }
        // Another unit ID answered with Modbus exceptions: the caller may try the next one.
        await #expect(throws: ModbusFrame.Failure.self) {
            try await inverter.make(endpoint, { _ in FakeModbusTransport(registers: inverter.registers, unit: 7) }).readLive()
        }
    }
}

// MARK: Price regions

/// Every region's profile is consistent with its client, and every kind of source has a contract case above.
@Suite struct PriceRegionProfileContractTests {
    /// Exhaustive: a new kind of source must be named here (and get a `PriceContractCase`).
    private func kind(_ source: PriceSource) -> String {
        switch source {
        case .redElectrica: "Red Eléctrica"
        case .energyCharts: "Energy-Charts"
        }
    }

    @Test(arguments: PriceRegion.allCases)
    func profileMatchesTheClient(_ region: PriceRegion) throws {
        let profile = region.profile
        #expect(!profile.flag.isEmpty && !region.displayName.isEmpty)
        #expect(region.calendar.timeZone.identifier == profile.timeZone.identifier)
        #expect(region.isRetailTariff == profile.source.isRetailTariff)
        let client = PriceDataSourceFactory.client(for: region)
        switch profile.source {
        case .redElectrica:
            #expect(client is PVPCClient)
        case .energyCharts(let zone):
            #expect((client as? EnergyChartsClient)?.region == region)
            let day = madrid(2026, 10, 17)
            #expect(try EnergyChartsClient.url(zone: region, startDay: day, endDay: day).absoluteString.contains("bzn=\(zone)&"))
            // Wholesale prices carry their licence; the app shows it next to them.
            #expect(profile.source.license == "CC BY 4.0")
        }
    }

    @Test func everyKindOfSourceHasAContractCase() {
        let used = Set(PriceRegion.allCases.map { kind($0.profile.source) })
        let covered = Set(PriceContractCase.all.map { kind($0.region.profile.source) })
        #expect(used == covered)
    }
}

// MARK: Inverters read over HTTP

/// The Solar API answering with `fixture`. The inverter is on the home network: its errors are "unreachable", not the
/// cloud errors checked above.
func froniusStub(_ fixture: String) -> StubHTTPClient {
    let http = StubHTTPClient()
    http.on("GetPowerFlowRealtimeData", .ok((try? Fixture.data(fixture)) ?? Data()))
    return http
}

/// One HTTP inverter reader against its fixture.
struct HTTPInverterContractCase: Sendable, CustomTestStringConvertible {
    let name: String
    let fixture: String
    let make: @Sendable (ModbusEndpoint, HTTPClient) -> any LocalInverterReader

    var testDescription: String { name }

    static let all: [HTTPInverterContractCase] = [
        HTTPInverterContractCase(name: "Fronius day", fixture: "fronius-powerflow-day-synthetic.json") { FroniusSolarAPIClient(endpoint: $0, http: $1) },
        HTTPInverterContractCase(name: "Fronius GEN24 night", fixture: "fronius-powerflow-gen24-night-synthetic.json") {
            FroniusSolarAPIClient(endpoint: $0, http: $1)
        },
    ]
}

@Suite struct HTTPInverterReaderContractTests {
    private let endpoint = ModbusEndpoint(host: "x", port: 80)

    @Test(arguments: HTTPInverterContractCase.all)
    func readingIsGetOnly(_ inverter: HTTPInverterContractCase) async throws {
        let http = froniusStub(inverter.fixture)
        let reader = inverter.make(endpoint, http)
        let reading = try await reader.readLive()
        #expect(reading.activePowerKW.isFinite)
        #expect((reading.dailyEnergyKWh ?? 0) >= 0 && (reading.totalEnergyKWh ?? 0) >= 0 && (reading.homeConsumptionKW ?? 0) >= 0)
        #expect(http.requests.count == 1, "one request per call")
        #expect(http.requests.allSatisfy { ($0.httpMethod ?? "GET") == "GET" && $0.httpBody == nil }, "GET only")
        let (_, identified) = try await reader.identifyLive()
        #expect(identified == reading)
    }

    @Test(arguments: HTTPInverterContractCase.all)
    func errors(_ inverter: HTTPInverterContractCase) async {
        await #expect(throws: SunWattError.inverterUnreachable) {
            try await inverter.make(endpoint, StubHTTPClient(.failure(URLError(.cannotConnectToHost)))).readLive()
        }
        // Another device answered on the port.
        await #expect(throws: SunWattError.inverterUnreachable) {
            try await inverter.make(endpoint, StubHTTPClient(.ok(Data("<html>Router</html>".utf8), status: 404))).readLive()
        }
        await #expect(throws: CancellationError.self) {
            try await inverter.make(endpoint, StubHTTPClient(.failure(CancellationError()))).readLive()
        }
    }
}
