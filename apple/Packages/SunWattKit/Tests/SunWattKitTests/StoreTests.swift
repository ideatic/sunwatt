import Foundation
import Testing
@testable import SunWattKit

func storeTempStore() -> (AppGroupStore, URL) {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sunwatt-test-\(UUID())", isDirectory: true)
    let defaults = UserDefaults(suiteName: "test-\(UUID())")!
    return (AppGroupStore(defaults: defaults, directory: dir), dir)
}

/// A valid kiosk link used by the loader tests (the token is fake).
let testKioskURL = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=TESTKK"
/// What `SunWattLoader` stores in `SolarSnapshot.endpointID` for `testKioskURL`.
let testKioskID = KioskEndpoint(parsing: testKioskURL)!.cacheID

func storeSampleSolar(fetchedAt: Date = madrid(2026, 6, 1, 12, 0), endpointID: String? = testKioskID) -> SolarSnapshot {
    SolarSnapshot(
        stationName: "Casa", currentPowerKW: 2.5, dailyEnergyKWh: 10.2,
        curve: [CurvePoint(time: madrid(2026, 6, 1, 11, 55), kW: 2.4), CurvePoint(time: madrid(2026, 6, 1, 12, 0), kW: nil)],
        fetchedAt: fetchedAt, dataAsOf: madrid(2026, 6, 1, 11, 55), endpointID: endpointID)
}

func storeSamplePrices(day: Date = madrid(2026, 6, 1, 0, 0), fetchedAt: Date = madrid(2026, 6, 1, 12, 0)) -> PriceSeries {
    let points = (0..<24).map { PricePoint(start: day.addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: 100 + Double($0)) }
    return PriceSeries(points: points, granularity: .hourly, fetchedAt: fetchedAt, sourceUpdatedAt: day)
}

@Suite struct StoreTests {
    @Test func settingsDefaultAndRoundTrip() {
        let (store, _) = storeTempStore()
        #expect(store.settings == SunWattSettings())
        store.settings = SunWattSettings(kioskURL: "https://x/y?kk=1", showTomorrowPrices: false)
        #expect(store.settings.kioskURL == "https://x/y?kk=1")
        #expect(store.settings.showTomorrowPrices == false)
    }

    @Test func corruptSettingsGiveDefault() {
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        defaults.set(Data("nope".utf8), forKey: "sunwatt.settings")
        let store = AppGroupStore(defaults: defaults, directory: FileManager.default.temporaryDirectory.appendingPathComponent("x-\(UUID())"))
        #expect(store.settings == SunWattSettings())
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

/// Mac builds before the team-prefixed App Group kept settings in the app's private plist; the widget saw none.
@Suite struct AdoptLocalSettingsTests {
    @Test func movesPrivateSettingsAndSecretsIntoTheSharedStore() throws {
        let local = UserDefaults(suiteName: "test-\(UUID())")!
        let old = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false)
        local.set(try JSONEncoder().encode(old), forKey: "sunwatt.settings")
        let secrets = MemorySecrets()
        let shared = UserDefaults(suiteName: "test-\(UUID())")!
        let store = AppGroupStore(defaults: shared, directory: FileManager.default.temporaryDirectory
            .appendingPathComponent("adopt-\(UUID())"), secrets: secrets)

        store.adoptSettings(from: local)

        #expect(store.settings.kioskURL == testKioskURL)
        #expect(store.settings.showTomorrowPrices == false)
        #expect(local.data(forKey: "sunwatt.settings") == nil)
        let plist = try #require(shared.data(forKey: "sunwatt.settings"))
        #expect(!String(decoding: plist, as: UTF8.self).contains("TESTKK"))
    }

    @Test func keepsSharedSettingsThatAlreadyExist() throws {
        let local = UserDefaults(suiteName: "test-\(UUID())")!
        local.set(try JSONEncoder().encode(SunWattSettings(kioskURL: "https://old.test/?kk=OLD")), forKey: "sunwatt.settings")
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL)

        store.adoptSettings(from: local)

        #expect(store.settings.kioskURL == testKioskURL)
        #expect(local.data(forKey: "sunwatt.settings") != nil)
    }
}

@Suite struct MoveSecretsToKeychainTests {
    @Test func secretInThePlistMovesAtLaunch() throws {
        let shared = UserDefaults(suiteName: "test-\(UUID())")!
        shared.set(try JSONEncoder().encode(SunWattSettings(kioskURL: testKioskURL)), forKey: "sunwatt.settings")
        let secrets = MemorySecrets()
        let store = AppGroupStore(defaults: shared, directory: FileManager.default.temporaryDirectory
            .appendingPathComponent("move-\(UUID())"), secrets: secrets)

        store.moveSecretsToKeychain()

        #expect(secrets.secret("kioskURL") == testKioskURL)
        let plist = try #require(shared.data(forKey: "sunwatt.settings"))
        #expect(!String(decoding: plist, as: UTF8.self).contains("TESTKK"))
        #expect(store.settings.kioskURL == testKioskURL)
    }

    @Test func withoutKeychainTheSecretStaysReadable() throws {
        let shared = UserDefaults(suiteName: "test-\(UUID())")!
        shared.set(try JSONEncoder().encode(SunWattSettings(kioskURL: testKioskURL)), forKey: "sunwatt.settings")
        let store = AppGroupStore(defaults: shared, directory: FileManager.default.temporaryDirectory
            .appendingPathComponent("move-\(UUID())"), secrets: MemorySecrets(works: false))

        store.moveSecretsToKeychain()

        #expect(store.settings.kioskURL == testKioskURL)
    }
}
