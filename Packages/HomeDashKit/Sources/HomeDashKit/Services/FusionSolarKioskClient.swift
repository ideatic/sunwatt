import Foundation

/// Fetches and parses the FusionSolar public kiosk.
public struct FusionSolarKioskClient: SolarDataSource {
    public let endpoint: KioskEndpoint
    public let http: HTTPClient
    public let now: @Sendable () -> Date

    public init(endpoint: KioskEndpoint, http: HTTPClient = URLSession.shared, now: @escaping @Sendable () -> Date = { Date() }) {
        self.endpoint = endpoint
        self.http = http
        self.now = now
    }

    public func fetch() async throws -> SolarSnapshot {
        fatalError("Implemented by the FusionSolar client task")
    }

    /// Parses a raw kiosk response body (outer JSON with HTML-escaped `data` string).
    /// `day` is any instant in the Europe/Madrid day the curve labels ("HH:mm") belong to.
    public static func parse(_ data: Data, fetchedAt: Date, day: Date) throws -> SolarSnapshot {
        fatalError("Implemented by the FusionSolar client task")
    }
}
