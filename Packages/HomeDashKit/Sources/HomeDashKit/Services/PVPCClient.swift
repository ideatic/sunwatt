import Foundation

/// Fetches PVPC prices from Red Eléctrica (apidatos.ree.es), no token.
public struct PVPCClient: PriceDataSource {
    public let http: HTTPClient
    public let now: @Sendable () -> Date

    static let endpoint = "https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real"

    public init(http: HTTPClient = URLSession.shared, now: @escaping @Sendable () -> Date = { Date() }) {
        self.http = http
        self.now = now
    }

    public func fetch(day: Date) async throws -> PriceSeries {
        let start = Constants.madridCalendar.startOfDay(for: day)
        var request = URLRequest(url: try Self.url(forDayStarting: start))
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 20

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await http.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw HomeDashError.network(error.localizedDescription)
        }

        if Self.hasErrorsBody(data) { throw HomeDashError.unavailable }
        if let status = (response as? HTTPURLResponse)?.statusCode, !(200..<300).contains(status) {
            throw HomeDashError.http(status: status)
        }
        return try Self.parse(data, fetchedAt: now())
    }

    /// Today, plus tomorrow when `includeTomorrow` and REE has published it.
    /// A failure for tomorrow must NOT fail the whole call.
    public func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries {
        let current = now()
        guard includeTomorrow else { return try await fetch(day: current) }
        let cal = Constants.madridCalendar
        let tomorrow = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: current))
            ?? current.addingTimeInterval(86_400)

        async let todayResult = fetch(day: current)
        async let tomorrowResult = Self.attempt { try await fetch(day: tomorrow) }
        let today = try await todayResult
        guard let next = await tomorrowResult else { return today }

        var byStart: [Date: PricePoint] = [:]
        for p in today.points + next.points where byStart[p.start] == nil { byStart[p.start] = p }
        let updates = [today.sourceUpdatedAt, next.sourceUpdatedAt].compactMap { $0 }
        return PriceSeries(
            points: byStart.values.sorted { $0.start < $1.start },
            granularity: today.granularity,
            fetchedAt: current,
            sourceUpdatedAt: updates.max()
        )
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
            throw HomeDashError.badResponse("JSON no válido")
        }
        guard let dict = root as? [String: Any] else { throw HomeDashError.badResponse("JSON inesperado") }
        if let errors = dict["errors"] as? [Any], !errors.isEmpty { throw HomeDashError.unavailable }
        guard let included = dict["included"] as? [[String: Any]] else {
            throw HomeDashError.badResponse("falta included")
        }
        guard let entry = included.first(where: { ($0["type"] as? String) == "PVPC" }),
              let attrs = entry["attributes"] as? [String: Any],
              let values = attrs["values"] as? [[String: Any]], !values.isEmpty
        else { throw HomeDashError.unavailable }

        var raw: [(Date, Double)] = []
        for item in values {
            guard let value = (item["value"] as? NSNumber)?.doubleValue,
                  let text = item["datetime"] as? String,
                  let date = parseDate(text)
            else { throw HomeDashError.badResponse("punto de precio no válido") }
            raw.append((date, value))
        }
        raw.sort { $0.0 < $1.0 }

        var unique: [(Date, Double)] = []
        for r in raw where unique.last?.0 != r.0 { unique.append(r) }

        let quarter = unique.count > 1 && unique[1].0.timeIntervalSince(unique[0].0) == 900
        let duration: TimeInterval = quarter ? 900 : 3600
        let points = unique.map { PricePoint(start: $0.0, duration: duration, eurPerMWh: $0.1) }
        let updated = (attrs["last-update"] as? String).flatMap(parseDate)
        return PriceSeries(points: points, granularity: quarter ? .quarterHourly : .hourly,
                           fetchedAt: fetchedAt, sourceUpdatedAt: updated)
    }

    // MARK: - Helpers

    static func url(forDayStarting start: Date) throws -> URL {
        let c = Constants.madridCalendar.dateComponents([.year, .month, .day], from: start)
        let day = String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        var comps = URLComponents(string: endpoint)!
        comps.queryItems = [
            URLQueryItem(name: "start_date", value: "\(day)T00:00"),
            URLQueryItem(name: "end_date", value: "\(day)T23:59"),
            URLQueryItem(name: "time_trunc", value: "hour"),
        ]
        guard let url = comps.url else { throw HomeDashError.badResponse("URL no válida") }
        return url
    }

    private static func hasErrorsBody(_ data: Data) -> Bool {
        guard let dict = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let errors = dict["errors"] as? [Any] else { return false }
        return !errors.isEmpty
    }

    static func parseDate(_ text: String) -> Date? {
        let withFraction = ISO8601DateFormatter()
        withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = withFraction.date(from: text) { return d }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: text)
    }
}
