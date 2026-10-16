import Testing
import Foundation
@testable import HomeDashKit

/// End-to-end checks against the real services. Skipped unless `HOMEDASH_LIVE=1`.
/// The kiosk URL is read from the git-ignored `Config/kiosk.local` and never printed.
@Suite(.serialized, .enabled(if: ProcessInfo.processInfo.environment["HOMEDASH_LIVE"] == "1"))
struct LiveTests {
    static func kioskURL() throws -> String {
        let root = URL(filePath: #filePath).deletingLastPathComponent().appending(path: "../../../../Config/kiosk.local")
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
