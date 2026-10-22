import Foundation
import Testing
@testable import SunWattKit

@Suite struct WatchPagesTests {
    @Test func solarFirstWhenSetUp() {
        let data = SunWattData(solar: nil, prices: nil, solarStatus: .fresh, priceStatus: .fresh)
        #expect(WatchRootView.firstPage(data) == .solar(plantID: SolarPlant.primaryID))
    }

    @Test func priceFirstWithoutSolar() {
        let data = SunWattData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .fresh)
        #expect(WatchRootView.firstPage(data) == .price)
    }

    @Test func firstPlantFirstWithSeveral() {
        var data = SunWattData(solar: nil, prices: nil, solarStatus: .notConfigured, priceStatus: .fresh)
        data.setPlants([
            PlantSolarData(id: SolarPlant.primaryID, name: "Home", solar: nil, status: .notConfigured),
            PlantSolarData(id: "second", name: "Barn", solar: nil, status: .fresh),
        ])
        #expect(WatchRootView.firstPage(data) == .solar(plantID: SolarPlant.primaryID))
    }

    @Test func tapPositionPicksTheBarUnderIt() {
        let points = PriceModel(entry: SampleData.entry(.day), window: .wholeDay).points
        #expect(points.count == 24)
        #expect(WatchPricePage.point(atFraction: 0, of: points) == points.first)
        #expect(WatchPricePage.point(atFraction: 0.5, of: points) == points[12])
        #expect(WatchPricePage.point(atFraction: 1, of: points) == points.last)
        #expect(WatchPricePage.point(atFraction: -0.2, of: points) == points.first)
        #expect(WatchPricePage.point(atFraction: 0.5, of: []) == nil)
    }

    @Test func tappedHourReplacesTheCurrentOne() throws {
        let model = PriceModel(entry: SampleData.entry(.day), window: .wholeDay)
        let tapped = try #require(model.points.max { $0.eurPerKWh < $1.eurPerKWh })
        let shown = model.showing(tapped)
        #expect(shown.current == tapped)
        #expect(shown.level == .high)
        #expect((shown.fraction ?? 0) > 2.0 / 3) // the day's top third (statistics may come from 15-minute prices)
        #expect(shown.points == model.points && shown.staleSince == model.staleSince)
    }

    @Test func tapOnTheCurvePicksTheNearestMeasuredPoint() throws {
        let entry = SampleData.entry(.day)
        let curve = try #require(entry.data.solar?.curve)
        let domain = ChartLayout.solarDomain(curve: curve, now: entry.date)
        let measured = curve.filter { $0.kW != nil && domain.contains($0.time) }
        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        // A tap at 10:00 picks the point measured nearest to 10:00.
        let tenOClock = try #require(Constants.madridCalendar.date(bySettingHour: 10, minute: 0, second: 0, of: entry.date))
        let fraction = tenOClock.timeIntervalSince(domain.lowerBound) / span
        let point = try #require(WatchSolarPage.point(atFraction: fraction, curve: curve, now: entry.date))
        #expect(abs(point.time.timeIntervalSince(tenOClock)) <= 5 * 60)
        #expect(point.kW != nil)
        // Left edge: the first point the chart draws.
        #expect(WatchSolarPage.point(atFraction: 0, curve: curve, now: entry.date) == measured.first)
    }

    @Test func tapAfterTheLastMeasurementSelectsNothing() throws {
        let entry = SampleData.entry(.day)
        let curve = try #require(entry.data.solar?.curve)
        // The day sample ends at 13:30; the right edge is the evening.
        #expect(WatchSolarPage.point(atFraction: 1, curve: curve, now: entry.date) == nil)
        #expect(WatchSolarPage.point(atFraction: 0.5, curve: [], now: entry.date) == nil)
    }
}
