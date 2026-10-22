import Foundation
import Testing
@testable import SunWattKit

private func hourly(from start: Date, hours: Int, region: PriceRegion = .spain, price: (Int) -> Double) -> PriceSeries {
    let points = (0..<hours).map { PricePoint(start: start.addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: price($0)) }
    return PriceSeries(points: points, granularity: .hourly, fetchedAt: start, region: region)
}

@Suite struct PriceContextsTests {
    @Test func matchesPerPointCallsOnATwoDayChart() {
        // Two different price levels per day, so a context mixed up between days would show.
        let series = hourly(from: madrid(2026, 6, 1, 0), hours: 48) { $0 < 24 ? 50 + Double($0 * 7) : 400 + Double(($0 - 24) * 3) }
        let contexts = PriceDayContexts(series: series, points: series.points)
        #expect(contexts.dayCount == 2)
        for point in series.points {
            #expect(contexts.fraction(of: point) == series.fraction(of: point))
            #expect(contexts.level(of: point) == series.level(of: point))
        }
    }

    @Test func matchesOnTheFallBackDayWith25Hours() {
        let series = hourly(from: madrid(2026, 10, 25, 0), hours: 25 + 24) { Double(($0 * 37) % 90) + 20 }
        let contexts = PriceDayContexts(series: series, points: series.points)
        #expect(contexts.dayCount == 2)
        for point in series.points {
            #expect(contexts.fraction(of: point) == series.fraction(of: point))
        }
    }

    @Test func oneContextPerDayNotPerPoint() {
        let series = hourly(from: madrid(2026, 6, 1, 0), hours: 24) { Double($0) }
        let contexts = PriceDayContexts(series: series, points: series.points)
        #expect(contexts.dayCount == 1)
        // A window around now that crosses midnight needs two.
        let around = series.hours(around: madrid(2026, 6, 1, 20), before: 6, after: 12)
        let more = hourly(from: madrid(2026, 6, 1, 0), hours: 48) { Double($0) }
        #expect(PriceDayContexts(series: more, points: more.hours(around: madrid(2026, 6, 1, 20), before: 6, after: 12)).dayCount == 2)
        #expect(!around.isEmpty)
    }

    @Test func flatDayAndUnknownDayGiveTheMiddle() {
        let flat = hourly(from: madrid(2026, 6, 1, 0), hours: 24) { _ in 100 }
        let contexts = PriceDayContexts(series: flat, points: flat.points)
        #expect(contexts.fraction(of: flat.points[5]) == 0.5)
        let outside = PricePoint(start: madrid(2026, 7, 1, 10), duration: 3600, eurPerMWh: 999)
        #expect(contexts.fraction(of: outside) == 0.5)
        #expect(contexts.fraction(of: outside) == flat.fraction(of: outside))
        #expect(PriceDayContexts(series: flat, points: []).dayCount == 0)
    }

    @Test func otherRegionsUseTheirOwnCalendar() {
        // Lisbon midnight is one hour after Madrid midnight in summer.
        let lisbon = PriceRegion.portugal.calendar
        let start = lisbon.startOfDay(for: madrid(2026, 6, 1, 12))
        let series = hourly(from: start, hours: 48, region: .portugal) { $0 < 24 ? Double($0) : 200 - Double($0) }
        let contexts = PriceDayContexts(series: series, points: series.points)
        for point in series.points { #expect(contexts.fraction(of: point) == series.fraction(of: point)) }
    }
}
