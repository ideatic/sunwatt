import Foundation
import Testing
@testable import SunWattKit

/// Several solar plants: settings, secrets, per-plant caches, the loader, and switching the plant shown.
@Suite struct MultiPlantTests {
    private let now = madrid(2026, 6, 1, 12, 30)

    /// A second plant on SolarEdge.
    private static let roof = SolarPlant(id: "B8E3C1F2-0000-4000-8000-000000000001", name: "Roof", provider: .solarEdge,
                                         siteID: "1234567", apiKey: "ABCDEFGH12345678")

    private func twoPlants() -> SunWattSettings {
        SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, primaryPlantName: "Home", additionalPlants: [Self.roof])
    }

    private func snapshot(_ station: String, kW: Double, kWh: Double) -> SolarSnapshot {
        var s = storeSampleSolar(fetchedAt: now)
        s.stationName = station
        s.currentPowerKW = kW
        s.dailyEnergyKWh = kWh
        return s
    }

    /// A solar source per plant, told apart by provider (the loader puts the plant in the place of the first one).
    private func loader(_ store: AppGroupStore, home: Result<SolarSnapshot, SunWattError>, roof: Result<SolarSnapshot, SunWattError>,
                        at date: Date? = nil) -> SunWattLoader {
        let date = date ?? now
        return SunWattLoader(store: store, makeSolar: { settings in
            guard SolarAccount(settings: settings) != nil else { return nil }
            return FakeSolar(result: settings.solarProvider == .solarEdge ? roof : home)
        }, prices: FakePlain(result: .success(storeSamplePrices(fetchedAt: date))), now: { date })
    }

    @Test func settingsKeepTheFirstPlantInPlace() throws {
        var settings = twoPlants()
        #expect(settings.plants.map(\.id) == [SolarPlant.primaryID, Self.roof.id])
        #expect(settings.primaryPlant.kioskURL == testKioskURL && settings.primaryPlant.name == "Home")
        #expect(settings.hasSeveralPlants)
        var roof = Self.roof
        roof.name = "Barn"
        settings.updatePlant(roof)
        #expect(settings.plant(id: roof.id)?.name == "Barn")
        // The first plant is edited through the original fields.
        var first = settings.primaryPlant
        first.provider = .pvOutput
        settings.updatePlant(first)
        #expect(settings.solarProvider == .pvOutput)
        // Focusing puts a plant in the first place, for code written for one plant.
        let focused = settings.focused(on: roof)
        #expect(focused.solarProvider == .solarEdge && focused.solarSiteID == "1234567")
        #expect(SolarAccount(settings: focused) == SolarAccount(plant: roof))
        // Older settings without the new fields still decode; a single plant has no others.
        let old = try JSONDecoder().decode(SunWattSettings.self, from: Data(#"{"kioskURL":"x"}"#.utf8))
        #expect(old.additionalPlants.isEmpty && !old.hasSeveralPlants && old.primaryPlantName.isEmpty)
    }

    @Test func plantDescriptionsHideSecrets() {
        let text = "\(Self.roof) \(String(reflecting: Self.roof))"
        #expect(!text.contains("ABCDEFGH12345678"))
    }

    @Test func eachPlantIsLoadedAndCachedOnItsOwn() async {
        let (store, _) = storeTempStore()
        store.settings = twoPlants()
        let data = await loader(store, home: .success(snapshot("Calle", kW: 3, kWh: 10)), roof: .success(snapshot("Roof site", kW: 1.5, kWh: 4))).load()
        #expect(data.plantID == SolarPlant.primaryID && data.plantName == "Home")
        #expect(data.solar?.currentPowerKW == 3 && data.solarStatus == .fresh)
        #expect(data.otherPlants.map(\.name) == ["Roof"])
        #expect(data.otherPlants.first?.solar?.currentPowerKW == 1.5)
        #expect(data.totalPowerKW == 4.5 && data.totalDailyEnergyKWh == 14)
        // Separate caches: the first plant keeps its old file.
        #expect(store.loadSolar()?.currentPowerKW == 3)
        #expect(store.loadSolar(plantID: Self.roof.id)?.currentPowerKW == 1.5)
        // Cached data (placeholders) has both too.
        let cached = loader(store, home: .failure(.unavailable), roof: .failure(.unavailable)).cached()
        #expect(cached.otherPlants.first?.solar?.currentPowerKW == 1.5)
    }

    @Test func aFailingPlantDoesNotAffectTheOther() async {
        let (store, _) = storeTempStore()
        store.settings = twoPlants()
        let data = await loader(store, home: .success(snapshot("Calle", kW: 3, kWh: 10)), roof: .failure(.solarCredentialsRejected)).load()
        #expect(data.solarStatus == .fresh)
        #expect(data.otherPlants.first?.status == .error(message: SunWattError.solarCredentialsRejected.localizedDescription))
        // Without a user name the station name, else the provider, is shown.
        #expect(data.otherPlants.first?.name == "Roof")
        var settings = twoPlants()
        settings.primaryPlantName = ""
        store.settings = settings
        let unnamed = await loader(store, home: .success(snapshot("Calle", kW: 3, kWh: 10)), roof: .failure(.unavailable)).load()
        #expect(unnamed.plantName == "Calle")
    }

    @Test func onePlantReadsAsBefore() async {
        let (store, _) = storeTempStore()
        store.settings = SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, primaryPlantName: "Home")
        let data = await loader(store, home: .success(snapshot("Calle", kW: 3, kWh: 10)), roof: .failure(.unavailable)).load()
        #expect(data.plantName == nil && data.otherPlants.isEmpty && !data.hasSeveralPlants)
        #expect(data.showingPlant(at: 1) == data)
    }

    @Test func showingAnotherPlant() async {
        let (store, _) = storeTempStore()
        store.settings = twoPlants()
        let data = await loader(store, home: .success(snapshot("Calle", kW: 3, kWh: 10)), roof: .success(snapshot("Roof site", kW: 1.5, kWh: 4))).load()
        let second = data.showingPlant(at: 1)
        #expect(second.plantID == Self.roof.id && second.plantName == "Roof" && second.solar?.currentPowerKW == 1.5)
        #expect(second.otherPlants.map(\.id) == [SolarPlant.primaryID])
        #expect(data.showingPlant(at: 2) == data.showingPlant(at: 0))
        #expect(data.showingPlant(id: Self.roof.id) == second)
        // Prices and totals do not change with the plant shown.
        #expect(second.prices == data.prices && second.totalPowerKW == data.totalPowerKW)
    }

    @Test func sharedWindowNeedsEveryPlant() async {
        let (store, _) = storeTempStore()
        store.settings = twoPlants()
        let fresh = loader(store, home: .success(snapshot("Calle", kW: 3, kWh: 10)), roof: .success(snapshot("Roof site", kW: 1.5, kWh: 4)))
        _ = await fresh.load()
        // Within the window both plants come from the store, without fetching.
        let cachedOnly = loader(store, home: .failure(.unavailable), roof: .failure(.unavailable), at: now.addingTimeInterval(30))
        let data = await cachedOnly.load(maxCacheAge: SunWattLoader.sharedCacheWindow)
        #expect(data.solarStatus == .fresh && data.otherPlants.first?.status == .fresh)
    }

    @Test func secretsOfEveryPlantStayOutOfThePlist() throws {
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sunwatt-test-\(UUID())")
        let secrets = MemorySecrets()
        let store = AppGroupStore(defaults: defaults, directory: dir, secrets: secrets)
        store.settings = twoPlants()
        let raw = String(decoding: try #require(defaults.data(forKey: "sunwatt.settings")), as: UTF8.self)
        #expect(!raw.contains("ABCDEFGH12345678") && !raw.contains("TESTKK"))
        #expect(store.settings == twoPlants())
        // Removing the plant removes its key.
        var settings = twoPlants()
        settings.additionalPlants = []
        store.settings = settings
        #expect(secrets.secret("plant.\(Self.roof.id).apiKey") == nil)
    }

    @Test func failuresAndRejectionsArePerAccount() {
        let (store, _) = storeTempStore()
        store.noteSolarFailure(accountID: "a", at: now, message: "A")
        store.noteSolarFailure(accountID: "b", at: now, message: "B")
        #expect(store.solarFailure(accountID: "a")?.message == "A" && store.solarFailure(accountID: "b")?.message == "B")
        store.clearSolarFailure(accountID: "a")
        #expect(store.solarFailure(accountID: "a") == nil && store.solarFailure(accountID: "b") != nil)
        store.noteKioskRejected(linkID: "x", at: now)
        store.noteKioskRejected(linkID: "y", at: now.addingTimeInterval(60))
        store.clearKioskRejection(linkID: "x")
        #expect(store.kioskRejectedSince(linkID: "x") == nil && store.kioskRejectedSince(linkID: "y") == now.addingTimeInterval(60))
    }
}
