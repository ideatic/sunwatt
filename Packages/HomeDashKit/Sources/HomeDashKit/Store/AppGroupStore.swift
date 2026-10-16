import Foundation

/// Settings and last-good data shared by the app and the widget extension.
/// Settings in `UserDefaults(suiteName: Constants.appGroupID)`; snapshots as JSON files in the App Group container.
/// When the App Group is unavailable (unsigned macOS builds), falls back to `UserDefaults.standard` and Application Support.
public final class AppGroupStore: @unchecked Sendable {
    public static let shared = AppGroupStore()

    private static let settingsKey = "homedash.settings"

    private let defaults: UserDefaults
    private let directory: URL
    private let lock = NSLock()

    /// `defaults` and `directory` are injectable for tests.
    public init(defaults: UserDefaults? = nil, directory: URL? = nil) {
        self.defaults = defaults ?? UserDefaults(suiteName: Constants.appGroupID) ?? .standard
        self.directory = directory ?? Self.defaultDirectory()
    }

    private static func defaultDirectory() -> URL {
        let fm = FileManager.default
        if let container = fm.containerURL(forSecurityApplicationGroupIdentifier: Constants.appGroupID) {
            return container.appendingPathComponent("Library/Caches/HomeDash", isDirectory: true)
        }
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fm.temporaryDirectory
        return support.appendingPathComponent("HomeDash", isDirectory: true)
    }

    public var settings: HomeDashSettings {
        get {
            lock.lock(); defer { lock.unlock() }
            guard let data = defaults.data(forKey: Self.settingsKey),
                  let value = try? JSONDecoder().decode(HomeDashSettings.self, from: data)
            else { return HomeDashSettings() }
            return value
        }
        set {
            lock.lock(); defer { lock.unlock() }
            guard let data = try? JSONEncoder().encode(newValue) else { return }
            defaults.set(data, forKey: Self.settingsKey)
        }
    }

    public func loadSolar() -> SolarSnapshot? { read(SolarSnapshot.self, file: "solar.json") }
    public func saveSolar(_ snapshot: SolarSnapshot) { write(snapshot, file: "solar.json") }
    public func loadPrices() -> PriceSeries? { read(PriceSeries.self, file: "prices.json") }
    public func savePrices(_ series: PriceSeries) { write(series, file: "prices.json") }

    private func read<T: Decodable>(_ type: T.Type, file: String) -> T? {
        lock.lock(); defer { lock.unlock() }
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(file)) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(type, from: data)
    }

    private func write<T: Encodable>(_ value: T, file: String) {
        lock.lock(); defer { lock.unlock() }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(value) else { return }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? data.write(to: directory.appendingPathComponent(file), options: .atomic)
    }
}
