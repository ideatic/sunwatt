import Foundation
import Testing
@testable import SunWattKit

/// Fronius Solar API on the home network: parsing, errors, the account and "Find inverter".
@Suite struct FroniusTests {
    private let endpoint = ModbusEndpoint(host: "192.168.1.30", port: 80)

    private func stub(_ fixture: String) throws -> StubHTTPClient {
        let http = StubHTTPClient()
        http.on("GetPowerFlowRealtimeData", .ok(try Fixture.data(fixture)))
        return http
    }

    private func close(_ a: Double?, _ b: Double) -> Bool {
        guard let a else { return false }
        return abs(a - b) < 0.0005
    }

    @Test func daytimeReadingWithMeter() throws {
        let reading = try FroniusSolarAPIClient.parse(try Fixture.data("fronius-powerflow-day-synthetic.json"))
        #expect(close(reading.activePowerKW, 3.2109))
        #expect(close(reading.dailyEnergyKWh, 7.954))
        #expect(close(reading.totalEnergyKWh, 12345.678))
        // P_Grid -1100.5 W: feeding the grid.
        #expect(close(reading.meterActivePowerKW, 1.1005))
        #expect(close(reading.homeConsumptionKW, 2.1104))
    }

    /// GEN24 at night with a battery: no PV, no energy today, the battery covers most of the home.
    @Test func gen24NightWithBattery() throws {
        let reading = try FroniusSolarAPIClient.parse(try Fixture.data("fronius-powerflow-gen24-night-synthetic.json"))
        #expect(reading.activePowerKW == 0)
        #expect(reading.dailyEnergyKWh == nil)
        #expect(close(reading.totalEnergyKWh, 8765.4321))
        #expect(close(reading.meterActivePowerKW, -0.3502))
        // P_Load, not production + grid (that would miss the battery's 1.28 kW).
        #expect(close(reading.homeConsumptionKW, 1.6305))
        let snapshot = LocalInverterSolarClient.merge(reading, into: nil, at: madrid(2026, 6, 1, 23, 10))
        #expect(close(snapshot.home?.consumptionKW, 1.6305))
        #expect(close(snapshot.home?.gridKW, 0.3502))
    }

    @Test func readsWithOneGet() async throws {
        let http = try stub("fronius-powerflow-day-synthetic.json")
        let client = FroniusSolarAPIClient(endpoint: endpoint, http: http)
        let (model, reading) = try await client.identifyLive()
        #expect(model == "Fronius")
        #expect(try await client.readLive() == reading)
        #expect(http.requests.count == 2 && http.requests.allSatisfy { ($0.httpMethod ?? "GET") == "GET" })
        #expect(http.requestedURLs.first?.absoluteString == "http://192.168.1.30:80/solar_api/v1/GetPowerFlowRealtimeData.fcgi")
    }

    /// No connection, another device on port 80, or the inverter busy: "no inverter there".
    @Test func anythingElseIsUnreachable() async throws {
        let answers: [StubHTTPClient.Reply] = [
            .failure(URLError(.cannotConnectToHost)),
            .ok(Data("<html>Router</html>".utf8), status: 404),
            .ok(Data("<html>Router</html>".utf8)),
            .ok(Data(#"{"Head":{"Status":{"Code":8,"Reason":"busy"}},"Body":{"Data":{}}}"#.utf8)),
        ]
        for answer in answers {
            await #expect(throws: SunWattError.inverterUnreachable) {
                try await FroniusSolarAPIClient(endpoint: endpoint, http: StubHTTPClient(answer)).readLive()
            }
        }
        await #expect(throws: CancellationError.self) {
            try await FroniusSolarAPIClient(endpoint: endpoint, http: StubHTTPClient(.failure(URLError(.cancelled)))).readLive()
        }
    }

