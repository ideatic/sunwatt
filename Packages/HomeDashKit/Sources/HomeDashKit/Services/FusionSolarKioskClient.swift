import Foundation

/// Fetches and parses the FusionSolar public kiosk.
public struct FusionSolarKioskClient: SolarDataSource {
    public let endpoint: KioskEndpoint
    public let http: HTTPClient
    public let now: @Sendable () -> Date

    public init(endpoint: KioskEndpoint, http: HTTPClient = URLSession.homeDash, now: @escaping @Sendable () -> Date = { Date() }) {
        self.endpoint = endpoint
        self.http = http
        self.now = now
    }

    public func fetch() async throws -> SolarSnapshot {
        let request = HTTPFetch.request(endpoint.apiURL)
        let (data, response) = try await HTTPFetch.perform(http, request, redacting: endpoint.kk)
        try HTTPFetch.checkStatus(response)
        let date = now()
        return try Self.parse(data, fetchedAt: date, day: date)
    }

    /// Parses a raw kiosk response body (outer JSON with HTML-escaped `data` string).
    /// Curve points later than `fetchedAt` + 5 min lose their value (stale yesterday curve).
    /// `day` is any instant in the Europe/Madrid day the curve labels ("HH:mm") belong to.
    public static func parse(_ data: Data, fetchedAt: Date, day: Date) throws -> SolarSnapshot {
        guard let outer = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw HomeDashError.badResponse("outer JSON")
        }
        guard (outer["success"] as? Bool) == true else {
            throw HomeDashError.badResponse("success=false")
        }
        guard let escaped = outer["data"] as? String, !escaped.isEmpty else {
            throw HomeDashError.badResponse("missing data")
        }
        let inner = HTMLEntities.unescape(escaped)
        guard let innerData = inner.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: innerData)) as? [String: Any] else {
            throw HomeDashError.badResponse("data is not JSON")
        }

        let kpiObject = root["realKpi"] as? [String: Any]
        let powerCurve = root["powerCurve"] as? [String: Any]
        guard kpiObject != nil || powerCurve != nil else {
            throw HomeDashError.badResponse("no realKpi or powerCurve")
        }
        let kpi = kpiObject ?? [:]
        let curveObject = powerCurve ?? [:]
        let labels = (curveObject["xAxis"] as? [Any])?.compactMap { $0 as? String } ?? []
        let values = curveObject["activePower"] as? [Any] ?? []
        let station = (root["stationOverview"] as? [String: Any])?["stationName"] as? String

        // Near midnight Huawei may still serve yesterday's curve: drop values from the future.
        let limit = fetchedAt.addingTimeInterval(5 * 60)
        let curve = curvePoints(labels: labels, values: values, day: day).map {
            $0.time > limit ? CurvePoint(time: $0.time, kW: nil) : $0
        }
        return SolarSnapshot(
            stationName: station.flatMap { $0.isEmpty ? nil : $0 },
            currentPowerKW: number(kpi["realTimePower"]),
            dailyEnergyKWh: number(kpi["dailyEnergy"]),
            monthEnergyKWh: number(kpi["monthEnergy"]),
            yearEnergyKWh: number(kpi["yearEnergy"]),
            cumulativeEnergyKWh: number(kpi["cumulativeEnergy"]),
            curve: curve,
            fetchedAt: fetchedAt,
            dataAsOf: curve.last { $0.kW != nil }?.time
        )
    }

    /// Lenient number: JSON number or numeric string. Anything else (`"-"`, `""`, null) is nil.
    static func number(_ value: Any?) -> Double? {
        let result: Double?
        switch value {
        case let n as NSNumber where CFGetTypeID(n) != CFBooleanGetTypeID(): result = n.doubleValue
        case let s as String: result = Double(s.trimmingCharacters(in: .whitespaces))
        default: result = nil
        }
        guard let result, result.isFinite else { return nil }
        return result
    }

    private static func curvePoints(labels: [String], values: [Any], day: Date) -> [CurvePoint] {
        let cal = Constants.madridCalendar
        let dayStart = cal.startOfDay(for: day)
        var firstInstant: [String: Date] = [:]
        var points: [CurvePoint] = []
        for (i, label) in labels.enumerated().prefix(min(labels.count, values.count)) {
            let parts = label.split(separator: ":")
            guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]),
                  (0..<24).contains(h), (0..<60).contains(m) else { continue }
            func matches(_ d: Date) -> Bool {
                let c = cal.dateComponents([.hour, .minute], from: d)
                return c.hour == h && c.minute == m && cal.isDate(d, inSameDayAs: dayStart)
            }
            var time: Date?
            if let first = firstInstant[label] {
                // Repeated label (fall-back day): the later instant.
                let later = first.addingTimeInterval(3600)
                time = matches(later) ? later : nil
            } else if let d = cal.date(bySettingHour: h, minute: m, second: 0, of: dayStart, matchingPolicy: .strict, repeatedTimePolicy: .first, direction: .forward),
                      matches(d) {
                time = d
                firstInstant[label] = d
            }
            // Nonexistent label (spring-forward gap): skip.
            guard let time else { continue }
            points.append(CurvePoint(time: time, kW: number(values[i])))
        }
        return points.sorted { $0.time < $1.time }
    }
}
