import Foundation

/// Fetches PVPC prices from Red Eléctrica (apidatos.ree.es), no token.
public struct PVPCClient: PriceDataSource {
    public let http: HTTPClient
    public let now: @Sendable () -> Date

    static let endpoint = "https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real"

    public init(http: HTTPClient = URLSession.sunWatt, now: @escaping @Sendable () -> Date = { Date() }) {
        self.http = http
        self.now = now
    }

    public func fetch(day: Date) async throws -> PriceSeries {
        let start = Constants.madridCalendar.startOfDay(for: day)
        let request = HTTPFetch.request(try Self.url(forDayStarting: start))
        let (data, response) = try await HTTPFetch.perform(http, request)
        if Self.hasErrorsBody(data) { throw SunWattError.unavailable }
        try HTTPFetch.checkStatus(response)
        return try Self.parse(data, fetchedAt: now())
    }

    /// Today, plus tomorrow when `includeTomorrow` and REE has published it.
    /// A failure for tomorrow must NOT fail the whole call.
    public func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries {
        let current = now()
        guard includeTomorrow else { return try await fetch(day: current) }
        let cal = Constants.madridCalendar
        guard let tomorrow = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: current)) else {
            return try await fetch(day: current)
        }

        async let todayResult = fetch(day: current)
        async let tomorrowResult = Self.attempt { try await fetch(day: tomorrow) }
        let today = try await todayResult
        guard let next = await tomorrowResult else { return today }
        return PriceSeries.merged([today, next], fetchedAt: current)
    }

    private static func attempt<T: Sendable>(_ work: @Sendable () async throws -> T) async -> T? {
        try? await work()
    }

    /// Parses an REE response body, picking the `included[]` entry of type "PVPC".
    public static func parse(_ data: Data, fetchedAt: Date) throws -> PriceSeries {
        let root: Any
        do {
            root = try JSONSerialization.jsonObject(with: data)
        } catch {
            throw SunWattError.badResponse("invalid JSON")
        }
        guard let dict = root as? [String: Any] else { throw SunWattError.badResponse("unexpected JSON") }
        if let errors = dict["errors"] as? [Any], !errors.isEmpty { throw SunWattError.unavailable }
        guard let included = dict["included"] as? [[String: Any]] else {
            throw SunWattError.badResponse("missing included")
        }
        guard let entry = included.first(where: { ($0["type"] as? String) == "PVPC" }),
              let attrs = entry["attributes"] as? [String: Any],
              let values = attrs["values"] as? [[String: Any]], !values.isEmpty
        else { throw SunWattError.unavailable }

        let raw: [(Date, Double)] = values.compactMap { item in
            guard let value = (item["value"] as? NSNumber)?.doubleValue, value.isFinite,
                  let text = item["datetime"] as? String,
                  let date = parseDate(text) else { return nil }
            return (date, value)
        }.sorted { $0.0 < $1.0 }
        guard !raw.isEmpty else { throw SunWattError.badResponse("no valid price points") }

        var unique: [(Date, Double)] = []
        for r in raw where unique.last?.0 != r.0 { unique.append(r) }

        let updated = (attrs["last-update"] as? String).flatMap(parseDate)
        return PriceSeries(points: PriceSeries.makePoints(unique), granularity: PriceSeries.granularity(of: unique.map(\.0)),
                           fetchedAt: fetchedAt, sourceUpdatedAt: updated, region: .spain)
    }

    // MARK: - Helpers

    static func url(forDayStarting start: Date) throws -> URL {
        let day = PriceSeries.dayString(of: start, in: Constants.madridCalendar)
        var comps = URLComponents(string: endpoint)!
        comps.queryItems = [
            URLQueryItem(name: "start_date", value: "\(day)T00:00"),
            URLQueryItem(name: "end_date", value: "\(day)T23:59"),
            URLQueryItem(name: "time_trunc", value: "hour"),
        ]
        guard let url = comps.url else { throw SunWattError.badResponse("invalid URL") }
        return url
    }

    private static func hasErrorsBody(_ data: Data) -> Bool {
        guard let dict = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let errors = dict["errors"] as? [Any] else { return false }
        return !errors.isEmpty
    }

    private static let fractionalStyle = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
    private static let plainStyle = Date.ISO8601FormatStyle()

    /// ISO 8601 with an explicit offset (`+02:00`, `Z`), with or without fractional seconds.
    static func parseDate(_ text: String) -> Date? {
        if let d = try? Date(text, strategy: fractionalStyle) { return d }
        return try? Date(text, strategy: plainStyle)
    }
}
