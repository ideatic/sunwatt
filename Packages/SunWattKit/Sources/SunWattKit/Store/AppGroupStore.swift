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
    private let lock = NSLock()

    /// `defaults` and `directory` are injectable for tests.
    public init(defaults: UserDefaults? = nil, directory: URL? = nil) {
        if let defaults, let directory {
            self.defaults = defaults
            self.directory = directory
        } else {
            let location = Self.defaultLocation()
            self.defaults = defaults ?? location.defaults
            self.directory = directory ?? location.directory
        }
    }

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

    public var settings: SunWattSettings {
        get {
            lock.lock(); defer { lock.unlock() }
            guard let data = defaults.data(forKey: Self.settingsKey),
                  let value = try? JSONDecoder().decode(SunWattSettings.self, from: data)
            else { return SunWattSettings() }
            return value
        }
        set {
            lock.lock(); defer { lock.unlock() }
            guard let data = try? JSONEncoder().encode(newValue) else { return }
            defaults.set(data, forKey: Self.settingsKey)
        }
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

    private func write<T: Encodable>(_ value: T, file: String, fetchedAt: Date) {
        lock.lock(); defer { lock.unlock() }
        if let existing = readLocked(FetchedAtProbe.self, file: file)?.fetchedAt, existing > fetchedAt { return }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        do {
            let data = try encoder.encode(value)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: directory.appendingPathComponent(file), options: .atomic)
        } catch {
            Self.log.error("Cannot write \(file, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }
}
