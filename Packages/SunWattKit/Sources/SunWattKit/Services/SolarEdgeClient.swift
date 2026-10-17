import Foundation

/// Reads a site from the SolarEdge Monitoring API (`monitoringapi.solaredge.com`, API key + site ID).
/// Two requests per fetch, sent together: `overview` (power now, energy today/month/year/lifetime, last update)
/// and `powerDetails` (today's 15-minute curves per meter: Production, and with a meter at home also Consumption,
/// FeedIn, Purchased, SelfConsumption). A response without a Production meter falls back to `power` (one more
/// request). SolarEdge allows 300 requests a day per site and per key (see `SolarProvider.minimumFetchInterval`).
/// Times come as "yyyy-MM-dd HH:mm:ss" in the site's time zone; `calendar` must use that zone (default: the device's).
public struct SolarEdgeClient: SolarDataSource {
    public static let host = "monitoringapi.solaredge.com"

    public let credentials: SolarAPICredentials
    public let http: HTTPClient
    public let now: @Sendable () -> Date
    public let calendar: Calendar

    public init(
        credentials: SolarAPICredentials,
        http: HTTPClient = URLSession.sunWatt,
        now: @escaping @Sendable () -> Date = { Date() },
        calendar: Calendar = SolarLocalTime.deviceCalendar
    ) {
        self.credentials = credentials
        self.http = http
        self.now = now
        self.calendar = calendar
    }

    public func fetch() async throws -> SolarSnapshot {
        let date = now()
        async let overview = get(overviewURL)
        async let details = get(powerURL(day: date, path: "powerDetails"))
        let (overviewData, detailsData) = try await (overview, details)
        if Self.hasProduction(detailsData) {
            return try Self.parse(overview: overviewData, power: detailsData, fetchedAt: date, calendar: calendar)
        }
        // No Production meter in the details: read the inverter curve, keep the meter readings for consumption.
        let powerData = try await get(powerURL(day: date, path: "power"))
        var snapshot = try Self.parse(overview: overviewData, power: powerData, fetchedAt: date, calendar: calendar)
        snapshot.home = Self.home(fromDetails: detailsData, fetchedAt: date, calendar: calendar)
        return snapshot
    }

    var overviewURL: URL { url(path: "overview", query: []) }

    /// Today (in `calendar`) from 00:00:00 to 23:59:59, for `power` or `powerDetails`.
    func powerURL(day: Date, path: String = "powerDetails") -> URL {
        let start = calendar.startOfDay(for: day)
        let c = calendar.dateComponents([.year, .month, .day], from: start)
        let dayText = String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        return url(path: path, query: [
            URLQueryItem(name: "startTime", value: "\(dayText) 00:00:00"),
            URLQueryItem(name: "endTime", value: "\(dayText) 23:59:59"),
        ])
    }