    @Test func accountFromSettings() throws {
        var settings = SunWattSettings()
        settings.solarProvider = .fronius
        #expect(SolarAccount(settings: settings) == nil)
        #expect(!settings.hasSolarInput)
        settings.froniusHost = "not an address!"
        #expect(SolarAccount(settings: settings) == nil)
        #expect(settings.hasSolarInput && settings.invalidSolarInputError == .invalidInverterAddress)

        settings.froniusHost = " 192.168.1.30 "
        let account = try #require(SolarAccount(settings: settings))
        #expect(account == .fronius(local: endpoint))
        #expect(account.provider == .fronius && account.kioskEndpoint == nil)
        #expect(account.cacheID.count == 64)
        #expect(account.client() is LocalInverterSolarClient)
        #expect(account.localReader() is FroniusSolarAPIClient)
        #expect(account.liveOnlyClient() != nil)

        settings.froniusHost = "192.168.1.30:8080"
        #expect(SolarAccount(settings: settings)?.localInverter?.port == 8080)
        #expect(SolarAccount(settings: settings)?.cacheID != account.cacheID)
    }

    @Test func providerProfile() {
        #expect(SolarProvider.pickerCases.contains(.fronius))
        #expect(SolarProvider.fronius.profile.setup == .homeNetwork)
        #expect(!SolarProvider.fronius.usesCredentialFields && !SolarProvider.fronius.usesAPIKey)
        #expect(LocalInverterKind(provider: .fronius) == .fronius)
        #expect(LocalInverterKind.fronius.ports == [80] && LocalInverterKind.fronius.defaultPort == 80)
    }

    @Test func addressesUseThePortOfTheKind() throws {
        let plain = try #require(ModbusEndpoint(parsing: "192.168.1.30", defaultPort: 80))
        #expect(plain.port == 80 && plain.text(defaultPort: 80) == "192.168.1.30")
        let other = try #require(ModbusEndpoint(parsing: "192.168.1.30:8080", defaultPort: 80))
        #expect(other.port == 8080 && other.text(defaultPort: 80) == "192.168.1.30:8080")
        // Modbus addresses are unchanged.
        #expect(ModbusEndpoint(parsing: "192.168.1.40")?.port == 502)
    }

    @Test func identifyForFindInverter() async throws {
        let http = try stub("fronius-powerflow-day-synthetic.json")
        let match = try await InverterFinder.identify(kind: .fronius, host: "192.168.1.30", ports: [80], unitIDs: [1],
                                                      makeTransport: nil, http: http).get()
        #expect(match.address == "192.168.1.30" && match.model == "Fronius")
        #expect(close(match.reading.activePowerKW, 3.2109))

        let finder = InverterFinder(kind: .fronius, candidates: { ["192.168.1.1", "192.168.1.30"] }, probe: { _, port in
            port == 80 // The router answers on port 80 too, but not as a Solar API.
        }, makeTransport: nil, http: RoutedByHost(fronius: "192.168.1.30", body: try Fixture.data("fronius-powerflow-day-synthetic.json")))
        let found = await finder.find()
        #expect(found.map(\.address) == ["192.168.1.30"])
    }

    /// At home the inverter's reading; away (no answer, no cloud) the loader keeps the last data.
    @Test func plantWithoutCloud() async throws {
        let now = madrid(2026, 6, 1, 12, 35)
        let home = LocalInverterSolarClient(local: FroniusSolarAPIClient(endpoint: endpoint, http: try stub("fronius-powerflow-day-synthetic.json")),
                                            cloud: nil, now: { now })
        let snapshot = try await home.fetch()
        #expect(snapshot.isLive == true && close(snapshot.currentPowerKW, 3.2109) && close(snapshot.dailyEnergyKWh, 7.954))
        let away = LocalInverterSolarClient(local: FroniusSolarAPIClient(endpoint: endpoint, http: StubHTTPClient()), cloud: nil, now: { now })
        await #expect(throws: SunWattError.inverterUnreachable) { try await away.fetch() }
    }
}

/// The Solar API answer for one host; every other host answers like a router's web page.
private struct RoutedByHost: HTTPClient {
    let fronius: String
    let body: Data

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        guard let url = request.url else { throw URLError(.badURL) }
        let isFronius = url.host == fronius
        let response = HTTPURLResponse(url: url, statusCode: isFronius ? 200 : 404, httpVersion: nil, headerFields: nil)!
        return (isFronius ? body : Data("<html>Router</html>".utf8), response)
    }
}
