import Testing
import Foundation
@testable import SunWattKit

/// End-to-end checks against the real services. Skipped unless `SUNWATT_LIVE=1`.
/// The kiosk URL is read from the git-ignored `private/kiosk.local` and never printed; SolarEdge and PVOutput
/// credentials from `private/solaredge.local` and `private/pvoutput.local` (ID, then key), when present.
@Suite(.serialized, .enabled(if: ProcessInfo.processInfo.environment["SUNWATT_LIVE"] == "1"))
struct LiveTests {
    static func kioskURL() throws -> String {
        let root = URL(filePath: #filePath).deletingLastPathComponent().appending(path: "../../../../../private/kiosk.local")
        return try String(contentsOf: root.standardized, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @Test func kioskEndToEnd() async throws {
        let endpoint = try #require(KioskEndpoint(parsing: try Self.kioskURL()))
        let snapshot = try await FusionSolarKioskClient(endpoint: endpoint).fetch()
        #expect(snapshot.curve.count > 200)
        #expect(snapshot.currentPowerKW != nil)
        #expect(snapshot.dailyEnergyKWh != nil)
        print("live kiosk: power=\(snapshot.currentPowerKW ?? -1) kW, today=\(snapshot.dailyEnergyKWh ?? -1) kWh, points=\(snapshot.curve.count), nonNil=\(snapshot.curve.filter { $0.kW != nil }.count)")
    }

    /// `private/<name>.local`: two lines, site/system ID then API key. Nil when the file is missing (test skipped).
    static func apiCredentials(_ name: String) -> SolarAPICredentials? {
        let url = URL(filePath: #filePath).deletingLastPathComponent().appending(path: "../../../../../private/\(name).local")
        guard let text = try? String(contentsOf: url.standardized, encoding: .utf8) else { return nil }
        let lines = text.split(whereSeparator: \.isNewline).map(String.init)
        guard lines.count >= 2 else { return nil }
        return SolarAPICredentials(siteID: lines[0], apiKey: lines[1])
    }

    @Test(.enabled(if: LiveTests.apiCredentials("solaredge") != nil))
    func solarEdgeEndToEnd() async throws {
        let credentials = try #require(Self.apiCredentials("solaredge"))
        let snapshot = try await SolarEdgeClient(credentials: credentials).fetch()
        #expect(snapshot.curve.count >= 90)
        #expect(snapshot.currentPowerKW != nil)
        #expect(snapshot.dailyEnergyKWh != nil)
        print("live solaredge: power=\(snapshot.currentPowerKW ?? -1) kW, today=\(snapshot.dailyEnergyKWh ?? -1) kWh, points=\(snapshot.curve.count), nonNil=\(snapshot.curve.filter { $0.kW != nil }.count)")
    }

    @Test(.enabled(if: LiveTests.apiCredentials("pvoutput") != nil))
    func pvOutputEndToEnd() async throws {
        let credentials = try #require(Self.apiCredentials("pvoutput"))
        let snapshot = try await PVOutputClient(credentials: credentials).fetch()
        #expect(snapshot.dailyEnergyKWh != nil)
        print("live pvoutput: power=\(snapshot.currentPowerKW ?? -1) kW, today=\(snapshot.dailyEnergyKWh ?? -1) kWh, points=\(snapshot.curve.count)")
    }

    @Test(arguments: PriceRegion.allCases)
    func pricesEndToEnd(region: PriceRegion) async throws {
        if region != .spain { try await Task.sleep(for: .seconds(15)) } // Energy-Charts rate limit
        let series = try await PriceDataSourceFactory.client(for: region).fetchTodayAndTomorrow(includeTomorrow: true)
        let now = Date()
        let current = try #require(series.currentHour(at: now))
        let hours = series.hours(onDayOf: now)
        #expect(hours.count >= 23)
        print("live \(region.rawValue): now=\(current.eurPerKWh) €/kWh, hours today=\(hours.count), total points=\(series.points.count), granularity=\(series.granularity)")
    }
}
