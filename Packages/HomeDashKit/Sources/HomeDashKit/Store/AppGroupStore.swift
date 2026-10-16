import Foundation

/// Settings and last-good data shared by the app and the widget extension.
/// Settings in `UserDefaults(suiteName: Constants.appGroupID)`; snapshots as JSON files in the App Group container.
/// When the App Group is unavailable (unsigned macOS builds), falls back to `UserDefaults.standard` and Application Support.
public final class AppGroupStore: @unchecked Sendable {
    public static let shared = AppGroupStore()

    /// `defaults` and `directory` are injectable for tests.
    public init(defaults: UserDefaults? = nil, directory: URL? = nil) {
        fatalError("Implemented by the store/timeline task")
    }

    public var settings: HomeDashSettings {
        get { fatalError("Implemented by the store/timeline task") }
        set { fatalError("Implemented by the store/timeline task") }
    }

    public func loadSolar() -> SolarSnapshot? { fatalError("Implemented by the store/timeline task") }
    public func saveSolar(_ snapshot: SolarSnapshot) { fatalError("Implemented by the store/timeline task") }
    public func loadPrices() -> PriceSeries? { fatalError("Implemented by the store/timeline task") }
    public func savePrices(_ series: PriceSeries) { fatalError("Implemented by the store/timeline task") }
}
