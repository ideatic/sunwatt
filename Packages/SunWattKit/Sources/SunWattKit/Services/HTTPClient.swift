import Foundation

/// Minimal HTTP abstraction so clients can be tested with fixtures.
public protocol HTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, URLResponse)
}

extension URLSession: HTTPClient {}

public extension URLSession {
    /// Session used by the clients by default: no disk cache, no cookies, short timeouts, no waiting for connectivity.
    /// A widget refresh has a small time budget, so a hanging request must fail fast.
    static let sunWatt: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 12
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        return URLSession(configuration: config)
    }()
}

public protocol SolarDataSource: Sendable {
    func fetch() async throws -> SolarSnapshot
}

public protocol PriceDataSource: Sendable {
    /// Fetches prices for the market day (in the source's time zone) that contains `day`.
    func fetch(day: Date) async throws -> PriceSeries
    /// Today, plus tomorrow when `includeTomorrow` and the source has published it.
    /// A failure for tomorrow must NOT fail the whole call.
    /// Default: `fetch(day:)` for the current instant only.
    func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries
}

public extension PriceDataSource {
    func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries {
        try await fetch(day: Date())
    }
}

/// Errors shown to the user. `errorDescription` is localized (see L10n).
public enum SunWattError: Error, Equatable, Sendable, LocalizedError {
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

/// Shared request plumbing for the clients.
enum HTTPFetch {
    static func request(_ url: URL, timeout: TimeInterval = 20) -> URLRequest {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    /// Sends `request`. Cancellation (including `URLError.cancelled`) becomes `CancellationError`;
    /// every other failure becomes `SunWattError.network` with `secret` removed from the message.
    static func perform(_ http: HTTPClient, _ request: URLRequest, redacting secret: String? = nil) async throws -> (Data, URLResponse) {
        do {
            return try await http.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            var text = error.localizedDescription
            if let secret, !secret.isEmpty { text = text.replacingOccurrences(of: secret, with: "***") }
            throw SunWattError.network(text)
        }
    }

    static func checkStatus(_ response: URLResponse) throws {
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw SunWattError.http(status: http.statusCode)
        }
    }
}
