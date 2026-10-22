import Testing
import Foundation
@testable import SunWattKit

@Test func fixturesLoad() throws {
    #expect(try Fixture.data("kiosk-night.json").count > 1000)
    #expect(try Fixture.data("pvpc-2026-10-17.json").count > 1000)
}

@Test func priceLevelClassification() {
    #expect(PriceLevel.classify(fraction: 0.0) == .low)
    #expect(PriceLevel.classify(fraction: 0.5) == .medium)
    #expect(PriceLevel.classify(fraction: 0.9) == .high)
}
