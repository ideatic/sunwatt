import Foundation

/// Data for intents and controls: the cache first, the network only when the cache cannot answer.
public struct IntentDataSource: Sendable {
    public enum Need: Sendable {
        case price
        case solar
    }

    /// Cached solar readings older than this are refreshed (an answer for "now" must be recent).
    public static let solarMaxAge: TimeInterval = 10 * 60

    public var loader: SunWattLoader
    public var deadline: TimeInterval
    /// Data stored less than this many seconds ago is used without a request (see `SunWattLoader.load`).
    public var maxCacheAge: TimeInterval

    public init(loader: SunWattLoader = SunWattLoader(), deadline: TimeInterval = 8, maxCacheAge: TimeInterval = SunWattLoader.sharedCacheWindow) {
        self.loader = loader
        self.deadline = deadline
        self.maxCacheAge = maxCacheAge
    }

    public func data(for need: Need) async -> SunWattData {
        let cached = loader.cached()
        let now = loader.now()
        let longestInterval = loader.store.settings.plants.compactMap { SolarAccount(plant: $0)?.provider.minimumFetchInterval }.max() ?? 0
        let solarMaxAge = max(Self.solarMaxAge, longestInterval)
        if Self.cacheAnswers(cached, need: need, now: now, solarMaxAge: solarMaxAge) { return cached }
        return await loader.load(deadline: deadline, maxCacheAge: maxCacheAge)
    }

    /// `solarMaxAge`: the provider's own fetch interval when longer (SolarEdge would not fetch again anyway).
    static func cacheAnswers(_ data: SunWattData, need: Need, now: Date, solarMaxAge: TimeInterval = IntentDataSource.solarMaxAge) -> Bool {
        switch need {
        case .price:
            return data.prices?.currentHour(at: now) != nil
        case .solar:
            // Every plant must be answerable (several plants: Siri gives each one).
            return data.allPlants.allSatisfy { plant in
                if case .notConfigured = plant.status { return true }
                guard let solar = plant.solar else { return false }
                return now.timeIntervalSince(solar.fetchedAt) <= solarMaxAge
            }
        }
    }
}
