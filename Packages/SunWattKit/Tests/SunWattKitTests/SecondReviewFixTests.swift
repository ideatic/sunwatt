import Foundation
import Testing
@testable import SunWattKit

/// Fixes from the second code review (2026-10-18), one test per finding.

/// Keychain stand-in whose writes can start failing (like errSecInteractionNotAllowed) and that counts reads.
final class FlakySecrets: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String: String] = [:]
    private var reads = 0
    var failWrites = false
    var readCount: Int { lock.withLock { reads } }
    func secret(_ name: String) -> String? { lock.withLock { reads += 1; return values[name] } }
    func setSecret(_ value: String, for name: String) -> Bool {
        lock.withLock {
            guard !failWrites else { return false }
            values[name] = value.isEmpty ? nil : value
            return true
        }
    }
    func removeAllSecrets() { lock.withLock { values.removeAll() } }
}

private func flakyStore(_ secrets: FlakySecrets, defaults: UserDefaults = UserDefaults(suiteName: "test-\(UUID())")!) -> AppGroupStore {
    AppGroupStore(defaults: defaults, directory: FileManager.default.temporaryDirectory.appendingPathComponent("flaky-\(UUID())"),
                  secrets: secrets)
}

@Suite struct SecondReviewFixTests {
    // 1: settings with a region this build does not know keep everything else.
    @Test func unknownRegionKeepsTheOtherSettings() throws {
        var json = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(
            SunWattSettings(kioskURL: testKioskURL, showTomorrowPrices: false, onboardingCompleted: true))) as? [String: Any])
        json["priceRegion"] = "atlantis"
        let settings = try JSONDecoder().decode(SunWattSettings.self, from: JSONSerialization.data(withJSONObject: json))
        #expect(settings.priceRegion == .spain)
        #expect(settings.onboardingCompleted && !settings.showTomorrowPrices && settings.kioskURL == testKioskURL)
    }

    // 2: a keychain write that fails does not let the older keychain value win.
    @Test func failedKeychainWriteKeepsTheNewValue() {
        let secrets = FlakySecrets()
        let store = flakyStore(secrets)
        store.settings = SunWattSettings(kioskURL: testKioskURL)
        #expect(secrets.secret("kioskURL") == testKioskURL)

        secrets.failWrites = true
        let newLink = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=NEWKK"
        store.settings = SunWattSettings(kioskURL: newLink)
        #expect(store.settings.kioskURL == newLink)

        // Clearing while the keychain refuses the delete: the link stays cleared.
        store.settings = SunWattSettings(kioskURL: "")
        #expect(store.settings.kioskURL.isEmpty)
    }

    // 2: another process (app vs. widget) that saves a new secret is seen, even though the plist bytes stay the same.
    @Test func secretSavedByAnotherStoreIsSeen() {
        let secrets = FlakySecrets()
        let defaults = UserDefaults(suiteName: "test-\(UUID())")!
        let app = flakyStore(secrets, defaults: defaults)
        let widget = flakyStore(secrets, defaults: defaults)
        app.settings = SunWattSettings(kioskURL: testKioskURL)
        #expect(widget.settings.kioskURL == testKioskURL)
        let newLink = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=NEWKK"
        app.settings = SunWattSettings(kioskURL: newLink)
        #expect(widget.settings.kioskURL == newLink)
    }

    // 3: secrets left in the keychain by an earlier install are not used and are deleted at launch.
    @Test func secretsOfAnEarlierInstallAreForgotten() {
        let secrets = FlakySecrets()
        _ = secrets.setSecret(testKioskURL, for: "kioskURL")
        let store = flakyStore(secrets)
        #expect(store.settings.kioskURL.isEmpty)
        store.forgetSecretsOfEarlierInstall()
        #expect(secrets.secret("kioskURL") == nil)
    }

    @Test func secretsOfThisInstallStay() {
        let secrets = FlakySecrets()
        let store = flakyStore(secrets)
        store.settings = SunWattSettings(kioskURL: testKioskURL)
        store.forgetSecretsOfEarlierInstall()
        #expect(store.settings.kioskURL == testKioskURL)
    }

    // 7: reading the settings again does not ask the keychain again while nothing changed.
    @Test func settingsReadsReuseTheSecrets() {
        let secrets = FlakySecrets()
        let store = flakyStore(secrets)
        var settings = SunWattSettings(kioskURL: testKioskURL)
        settings.additionalPlants = [SolarPlant(), SolarPlant()]
        store.settings = settings
        _ = store.settings
        let reads = secrets.readCount
        for _ in 0..<5 { _ = store.settings }
        #expect(secrets.readCount == reads)
        #expect(store.settings.kioskURL == testKioskURL)
    }
}

/// 4: the autumn DST day repeats 02:00-02:59 (Madrid, 2026-10-25). Each reading gets its own instant.
@Suite struct RepeatedHourTests {
    private var madridCal: Calendar { Constants.madridCalendar }
    private let fetchedAt = madrid(2026, 10, 25, 12)
    /// Local times as the provider sends them, oldest first: 01:45, 02:00 … 02:45, 02:00 … 02:45, 03:00.
    private let wallTimes = ["01:45"] + ["02:00", "02:15", "02:30", "02:45"] + ["02:00", "02:15", "02:30", "02:45"] + ["03:00"]

    private func expectStrictlyIncreasing(_ times: [Date]) {
        #expect(times.count == wallTimes.count)
        #expect(zip(times, times.dropFirst()).allSatisfy { $0 < $1 })
        // 01:45 to 03:00 is 2 h 15 min of real time on this day.
        if let first = times.first, let last = times.last { #expect(last.timeIntervalSince(first) == 2.25 * 3600) }
    }

    @Test func solarEdgeCurve() throws {
        let values = wallTimes.enumerated().map { ["date": "2026-10-25 \($0.element):00", "value": Double($0.offset)] as [String: Any] }
        let power = try JSONSerialization.data(withJSONObject: ["power": ["unit": "W", "values": values]])
        let overview = Data(#"{"overview":{}}"#.utf8)
        let snapshot = try SolarEdgeClient.parse(overview: overview, power: power, fetchedAt: fetchedAt, calendar: madridCal)
        expectStrictlyIncreasing(snapshot.curve.map(\.time))
        // The second 02:00 reading keeps its own value.
        #expect(snapshot.curve[5].kW == 5 * 0.001)
    }

    @Test func pvOutputHistory() throws {
        let body = wallTimes.enumerated().map { "20261025,\($0.element),\($0.offset * 10),NaN,\($0.offset * 100)" }.joined(separator: ";")
        let snapshot = try PVOutputClient.parse(Data(body.utf8), fetchedAt: fetchedAt, calendar: madridCal)
        expectStrictlyIncreasing(snapshot.curve.map(\.time))
    }

    @Test func realDuplicatesAreDropped() {
        let t = madrid(2026, 6, 1, 12)
        let points = [CurvePoint(time: t, kW: 1), CurvePoint(time: t, kW: 2), CurvePoint(time: t.addingTimeInterval(900), kW: 3)]
        let result = SolarLocalTime.inOrder(points, time: \.time, moving: { CurvePoint(time: $1, kW: $0.kW) }, calendar: madridCal)
        #expect(result.map(\.kW) == [1, 3])
    }
}
