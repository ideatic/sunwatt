import Foundation
import os

/// Settings and last-good data shared by the app and the widget extension.
/// With an App Group container: settings in `UserDefaults(suiteName: Constants.appGroupID)`, snapshots as JSON files in
/// `<container>/Library/Application Support/SunWatt`. Without it (unsigned macOS builds) both fall back together to
/// `UserDefaults.standard` and the user's Application Support directory.
/// Saves never replace a snapshot with an older one (the app and the widget extension write concurrently).
public final class AppGroupStore: @unchecked Sendable {
    public static let shared = AppGroupStore()

    private static let settingsKey = "sunwatt.settings"
    private static let log = Logger(subsystem: "com.ideatic.sunwatt", category: "store")

    private let defaults: UserDefaults
    private let directory: URL
    /// Keeps the kiosk link and the API key out of the settings plist. Nil: everything in `defaults` (tests).
    private let secrets: SecretStore?
    private let lock = NSLock()
    /// False when the App Group container could not be opened and the store uses the private fallback: then the app
    /// and its widgets do not see each other's settings (DECISIONS D4).
    public let usesAppGroup: Bool

    /// `defaults`, `directory` and `secrets` are injectable for tests. With injected defaults and directory and no
    /// `secrets`, secrets stay in `defaults`; the shared store uses the keychain (App Group access group).
    public init(defaults: UserDefaults? = nil, directory: URL? = nil, secrets: SecretStore? = nil) {
        if let defaults, let directory {
            self.defaults = defaults
            self.directory = directory
            self.secrets = secrets
            self.usesAppGroup = false
        } else {
            let location = Self.defaultLocation()
            self.defaults = defaults ?? location.defaults
            self.directory = directory ?? location.directory
            self.secrets = secrets ?? KeychainSecretStore(accessGroup: Constants.appGroupID)
            self.usesAppGroup = location.shared
        }
    }

    private static let kioskSecret = "kioskURL"
    private static let apiKeySecret = "solarAPIKey"
    /// Secrets of the further plants, one pair per plant id.
    private static func plantKioskSecret(_ id: String) -> String { "plant.\(id).kioskURL" }
    private static func plantAPIKeySecret(_ id: String) -> String { "plant.\(id).apiKey" }

    /// Group defaults + group directory when the container exists, standard defaults + Application Support otherwise.
    private static func defaultLocation() -> (defaults: UserDefaults, directory: URL, shared: Bool) {
        let fm = FileManager.default
        if let container = fm.containerURL(forSecurityApplicationGroupIdentifier: Constants.appGroupID),
           let groupDefaults = UserDefaults(suiteName: Constants.appGroupID) {
            return (groupDefaults, container.appendingPathComponent("Library/Application Support/SunWatt", isDirectory: true), true)
        }
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        return (.standard, support.appendingPathComponent("SunWatt", isDirectory: true), false)
    }

    /// Which secrets are in the keychain now, written by every settings save. A secret that is not listed is read from
    /// the plist, so a failed keychain write or delete never lets an older keychain value win (DECISIONS D27).
    /// `revision` changes with every save: other processes see that their cached secrets are out of date.
    /// Missing (settings saved before it existed): every secret the keychain has wins, as before.
    private struct KeychainIndex: Codable, Equatable {
        var names: [String]
        var revision: String
    }
    private static let keychainIndexKey = "sunwatt.keychainSecrets"

    /// Secrets read for these stored bytes and this index revision; reused while neither changes (each keychain read is
    /// a round trip to securityd, and the settings are read many times per load).
    private var secretCache: (settings: Data, revision: String?, values: [String: String])?