    private func url(path: String, query: [URLQueryItem]) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = Self.host
        components.path = "/site/\(credentials.siteID)/\(path)"
        // The key is letters and digits only (`SolarAPICredentials`), so it needs no escaping.
        components.queryItems = query + [URLQueryItem(name: "api_key", value: credentials.apiKey)]
        return components.url ?? URL(string: "https://invalid.invalid")!
    }

    private func get(_ url: URL) async throws -> Data {
        let (data, response) = try await HTTPFetch.perform(http, HTTPFetch.request(url), redacting: credentials.apiKey)
        if let status = (response as? HTTPURLResponse)?.statusCode, status == 401 || status == 403 {
            throw SunWattError.solarCredentialsRejected
        }
        try HTTPFetch.checkStatus(response)
        return data
    }

    /// Parses the `overview` and `power` bodies. Curve points later than `fetchedAt` + 5 min lose their value.
    public static func parse(overview: Data, power: Data, fetchedAt: Date, calendar: Calendar) throws -> SolarSnapshot {
        guard let root = (try? JSONSerialization.jsonObject(with: overview)) as? [String: Any],
              let o = root["overview"] as? [String: Any] else {
            throw SunWattError.badResponse("overview")
        }
        func energyKWh(_ key: String) -> Double? {
            SolarLocalTime.number((o[key] as? [String: Any])?["energy"]).map { $0 / 1000 }
        }
        let currentKW = SolarLocalTime.number((o["currentPower"] as? [String: Any])?["power"]).map { $0 / 1000 }
        // At night the inverter stops reporting, so after midnight lastUpdateTime is still yesterday evening. That
        // time must not make a fresh snapshot look like yesterday's (`SunWattEntry.isSolarFromEarlierDay`): only a
        // time from the fetch day counts.
        let lastUpdate = (o["lastUpdateTime"] as? String)
            .flatMap { SolarLocalTime.parse($0, calendar: calendar) }
            .flatMap { calendar.isDate($0, inSameDayAs: fetchedAt) ? $0 : nil }

        // `power` (one curve) or `powerDetails` (one curve per meter).
        let curve: [CurvePoint]
        var home: HomeEnergy?
        if let details = Self.meters(power) {
            guard let production = details.meters["Production"] else { throw SunWattError.badResponse("powerDetails") }
            curve = Self.curve(production, scale: details.scale, fetchedAt: fetchedAt, calendar: calendar)
            home = Self.home(fromDetails: power, fetchedAt: fetchedAt, calendar: calendar)
        } else {
            guard let powerRoot = (try? JSONSerialization.jsonObject(with: power)) as? [String: Any],
                  let p = powerRoot["power"] as? [String: Any] else {
                throw SunWattError.badResponse("power")
            }
            curve = Self.curve(p["values"] as? [[String: Any]] ?? [], scale: Self.scale(p["unit"]), fetchedAt: fetchedAt, calendar: calendar)
        }

        return SolarSnapshot(
            stationName: nil,
            currentPowerKW: currentKW,
            dailyEnergyKWh: energyKWh("lastDayData"),
            monthEnergyKWh: energyKWh("lastMonthData"),
            yearEnergyKWh: energyKWh("lastYearData"),
            cumulativeEnergyKWh: energyKWh("lifeTimeData"),
            curve: curve,
            fetchedAt: fetchedAt,
            dataAsOf: lastUpdate.map { min($0, fetchedAt) } ?? curve.last { $0.kW != nil }?.time,
            home: home
        ).clampingProduction()
    }

    /// kW per unit. The unit is "W" in practice; "kW" and "MW" are handled in case a site reports them.
    static func scale(_ unit: Any?) -> Double {
        switch ((unit as? String) ?? "W").uppercased() {
        case "KW": 1
        case "MW": 1000
        default: 0.001
        }
    }

    /// Points in kW; values later than `fetchedAt` + 5 min lose their value.
    static func curve(_ values: [[String: Any]], scale: Double, fetchedAt: Date, calendar: Calendar) -> [CurvePoint] {
        let limit = fetchedAt.addingTimeInterval(5 * 60)
        return values.compactMap { item -> CurvePoint? in
            guard let text = item["date"] as? String, let time = SolarLocalTime.parse(text, calendar: calendar) else { return nil }
            let kW = SolarLocalTime.number(item["value"]).map { $0 * scale }
            return CurvePoint(time: time, kW: time > limit ? nil : kW)
        }
        .sorted { $0.time < $1.time }
    }

    /// The meters of a `powerDetails` body by type ("Production", "Consumption", "FeedIn", "Purchased", ...).
    /// Nil when the body is not `powerDetails`.
    static func meters(_ data: Data) -> (meters: [String: [[String: Any]]], scale: Double)? {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let details = root["powerDetails"] as? [String: Any] else { return nil }
        var meters: [String: [[String: Any]]] = [:]
        for meter in details["meters"] as? [[String: Any]] ?? [] {
            if let type = meter["type"] as? String { meters[type] = meter["values"] as? [[String: Any]] ?? [] }
        }
        return (meters, scale(details["unit"]))
    }

    /// True when a `powerDetails` body has a Production meter (its values may all be null at night).
    static func hasProduction(_ data: Data) -> Bool {
        meters(data)?.meters["Production"] != nil
    }

    /// Consumption and grid exchange from a `powerDetails` body. Nil without a Consumption meter that has values
    /// (no meter at home: export-only plants show nothing about consumption).
    /// Now: the latest 15-minute average, if at most 30 minutes old. Today: the curves summed (15-minute points).
    static func home(fromDetails data: Data, fetchedAt: Date, calendar: Calendar) -> HomeEnergy? {
        guard let details = meters(data), let consumption = details.meters["Consumption"] else { return nil }
        let used = curve(consumption, scale: details.scale, fetchedAt: fetchedAt, calendar: calendar)
        guard let latest = used.last(where: { $0.kW != nil }) else { return nil }
        let bought = details.meters["Purchased"].map { curve($0, scale: details.scale, fetchedAt: fetchedAt, calendar: calendar) }
        let sold = details.meters["FeedIn"].map { curve($0, scale: details.scale, fetchedAt: fetchedAt, calendar: calendar) }
        func total(_ points: [CurvePoint]?) -> Double? {
            points.map { $0.compactMap(\.kW).reduce(0, +) * 0.25 }
        }
        func value(_ points: [CurvePoint]?, at time: Date) -> Double? {
            points?.first { $0.time == time }?.kW
        }
        let recent = fetchedAt.timeIntervalSince(latest.time) <= 30 * 60
        var grid: Double?
        if recent, let buy = value(bought, at: latest.time), let sell = value(sold, at: latest.time) { grid = buy - sell }
        return HomeEnergy(
            consumptionKW: recent ? latest.kW : nil,
            gridKW: grid,
            dailyConsumptionKWh: total(used),
            dailyImportKWh: total(bought),
            dailyExportKWh: total(sold),
            curve: used)
    }
}

/// Helpers shared by the SolarEdge and PVOutput clients.
public enum SolarLocalTime {
    /// Gregorian calendar in the device's time zone (where the plant almost always is).
    public static var deviceCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar
    }

    /// "yyyy-MM-dd HH:mm:ss" or "yyyy-MM-dd HH:mm" in `calendar`'s time zone. Nil for anything else.
    static func parse(_ text: String, calendar: Calendar) -> Date? {
        let parts = text.trimmingCharacters(in: .whitespaces).split(separator: " ")
        guard parts.count == 2 else { return nil }
        let day = parts[0].split(separator: "-").compactMap { Int($0) }
        let time = parts[1].split(separator: ":").compactMap { Int($0) }
        guard day.count == 3, (2...3).contains(time.count) else { return nil }
        return date(year: day[0], month: day[1], day: day[2], hour: time[0], minute: time[1], second: time.count == 3 ? time[2] : 0, calendar: calendar)
    }

    /// Validated local date; nil when a field is out of range or the time does not exist (spring-forward gap).
    static func date(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, calendar: Calendar) -> Date? {
        guard (1...12).contains(month), (1...31).contains(day), (0..<24).contains(hour), (0..<60).contains(minute), (0..<60).contains(second) else { return nil }
        let components = DateComponents(year: year, month: month, day: day, hour: hour, minute: minute, second: second)
        guard let date = calendar.date(from: components) else { return nil }
        let check = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        guard check.year == year, check.month == month, check.day == day, check.hour == hour, check.minute == minute else { return nil }
        return date
    }

    /// Lenient number: JSON number or numeric string; nil for anything else, NaN or infinity.
    static func number(_ value: Any?) -> Double? {
        FusionSolarKioskClient.number(value)
    }
}
