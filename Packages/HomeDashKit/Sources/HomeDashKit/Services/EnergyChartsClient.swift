import Foundation

/// Day-ahead wholesale prices from Energy-Charts (Fraunhofer ISE, CC BY 4.0) for every region except Spain.
///
/// `GET https://api.energy-charts.info/price?bzn=<zone>&start=yyyy-MM-dd&end=yyyy-MM-dd`
/// answers `{"unix_seconds": [...], "price": [...], "unit": "EUR / MWh", ...}` with 15-minute points
/// (96 per day, 92/100 on DST days). The API rate-limits hard (HTTP 429), so today and tomorrow are one request.
public struct EnergyChartsClient: PriceDataSource {
    public let region: PriceRegion
    public let http: HTTPClient
    public let now: @Sendable () -> Date

    static let endpoint = "https://api.energy-charts.info/price"

    public init(region: PriceRegion, http: HTTPClient = URLSession.homeDash, now: @escaping @Sendable () -> Date = { Date() }) {
        self.region = region
        self.http = http
        self.now = now
    }

    public func fetch(day: Date) async throws -> PriceSeries {
        let cal = region.calendar
        guard let interval = cal.dateInterval(of: .day, for: day) else { throw HomeDashError.badResponse("invalid day") }
        return try await load(days: interval.start...interval.start, window: interval)
    }

    /// One request for today and tomorrow (market days of the region). When tomorrow is not published yet,
    /// the answer holds today only. With `includeTomorrow == false` the series is trimmed to today.
    public func fetchTodayAndTomorrow(includeTomorrow: Bool) async throws -> PriceSeries {
        let cal = region.calendar
        let current = now()
        guard let today = cal.dateInterval(of: .day, for: current),
              let tomorrow = cal.dateInterval(of: .day, for: today.end) else {
            throw HomeDashError.badResponse("invalid day")
        }
        let window = includeTomorrow ? DateInterval(start: today.start, end: tomorrow.end) : today
        return try await load(days: today.start...tomorrow.start, window: window, fetchedAt: current)
    }

    private func load(days: ClosedRange<Date>, window: DateInterval, fetchedAt: Date? = nil) async throws -> PriceSeries {
        let url = try Self.url(zone: region, startDay: days.lowerBound, endDay: days.upperBound)
        let (data, response) = try await HTTPFetch.perform(http, HTTPFetch.request(url))
        if let status = (response as? HTTPURLResponse)?.statusCode {
            if status == 404 { throw HomeDashError.unavailable }
            if !(200..<300).contains(status) { throw HomeDashError.http(status: status) }
        }
        return try Self.parse(data, region: region, fetchedAt: fetchedAt ?? now(), window: window)
    }

    /// Parses an Energy-Charts price response. Points outside `window` are dropped; null prices are skipped.
    public static func parse(_ data: Data, region: PriceRegion, fetchedAt: Date, window: DateInterval? = nil) throws -> PriceSeries {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw HomeDashError.badResponse("invalid JSON")
        }
        if let unit = root["unit"] as? String {
            let normalized = unit.lowercased().filter { !$0.isWhitespace }
            guard normalized == "eur/mwh" else { throw HomeDashError.badResponse("unit \(unit)") }
        } else {
            throw HomeDashError.badResponse("missing unit")
        }
        guard let seconds = root["unix_seconds"] as? [Any], let prices = root["price"] as? [Any] else {
            throw HomeDashError.badResponse("missing price arrays")
        }
        var raw: [(Date, Double)] = []
        for (s, p) in zip(seconds, prices) {
            guard let t = (s as? NSNumber)?.doubleValue, let price = (p as? NSNumber)?.doubleValue, price.isFinite else { continue }
            let start = Date(timeIntervalSince1970: t)
            if let window, !(start >= window.start && start < window.end) { continue }
            raw.append((start, price))
        }
        raw.sort { $0.0 < $1.0 }
        var unique: [(Date, Double)] = []
        for r in raw where unique.last?.0 != r.0 { unique.append(r) }
        guard !unique.isEmpty else { throw HomeDashError.unavailable }
        return PriceSeries(
            points: PriceSeries.makePoints(unique),
            granularity: PriceSeries.granularity(of: unique.map(\.0)),
            fetchedAt: fetchedAt,
            sourceUpdatedAt: nil,
            region: region
        )
    }

    static func url(zone region: PriceRegion, startDay: Date, endDay: Date) throws -> URL {
        guard let zone = region.energyChartsZone, var comps = URLComponents(string: endpoint) else {
            throw HomeDashError.badResponse("unsupported region")
        }
        let cal = region.calendar
        comps.queryItems = [
            URLQueryItem(name: "bzn", value: zone),
            URLQueryItem(name: "start", value: PriceSeries.dayString(of: startDay, in: cal)),
            URLQueryItem(name: "end", value: PriceSeries.dayString(of: endDay, in: cal)),
        ]
        guard let url = comps.url else { throw HomeDashError.badResponse("invalid URL") }
        return url
    }
}

/// Picks the price client for a region.
public enum PriceDataSourceFactory {
    public static func client(for region: PriceRegion, http: HTTPClient = URLSession.homeDash) -> PriceRangeDataSource {
        switch region {
        case .spain: PVPCClient(http: http)
        default: EnergyChartsClient(region: region, http: http)
        }
    }
}

public extension PriceRegion {
    /// `PriceDataSourceFactory.client(for:http:)`.
    static func client(for region: PriceRegion, http: HTTPClient = URLSession.homeDash) -> PriceRangeDataSource {
        PriceDataSourceFactory.client(for: region, http: http)
    }
}
