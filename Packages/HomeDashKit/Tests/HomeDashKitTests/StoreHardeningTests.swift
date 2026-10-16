import Foundation
import Testing
@testable import HomeDashKit

@Suite struct SettingsRegionTests {
    @Test func priceRegionRoundTrip() {
        let (store, _) = storeTempStore()
        store.settings = HomeDashSettings(kioskURL: "https://x/y?kk=1", showTomorrowPrices: false, priceRegion: .portugal)
        #expect(store.settings.priceRegion == .portugal)
        #expect(store.settings == HomeDashSettings(kioskURL: "https://x/y?kk=1", showTomorrowPrices: false, priceRegion: .portugal))
        for region in PriceRegion.allCases {
            let data = try! JSONEncoder().encode(HomeDashSettings(priceRegion: region))
            #expect(try! JSONDecoder().decode(HomeDashSettings.self, from: data).priceRegion == region)
        }
    }

    @Test func oldSettingsWithoutRegionDecodeAsSpain() throws {
        let old = Data(#"{"kioskURL":"https://x/y?kk=1","showTomorrowPrices":false}"#.utf8)
        let s = try JSONDecoder().decode(HomeDashSettings.self, from: old)
        #expect(s.priceRegion == .spain)
        #expect(s.kioskURL == "https://x/y?kk=1")
        #expect(s.showTomorrowPrices == false)

        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        defaults.set(old, forKey: "homedash.settings")
        let store = AppGroupStore(defaults: defaults, directory: FileManager.default.temporaryDirectory.appendingPathComponent("x-\(UUID())"))
        #expect(store.settings.priceRegion == .spain)
    }

    @Test func oldPriceSeriesWithoutRegionDecodeAsSpain() throws {
        let series = storeSamplePrices()
        let encoder = JSONEncoder()
        var object = try #require(JSONSerialization.jsonObject(with: encoder.encode(series)) as? [String: Any])
        object.removeValue(forKey: "region")
        let data = try JSONSerialization.data(withJSONObject: object)
        #expect(try JSONDecoder().decode(PriceSeries.self, from: data).region == .spain)
    }
}

@Suite struct StoreWriteTests {
    @Test func olderSnapshotDoesNotReplaceNewerOne() {
        let (store, _) = storeTempStore()
        let newer = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12))
        let older = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 11))
        store.saveSolar(newer)
        store.saveSolar(older)
        #expect(store.loadSolar() == newer)
        let newest = storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 13))
        store.saveSolar(newest)
        #expect(store.loadSolar() == newest)
        store.saveSolar(newest) // equal timestamp is accepted
        #expect(store.loadSolar() == newest)

        let p1 = storeSamplePrices(fetchedAt: madrid(2026, 6, 1, 12))
        let p0 = storeSamplePrices(fetchedAt: madrid(2026, 6, 1, 9))
        store.savePrices(p1)
        store.savePrices(p0)
        #expect(store.loadPrices() == p1)
    }

    @Test func corruptExistingFileIsReplaced() throws {
        let (store, dir) = storeTempStore()
        store.savePrices(storeSamplePrices())
        try Data("garbage".utf8).write(to: dir.appendingPathComponent("prices.json"))
        let fresh = storeSamplePrices(fetchedAt: madrid(2026, 6, 1, 13))
        store.savePrices(fresh)
        #expect(store.loadPrices() == fresh)
    }

    @Test func snapshotsLiveInTheGivenDirectory() throws {
        let (store, dir) = storeTempStore()
        store.saveSolar(storeSampleSolar())
        store.savePrices(storeSamplePrices())
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        #expect(names == ["prices.json", "solar.json"])
    }

    @Test func unwritableDirectoryDoesNotCrash() {
        let store = AppGroupStore(defaults: UserDefaults(suiteName: "test-\(UUID())")!, directory: URL(fileURLWithPath: "/dev/null/nope"))
        store.saveSolar(storeSampleSolar())
        #expect(store.loadSolar() == nil)
    }

    @Test func concurrentSavesStayConsistent() async {
        let (store, _) = storeTempStore()
        await withTaskGroup(of: Void.self) { group in
            for i in 0..<20 {
                group.addTask { store.saveSolar(storeSampleSolar(fetchedAt: madrid(2026, 6, 1, 12).addingTimeInterval(Double(i)))) }
            }
        }
        #expect(store.loadSolar()?.fetchedAt == madrid(2026, 6, 1, 12).addingTimeInterval(19))
    }
}
