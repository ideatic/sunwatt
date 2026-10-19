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
}
