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
        #expect(shown.fraction == 1)
        #expect(shown.points == model.points && shown.staleSince == model.staleSince)
    }
}
