import Foundation

/// Data for intents and controls: the cache first, the network only when the cache cannot answer.
public struct IntentDataSource: Sendable {
    public enum Need: Sendable {
        case price
        case solar
    }

    /// Cached solar readings older than this are refreshed (an answer for "now" must be recent).
    public static let solarMaxAge: TimeInterval = 10 * 60

    public var loader: HomeDashLoader
    public var deadline: TimeInterval

    public init(loader: HomeDashLoader = HomeDashLoader(), deadline: TimeInterval = 8) {
        self.loader = loader
        self.deadline = deadline
    }

    public func data(for need: Need) async -> HomeDashData {
        let cached = loader.cached()
        let now = loader.now()
        if Self.cacheAnswers(cached, need: need, now: now) { return cached }
        return await loader.load(deadline: deadline)
    }

    static func cacheAnswers(_ data: HomeDashData, need: Need, now: Date) -> Bool {
        switch need {
        case .price:
            return data.prices?.currentHour(at: now) != nil
        case .solar:
            if case .notConfigured = data.solarStatus { return true }
            guard let solar = data.solar else { return false }
            return now.timeIntervalSince(solar.fetchedAt) <= solarMaxAge
        }
    }
}