    /// The secret fields of `settings`: keychain item name and where the value lives.
    private static func secretSlots(_ settings: SunWattSettings) -> [(name: String, path: WritableKeyPath<SunWattSettings, String>)] {
        var slots: [(name: String, path: WritableKeyPath<SunWattSettings, String>)] = [
            (kioskSecret, \.kioskURL), (apiKeySecret, \.solarAPIKey),
        ]
        for index in settings.additionalPlants.indices {
            let id = settings.additionalPlants[index].id
            slots.append((plantKioskSecret(id), \.additionalPlants[index].kioskURL))
            slots.append((plantAPIKeySecret(id), \.additionalPlants[index].apiKey))
        }
        return slots
    }

    private func keychainIndexLocked() -> KeychainIndex? {
        defaults.data(forKey: Self.keychainIndexKey).flatMap { try? JSONDecoder().decode(KeychainIndex.self, from: $0) }
    }

    /// Settings from `defaults`, with the kiosk link and the API keys from the keychain where the last save put them.
    /// No stored settings: defaults without secrets (keychain items outlive an uninstall; they are not this install's).
    public var settings: SunWattSettings {
        get {
            lock.lock(); defer { lock.unlock() }
            guard let data = defaults.data(forKey: Self.settingsKey),
                  var value = try? JSONDecoder().decode(SunWattSettings.self, from: data) else { return SunWattSettings() }
            guard let secrets else { return value }
            let index = keychainIndexLocked()
            if let cache = secretCache, cache.settings == data, cache.revision == index?.revision {
                for slot in Self.secretSlots(value) { if let secret = cache.values[slot.name] { value[keyPath: slot.path] = secret } }
                return value
            }
            var values: [String: String] = [:]
            var complete = true
            for slot in Self.secretSlots(value) where index?.names.contains(slot.name) ?? true {
                if let secret = secrets.secret(slot.name) {
                    values[slot.name] = secret
                    value[keyPath: slot.path] = secret
                } else if index != nil {
                    // Listed but unreadable (keychain locked before the first unlock): read again next time.
                    complete = false
                }
            }
            secretCache = complete ? (data, index?.revision, values) : nil
            return value
        }
        set {
            lock.lock(); defer { lock.unlock() }
            var stored = newValue
            var index = KeychainIndex(names: [], revision: UUID().uuidString)
            // Saved in the keychain: blank in the plist and listed in the index. Otherwise (no keychain access) the
            // plist keeps it and the index leaves it out.
            if let secrets {
                for slot in Self.secretSlots(newValue) {
                    let secret = newValue[keyPath: slot.path]
                    guard secrets.setSecret(secret, for: slot.name) else { continue }
                    stored[keyPath: slot.path] = ""
                    if !secret.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { index.names.append(slot.name) }
                }
                // A removed plant's secrets go too.
                let kept = Set(newValue.additionalPlants.map(\.id))
                let previous = defaults.data(forKey: Self.settingsKey)
                    .flatMap { try? JSONDecoder().decode(SunWattSettings.self, from: $0) }?.additionalPlants ?? []
                for plant in previous where !kept.contains(plant.id) {
                    secrets.setSecret("", for: Self.plantKioskSecret(plant.id))
                    secrets.setSecret("", for: Self.plantAPIKeySecret(plant.id))
                }
            }
            guard let data = try? JSONEncoder().encode(stored), let indexData = try? JSONEncoder().encode(index) else { return }
            secretCache = nil
            // The index first: a reader between the two writes then looks in the keychain for a value just moved there.
            if secrets != nil { defaults.set(indexData, forKey: Self.keychainIndexKey) }
            defaults.set(data, forKey: Self.settingsKey)
        }
    }

    /// Deletes keychain secrets left by an earlier install. iOS removes the App Group (settings) with the app but keeps
    /// keychain items, so a reinstall would bring an old kiosk link or API key back. Call at app launch after
    /// `adoptSettings(from:)`; does nothing while settings exist.
    public func forgetSecretsOfEarlierInstall() {
        lock.lock(); defer { lock.unlock() }
        guard let secrets, defaults.data(forKey: Self.settingsKey) == nil else { return }
        secrets.removeAllSecrets()
        defaults.removeObject(forKey: Self.keychainIndexKey)
        secretCache = nil
    }

