import Foundation
import Testing
@testable import HomeDashKit

func storeTempStore() -> (AppGroupStore, URL) {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("homedash-test-\(UUID())", isDirectory: true)
    let defaults = UserDefaults(suiteName: "test-\(UUID())")!
    return (AppGroupStore(defaults: defaults, directory: dir), dir)
}

func storeSampleSolar(fetchedAt: Date = madrid(2026, 6, 1, 12, 0)) -> SolarSnapshot {
    SolarSnapshot(
        stationName: "Casa", currentPowerKW: 2.5, dailyEnergyKWh: 10.2,
        curve: [CurvePoint(time: madrid(2026, 6, 1, 11, 55), kW: 2.4), CurvePoint(time: madrid(2026, 6, 1, 12, 0), kW: nil)],
        fetchedAt: fetchedAt, dataAsOf: madrid(2026, 6, 1, 11, 55))
}

func storeSamplePrices(day: Date = madrid(2026, 6, 1, 0, 0), fetchedAt: Date = madrid(2026, 6, 1, 12, 0)) -> PriceSeries {
    let points = (0..<24).map { PricePoint(start: day.addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: 100 + Double($0)) }
    return PriceSeries(points: points, granularity: .hourly, fetchedAt: fetchedAt, sourceUpdatedAt: day)
}

@Suite struct StoreTests {
    @Test func settingsDefaultAndRoundTrip() {
        let (store, _) = storeTempStore()
        #expect(store.settings == HomeDashSettings())
        store.settings = HomeDashSettings(kioskURL: "https://x/y?kk=1", showTomorrowPrices: false)
        #expect(store.settings.kioskURL == "https://x/y?kk=1")
        #expect(store.settings.showTomorrowPrices == false)
    }

    @Test func corruptSettingsGiveDefault() {
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        defaults.set(Data("nope".utf8), forKey: "homedash.settings")
        let store = AppGroupStore(defaults: defaults, directory: FileManager.default.temporaryDirectory.appendingPathComponent("x-\(UUID())"))
        #expect(store.settings == HomeDashSettings())
    }

    @Test func solarRoundTrip() {
        let (store, _) = storeTempStore()
        #expect(store.loadSolar() == nil)
        let snap = storeSampleSolar()
        store.saveSolar(snap)
        #expect(store.loadSolar() == snap)
    }

    @Test func pricesRoundTrip() {
        let (store, _) = storeTempStore()
        #expect(store.loadPrices() == nil)
        let series = storeSamplePrices()
        store.savePrices(series)
        #expect(store.loadPrices() == series)
    }

    @Test func corruptFilesGiveNil() throws {
        let (store, dir) = storeTempStore()
        store.saveSolar(storeSampleSolar())
        store.savePrices(storeSamplePrices())
        try Data("garbage".utf8).write(to: dir.appendingPathComponent("solar.json"))
        try Data("{}".utf8).write(to: dir.appendingPathComponent("prices.json"))
        #expect(store.loadSolar() == nil)
        #expect(store.loadPrices() == nil)
    }

    @Test func defaultInitDoesNotCrash() {
        _ = AppGroupStore()
    }
}
