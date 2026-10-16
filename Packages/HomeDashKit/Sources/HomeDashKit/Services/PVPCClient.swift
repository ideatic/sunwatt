import Foundation

/// Fetches PVPC prices from Red Eléctrica (apidatos.ree.es), no token.
public struct PVPCClient: PriceDataSource {
    public let http: HTTPClient
    public let now: @Sendable () -> Date

    public init(http: HTTPClient = URLSession.shared, now: @escaping @Sendable () -> Date = { Date() }) {
        self.http = http
        self.now = now
    }

    public func fetch(day: Date) async throws -> PriceSeries {
        fatalError("Implemented by the PVPC client task")
    }

    /// Today, plus tomorrow when `includeTomorrow` and REE has published it.
    /// A failure for tomorrow must NOT fail the whole call.
    public func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries {
        fatalError("Implemented by the PVPC client task")
    }

    /// Parses an REE response body, picking the `included[]` entry of type "PVPC".
    public static func parse(_ data: Data, fetchedAt: Date) throws -> PriceSeries {
        fatalError("Implemented by the PVPC client task")
    }
}