    /// Moves settings saved in the private fallback (`local`) into the shared store, once: Mac builds before the
    /// team-prefixed App Group could not open the group, so the app kept settings and secrets in its own plist and the
    /// widget saw none. Does nothing when the shared store already has settings or both are the same defaults.
    /// The app calls it at launch; the widget extension never does (its private defaults are empty).
    public func adoptSettings(from local: UserDefaults) {
        guard local !== defaults, defaults.data(forKey: Self.settingsKey) == nil,
              let data = local.data(forKey: Self.settingsKey),
              let old = try? JSONDecoder().decode(SunWattSettings.self, from: data)
        else { return }
        settings = old
        // The old plist may hold the kiosk link or API key in clear text: drop it.
        local.removeObject(forKey: Self.settingsKey)
    }

    /// Saves the settings again when the plist still holds a secret (older installs, or written while the keychain
    /// could not be used), so it moves to the keychain at launch instead of at the next save. No-op without keychain.
    public func moveSecretsToKeychain() {
        guard secrets != nil,
              let stored = defaults.data(forKey: Self.settingsKey)
                .flatMap({ try? JSONDecoder().decode(SunWattSettings.self, from: $0) }),
              !stored.kioskURL.isEmpty || !stored.solarAPIKey.isEmpty
                || stored.additionalPlants.contains(where: { !$0.kioskURL.isEmpty || !$0.apiKey.isEmpty })
        else { return }
        settings = settings
    }

    // MARK: Failed fetches of rate-limited providers

    private static let solarFailureKey = "sunwatt.solarFailures"
    private struct SolarFailure: Codable { var at: Date; var message: String }

    /// The last failed fetch of the solar account with this cache ID: when, and the message to show.
    public func solarFailure(accountID: String) -> (at: Date, message: String)? {
        lock.lock(); defer { lock.unlock() }
        guard let value = failuresLocked()[accountID] else { return nil }
        return (value.at, value.message)
    }

    public func noteSolarFailure(accountID: String, at date: Date, message: String) {
        lock.lock(); defer { lock.unlock() }
        var all = failuresLocked()
        all[accountID] = SolarFailure(at: date, message: message)
        if let data = try? JSONEncoder().encode(all) { defaults.set(data, forKey: Self.solarFailureKey) }
    }

    /// Forgets the failure of this account; nil: of every account.
    public func clearSolarFailure(accountID: String? = nil) {
        lock.lock(); defer { lock.unlock() }
        guard let accountID else { defaults.removeObject(forKey: Self.solarFailureKey); return }
        var all = failuresLocked()
        guard all.removeValue(forKey: accountID) != nil else { return }
        if let data = try? JSONEncoder().encode(all) { defaults.set(data, forKey: Self.solarFailureKey) }
    }

    private func failuresLocked() -> [String: SolarFailure] {
        defaults.data(forKey: Self.solarFailureKey).flatMap { try? JSONDecoder().decode([String: SolarFailure].self, from: $0) } ?? [:]
    }

    private static let kioskRejectionKey = "sunwatt.kioskRejections"

    /// Since when the kiosk link with this cache ID has been rejected without a success in between. Nil when it
    /// was not rejected.
    public func kioskRejectedSince(linkID: String) -> Date? {
        lock.lock(); defer { lock.unlock() }
        return rejectionsLocked()[linkID]
    }

    /// Records a rejection of the link; keeps the first time when it was already rejected.
    /// Returns the time the rejections started.
    @discardableResult
    public func noteKioskRejected(linkID: String, at date: Date) -> Date {
        lock.lock(); defer { lock.unlock() }
        var all = rejectionsLocked()
        if let since = all[linkID] { return since }
        all[linkID] = date
        if let data = try? JSONEncoder().encode(all) { defaults.set(data, forKey: Self.kioskRejectionKey) }
        return date
    }

