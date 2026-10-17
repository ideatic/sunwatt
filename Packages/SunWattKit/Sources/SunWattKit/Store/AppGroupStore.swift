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
            }
            guard let data = try? JSONEncoder().encode(stored) else { return }
            defaults.set(data, forKey: Self.settingsKey)
        }
    }

    // MARK: Failed fetches of rate-limited providers

    private static let solarFailureKey = "sunwatt.solarFailure"
    private struct SolarFailure: Codable { var accountID: String; var at: Date; var message: String }

    /// The last failed fetch of the solar account with this cache ID: when, and the message to show.
    public func solarFailure(accountID: String) -> (at: Date, message: String)? {
        lock.lock(); defer { lock.unlock() }
        guard let data = defaults.data(forKey: Self.solarFailureKey),
              let value = try? JSONDecoder().decode(SolarFailure.self, from: data), value.accountID == accountID else { return nil }
        return (value.at, value.message)
    }

    public func noteSolarFailure(accountID: String, at date: Date, message: String) {
        lock.lock(); defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(SolarFailure(accountID: accountID, at: date, message: message)) {
            defaults.set(data, forKey: Self.solarFailureKey)
        }
    }

    public func clearSolarFailure() {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: Self.solarFailureKey)
    }

    private static let kioskRejectionKey = "sunwatt.kioskRejection"
    private struct KioskRejection: Codable { var linkID: String; var since: Date }

    /// Since when the kiosk link with this cache ID has been rejected without a success in between. Nil when it
    /// was not rejected (or another link was).
    public func kioskRejectedSince(linkID: String) -> Date? {
        lock.lock(); defer { lock.unlock() }
        guard let data = defaults.data(forKey: Self.kioskRejectionKey),
              let value = try? JSONDecoder().decode(KioskRejection.self, from: data), value.linkID == linkID else { return nil }
        return value.since
    }

    /// Records a rejection of the link; keeps the first time when it was already rejected.
    /// Returns the time the rejections started.
    @discardableResult
    public func noteKioskRejected(linkID: String, at date: Date) -> Date {
        if let since = kioskRejectedSince(linkID: linkID) { return since }
        lock.lock(); defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(KioskRejection(linkID: linkID, since: date)) {
            defaults.set(data, forKey: Self.kioskRejectionKey)
        }
        return date
    }

    /// A successful fetch (or a new link) ends the rejection.
    public func clearKioskRejection() {
        lock.lock(); defer { lock.unlock() }
        defaults.removeObject(forKey: Self.kioskRejectionKey)
    }

    public func loadSolar() -> SolarSnapshot? { read(SolarSnapshot.self, file: "solar.json") }
    /// Skipped when the stored snapshot has a newer `fetchedAt`.
    public func saveSolar(_ snapshot: SolarSnapshot) { write(snapshot, file: "solar.json", fetchedAt: snapshot.fetchedAt) }
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
