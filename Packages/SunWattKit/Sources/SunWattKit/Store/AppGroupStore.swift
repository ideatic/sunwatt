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

    /// `defaults`, `directory` and `secrets` are injectable for tests. With injected defaults and directory and no
    /// `secrets`, secrets stay in `defaults`; the shared store uses the keychain (App Group access group).
    public init(defaults: UserDefaults? = nil, directory: URL? = nil, secrets: SecretStore? = nil) {
        if let defaults, let directory {
            self.defaults = defaults
            self.directory = directory
            self.secrets = secrets
        } else {
            let location = Self.defaultLocation()
            self.defaults = defaults ?? location.defaults
            self.directory = directory ?? location.directory
            self.secrets = secrets ?? KeychainSecretStore(accessGroup: Constants.appGroupID)
        }
    }

    private static let kioskSecret = "kioskURL"
    private static let apiKeySecret = "solarAPIKey"
    /// Secrets of the further plants, one pair per plant id.
    private static func plantKioskSecret(_ id: String) -> String { "plant.\(id).kioskURL" }
    private static func plantAPIKeySecret(_ id: String) -> String { "plant.\(id).apiKey" }

    /// Group defaults + group directory when the container exists, standard defaults + Application Support otherwise.
    private static func defaultLocation() -> (defaults: UserDefaults, directory: URL) {
        let fm = FileManager.default
        if let container = fm.containerURL(forSecurityApplicationGroupIdentifier: Constants.appGroupID),
           let groupDefaults = UserDefaults(suiteName: Constants.appGroupID) {
            return (groupDefaults, container.appendingPathComponent("Library/Application Support/SunWatt", isDirectory: true))
        }
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? fm.temporaryDirectory
        return (.standard, support.appendingPathComponent("SunWatt", isDirectory: true))
    }

    /// Settings from `defaults`, with the kiosk link and the API key from the keychain. A secret the keychain does
    /// not have (older installs, or a build that cannot use it) is read from `defaults`.
    public var settings: SunWattSettings {
        get {
            lock.lock(); defer { lock.unlock() }
            var value = defaults.data(forKey: Self.settingsKey)
                .flatMap { try? JSONDecoder().decode(SunWattSettings.self, from: $0) } ?? SunWattSettings()
            if let secrets {
                if let kiosk = secrets.secret(Self.kioskSecret) { value.kioskURL = kiosk }
                if let key = secrets.secret(Self.apiKeySecret) { value.solarAPIKey = key }
                for index in value.additionalPlants.indices {
                    let id = value.additionalPlants[index].id
                    if let kiosk = secrets.secret(Self.plantKioskSecret(id)) { value.additionalPlants[index].kioskURL = kiosk }
                    if let key = secrets.secret(Self.plantAPIKeySecret(id)) { value.additionalPlants[index].apiKey = key }
                }
            }
            return value
        }
        set {
            lock.lock(); defer { lock.unlock() }
            var stored = newValue
            // Saved in the keychain: blank in the plist. Otherwise (no keychain access) the plist keeps it.
            if let secrets {
                if secrets.setSecret(newValue.kioskURL, for: Self.kioskSecret) { stored.kioskURL = "" }
                if secrets.setSecret(newValue.solarAPIKey, for: Self.apiKeySecret) { stored.solarAPIKey = "" }
                for index in stored.additionalPlants.indices {
                    let plant = stored.additionalPlants[index]
                    if secrets.setSecret(plant.kioskURL, for: Self.plantKioskSecret(plant.id)) { stored.additionalPlants[index].kioskURL = "" }
                    if secrets.setSecret(plant.apiKey, for: Self.plantAPIKeySecret(plant.id)) { stored.additionalPlants[index].apiKey = "" }
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
            guard let data = try? JSONEncoder().encode(stored) else { return }
            defaults.set(data, forKey: Self.settingsKey)
        }
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