    /// A successful fetch (or a new link) ends the rejection of this link; nil: of every link.
    public func clearKioskRejection(linkID: String? = nil) {
        lock.lock(); defer { lock.unlock() }
        guard let linkID else { defaults.removeObject(forKey: Self.kioskRejectionKey); return }
        var all = rejectionsLocked()
        guard all.removeValue(forKey: linkID) != nil else { return }
        if let data = try? JSONEncoder().encode(all) { defaults.set(data, forKey: Self.kioskRejectionKey) }
    }

    private func rejectionsLocked() -> [String: Date] {
        defaults.data(forKey: Self.kioskRejectionKey).flatMap { try? JSONDecoder().decode([String: Date].self, from: $0) } ?? [:]
    }

    /// The last good snapshot of a plant (`SolarPlant.id`; the first plant by default, in the file it always had).
    public func loadSolar(plantID: String = SolarPlant.primaryID) -> SolarSnapshot? {
        read(SolarSnapshot.self, file: Self.solarFile(plantID))
    }

    /// Skipped when the stored snapshot has a newer `fetchedAt`.
    public func saveSolar(_ snapshot: SolarSnapshot, plantID: String = SolarPlant.primaryID) {
        write(snapshot, file: Self.solarFile(plantID), fetchedAt: snapshot.fetchedAt)
    }

    private static func solarFile(_ plantID: String) -> String {
        guard plantID != SolarPlant.primaryID else { return "solar.json" }
        // Plant ids are UUIDs; anything else is reduced to safe file name characters.
        let safe = String(plantID.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) || $0 == "-" })
        return "solar-\(safe).json"
    }
    public func loadPrices() -> PriceSeries? { read(PriceSeries.self, file: "prices.json") }
    /// Skipped when the stored series has a newer `fetchedAt`.
    public func savePrices(_ series: PriceSeries) { write(series, file: "prices.json", fetchedAt: series.fetchedAt) }

    private static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }

    /// Caller holds the lock. A missing file is normal; a file that cannot be decoded is logged.
    private func readLocked<T: Decodable>(_ type: T.Type, file: String) -> T? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(file)) else { return nil }
        do {
            return try Self.decoder().decode(type, from: data)
        } catch {
            Self.log.error("Cannot decode \(file, privacy: .public)")
            return nil
        }
    }

    private func read<T: Decodable>(_ type: T.Type, file: String) -> T? {
        lock.lock(); defer { lock.unlock() }
        return readLocked(type, file: file)
    }

    private struct FetchedAtProbe: Decodable { var fetchedAt: Date }

    /// A stored `fetchedAt` this far in the future means the device clock went back: it no longer blocks writes.
    static let clockTolerance: TimeInterval = 10 * 60

    /// Writes unless the stored data is newer. The check and the write run under a file coordinator, so the app and
    /// the widget extension (separate processes) cannot interleave them.
    private func write<T: Encodable>(_ value: T, file: String, fetchedAt: Date) {
        lock.lock(); defer { lock.unlock() }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data: Data
        do {
            data = try encoder.encode(value)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            Self.log.error("Cannot write \(file, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return
        }
        let url = directory.appendingPathComponent(file)
        var coordinationError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: url, options: .forReplacing, error: &coordinationError) { target in
            if let stored = try? Data(contentsOf: target),
               let existing = try? Self.decoder().decode(FetchedAtProbe.self, from: stored).fetchedAt,
               existing > fetchedAt, existing <= Date().addingTimeInterval(Self.clockTolerance) {
                return
            }
            do {
                try data.write(to: target, options: .atomic)
            } catch {
                Self.log.error("Cannot write \(file, privacy: .public): \(error.localizedDescription, privacy: .public)")
            }
        }
        if let coordinationError {
            Self.log.error("Cannot coordinate \(file, privacy: .public): \(coordinationError.localizedDescription, privacy: .public)")
        }
    }
}
