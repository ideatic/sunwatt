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

/// A solar provider (cloud service, inverter on the home network, or both). Contract, checked for every provider by
/// `ProviderContractTests`:
///
/// - **Snapshot.** `fetchedAt` is the device clock at the fetch (the client's `now`). `curve` holds today's points
///   only (one local day, at most 25 hours apart, DST included), sorted by time, no two at the same time; a point's
///   `kW` is nil for "no data", never negative. `currentPowerKW` and the energy figures are nil when unknown, never
///   negative. `dataAsOf` is the time of the newest figure the provider reports. `endpointID` is left nil (the loader
///   sets it). `isLive` is true only for figures read from the inverter just now; `cloudFetchedAt` is set only on such
///   live snapshots.
/// - **Errors.** Only `CancellationError` (the task was cancelled; never wrapped) or `SunWattError`:
///   `.network` (no connection, timeout), `.http(status:)` (any other non-2xx), `.badResponse` (the body cannot be
///   read), `.unavailable` (no data for today yet), `.rateLimited` (asked too often), and the account errors
///   `.kioskLinkRejected`, `.solarCredentialsRejected`, `.northboundAccountRejected`, `.inverterUnreachable`. The loader shows them to the user, so their text must
///   never contain the kiosk token or an API key (`HTTPFetch.perform(redacting:)`).
/// - **Budget.** One fetch must finish well within `SunWattLoader.load(deadline:)` (15 s) and stop when cancelled.
///   Rate limits are the provider's `SolarProviderProfile.minimumFetchInterval`, applied by the loader, not here.
/// - **No side effects.** No storage, no logging of secrets; the loader stores the result.
public protocol SolarDataSource: Sendable {
    func fetch() async throws -> SolarSnapshot
}

/// An electricity price source. Which one serves a region is in `PriceRegion.profile` (`PriceSource`). Contract,
/// checked for every source by `ProviderContractTests`:
///
/// - **Series.** `region` is the region asked for; points sorted by start, not overlapping, each with a positive
///   duration (15 or 60 minutes, `granularity`); prices in €/MWh (negative allowed). `fetchedAt` is the device clock.
///   A market day may have 23 or 25 hours (DST).
/// - **Errors.** `CancellationError` or `SunWattError` (`.network`, `.http(status:)`, `.badResponse`, `.unavailable`
///   when the day is not published), as for `SolarDataSource`.
/// - **Budget.** Within the loader deadline, cancellable. Energy-Charts answers HTTP 429 to parallel requests: one
///   request per call where the API allows it.
public protocol PriceDataSource: Sendable {
    /// Fetches prices for the market day (in the source's time zone) that contains `day`.
    func fetch(day: Date) async throws -> PriceSeries
    /// Today, plus tomorrow when `includeTomorrow` and the source has published it.
    /// A failure for tomorrow must NOT fail the whole call. "Today" comes from the source's own injected clock (no
    /// default implementation: it would have to read `Date()`).
    func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries
}

/// Errors shown to the user. `errorDescription` is localized (see L10n).
public enum SunWattError: Error, Equatable, Sendable, LocalizedError {
    case invalidKioskURL
    /// FusionSolar answered `success: false` or 401/403/404 for the kiosk link: most likely the link expired (they
    /// last one year) or the kiosk was turned off. `SunWattLoader` calls it expired once it lasts 30 minutes.
    case kioskLinkRejected
    /// The inverter address is not a valid IP address or host name.
    case invalidInverterAddress
    /// No Modbus answer from the Huawei dongle (not at home, wrong address, or Modbus TCP off).
    case inverterUnreachable
    /// SolarEdge / PVOutput: the ID is not a number or the key has the wrong format.
    case invalidSolarCredentials
    /// SolarEdge / PVOutput answered 401 or 403: wrong key or ID, or the API access is off.
    case solarCredentialsRejected
    /// FusionSolar API account: the user name or system code is missing, or the server is not a FusionSolar one.
    case invalidNorthboundAccount
    /// FusionSolar API account: the login was refused (wrong user name or system code, or not an API account).
    case northboundAccountRejected
    /// The provider refused because SunWatt asked too often (FusionSolar API failCode 407). Kept for the provider's
    /// fetch interval, then asked again.
    case rateLimited
    case notConfigured
    case http(status: Int)
    case badResponse(String)
    case unavailable
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .invalidKioskURL: L10n.string("The kiosk URL is not valid. It must contain the kk parameter.")
        case .kioskLinkRejected: L10n.string("Huawei no longer accepts this kiosk link. Kiosk links expire after a year: create a new one in FusionSolar and paste it in SunWatt.")
        case .invalidInverterAddress: L10n.string("This is not a valid address. Use the IP address of the inverter or its dongle, for example 192.168.1.50.")
        case .inverterUnreachable: L10n.string("The inverter did not answer. Check the address, that you are on your home Wi-Fi, and that its local access is turned on: Modbus TCP (in a Huawei dongle: “Enable (unrestricted)”) or, on a Fronius inverter, the Solar API.")
        case .invalidSolarCredentials: L10n.string("The ID must be a number and the API key letters and digits only.")
        case .solarCredentialsRejected: L10n.string("The API key or the ID was rejected. Check both, and that API access is on.")
        case .invalidNorthboundAccount: L10n.string("Enter the user name and the password (system code) of your FusionSolar API account.")
        case .northboundAccountRejected: L10n.string("FusionSolar did not accept the user name or the password. Check both with your installer.")
        case .rateLimited: L10n.string("The provider allows only a few requests at a time. SunWatt will try again in a few minutes.")
        case .notConfigured: L10n.string("Set up your solar system in Settings.")
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
