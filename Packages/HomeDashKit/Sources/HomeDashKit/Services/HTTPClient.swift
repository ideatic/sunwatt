import Foundation

/// Minimal HTTP abstraction so clients can be tested with fixtures.
public protocol HTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPClient {}

public protocol SolarDataSource: Sendable {
    func fetch() async throws -> SolarSnapshot
}

public protocol PriceDataSource: Sendable {
    /// Fetches prices for the Europe/Madrid day that contains `day`.
    func fetch(day: Date) async throws -> PriceSeries
}

/// Errors shown to the user. `errorDescription` is localized (see L10n).
public enum HomeDashError: Error, Equatable, Sendable, LocalizedError {
    case invalidKioskURL
    case notConfigured
    case http(status: Int)
    case badResponse(String)
    case unavailable
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .invalidKioskURL: L10n.string("The kiosk URL is not valid. It must contain the kk parameter.")
        case .notConfigured: L10n.string("Set up the kiosk URL in Settings.")
        case .http(let status): L10n.string("The server responded with code \(status).")
        case .badResponse(let detail): L10n.string("Invalid response: \(detail)")
        case .unavailable: L10n.string("Data not available yet.")
        case .network(let detail): L10n.string("Network error: \(detail)")
        }
    }
}
