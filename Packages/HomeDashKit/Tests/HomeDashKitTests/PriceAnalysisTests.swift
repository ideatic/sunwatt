import Foundation
import Testing
@testable import HomeDashKit

struct PriceAnalysisTests {
    private func series(_ name: String) throws -> PriceSeries {
        try PVPCClient.parse(Fixture.data(name), fetchedAt: madrid(2026, 10, 17, 10))
    }

    private func instant(_ iso: String) -> Date { PVPCClient.parseDate(iso)! }

    @Test func currentHourPicksContainingPoint() throws {
        let s = try series("pvpc-2026-10-17.json")
        #expect(s.currentHour(at: madrid(2026, 10, 17, 14, 37))?.start == madrid(2026, 10, 17, 14))
        #expect(s.currentHour(at: madrid(2026, 10, 17, 15))?.start == madrid(2026, 10, 17, 15))
        #expect(s.currentHour(at: madrid(2026, 10, 18, 0, 5)) == nil)
        #expect(s.currentHour(at: madrid(2026, 10, 16, 23, 59)) == nil)
    }

    @Test func dstDayCounts() throws {
        let spring = try series("pvpc-2026-03-29-dst23.json")
        #expect(spring.hours(onDayOf: madrid(2026, 3, 29, 12)).count == 23)
        let fall = try series("pvpc-2025-10-26-dst25.json")
        #expect(fall.hours(onDayOf: madrid(2025, 10, 26, 12)).count == 25)
        #expect(fall.hours(onDayOf: madrid(2025, 10, 27, 12)).isEmpty)
    }

    @Test func fallBackSecondPassIsSeparate() throws {
        let s = try series("pvpc-2025-10-26-dst25.json")
        let second = instant("2025-10-26T02:30:00.000+01:00")
        let point = try #require(s.currentHour(at: second))
        #expect(point.start == instant("2025-10-26T02:00:00.000+01:00"))
        #expect(point.eurPerMWh == 126.46)
        let first = try #require(s.currentHour(at: instant("2025-10-26T02:30:00.000+02:00")))
        #expect(first.eurPerMWh == 131.07)
    }

    @Test func hoursAroundAcrossDST() throws {
        let s = try series("pvpc-2025-10-26-dst25.json")
        let around = s.hours(around: instant("2025-10-26T02:30:00.000+01:00"), before: 6, after: 12)
        // 6 hours before the 02:00+01:00 hour starts at 20:00 prior day (absent); data runs 00:00+02:00 .. 13:00 UTC.
        #expect(around.count == 16)
        #expect(around.first?.start == instant("2025-10-26T00:00:00.000+02:00"))
        #expect(around.last?.start == instant("2025-10-26T14:00:00.000+01:00"))
        #expect(around.contains { $0.eurPerMWh == 126.46 })
        #expect(around.contains { $0.eurPerMWh == 131.07 })
    }

    @Test func hoursAroundIsInclusive() throws {
        let s = try series("pvpc-2026-10-17.json")
        let around = s.hours(around: madrid(2026, 10, 17, 10, 20), before: 2, after: 3)
        #expect(around.map(\.start) == (8...13).map { madrid(2026, 10, 17, $0) })
    }

    @Test func statsMatchHandComputedValues() throws {
        let s = try series("pvpc-2026-10-17.json")
        let stats = try #require(s.stats(onDayOf: madrid(2026, 10, 17, 9)))
        #expect(abs(stats.min - 0.01781) < 1e-12)
        #expect(abs(stats.max - 0.24676) < 1e-12)
        #expect(abs(stats.average - 0.14432291666666666) < 1e-9)
        #expect(s.stats(onDayOf: madrid(2026, 10, 19)) == nil)
    }

    @Test func fractionAndLevel() throws {
        let s = try series("pvpc-2026-10-17.json")
        let lowest = try #require(s.points.min { $0.eurPerMWh < $1.eurPerMWh })
        let highest = try #require(s.points.max { $0.eurPerMWh < $1.eurPerMWh })
        #expect(s.fraction(of: lowest) == 0)
        #expect(s.fraction(of: highest) == 1)
        #expect(s.level(of: lowest) == .low)
        #expect(s.level(of: highest) == .high)
        let mid = PricePoint(start: madrid(2026, 10, 17, 3), duration: 3600, eurPerMWh: (17.81 + 246.76) / 2)
        #expect(abs(s.fraction(of: mid) - 0.5) < 1e-9)
        #expect(s.level(of: mid) == .medium)
    }

    @Test func flatDayFractionIsHalf() {
        let pts = (0..<24).map { PricePoint(start: madrid(2026, 10, 17, $0), duration: 3600, eurPerMWh: 100) }
        let s = PriceSeries(points: pts, granularity: .hourly, fetchedAt: Date())
        #expect(s.fraction(of: pts[5]) == 0.5)
        #expect(s.level(of: pts[5]) == .medium)
    }

    @Test func fractionUsesPointsOwnDay() throws {
        let today = try series("pvpc-2026-10-17.json").points
        let tomorrow = today.map { PricePoint(start: $0.start.addingTimeInterval(86_400), duration: 3600, eurPerMWh: 100) }
        let s = PriceSeries(points: today + tomorrow, granularity: .hourly, fetchedAt: Date())
        #expect(s.fraction(of: tomorrow[3]) == 0.5)
        #expect(s.fraction(of: today[0]) == 1)
    }

    @Test func cheapestUpcoming() throws {
        let s = try series("pvpc-2026-10-17.json")
        let now = madrid(2026, 10, 17, 12, 30)
        let picks = s.cheapestUpcoming(from: now, count: 3)
        #expect(picks.count == 3)
        #expect(picks.allSatisfy { $0.start >= madrid(2026, 10, 17, 12) })
        #expect(picks == picks.sorted { $0.start < $1.start })
        let expected = s.points.filter { $0.start >= madrid(2026, 10, 17, 12) }
            .sorted { $0.eurPerMWh < $1.eurPerMWh }.prefix(3).map(\.start).sorted()
        #expect(picks.map(\.start) == expected)
    }

    @Test func cheapestUpcomingNearEndOfDay() throws {
        let s = try series("pvpc-2026-10-17.json")
        let picks = s.cheapestUpcoming(from: madrid(2026, 10, 17, 22, 30), count: 3)
        #expect(picks.map(\.start) == [madrid(2026, 10, 17, 22), madrid(2026, 10, 17, 23)])
        #expect(s.cheapestUpcoming(from: madrid(2026, 10, 18, 3), count: 3).isEmpty)
        #expect(s.cheapestUpcoming(from: madrid(2026, 10, 17, 12), count: 0).isEmpty)
    }
}
