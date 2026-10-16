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
        var request = URLRequest(url: endpoint.apiURL)
        request.timeoutInterval = 20
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await http.data(for: request)
        } catch let error as URLError {
            throw HomeDashError.network(error.localizedDescription)
        }
        if let httpResponse = response as? HTTPURLResponse, !(200..<300).contains(httpResponse.statusCode) {
            throw HomeDashError.http(status: httpResponse.statusCode)
        }
        let date = now()
        return try Self.parse(data, fetchedAt: date, day: date)
    }

    /// Parses a raw kiosk response body (outer JSON with HTML-escaped `data` string).
    /// `day` is any instant in the Europe/Madrid day the curve labels ("HH:mm") belong to.
    public static func parse(_ data: Data, fetchedAt: Date, day: Date) throws -> SolarSnapshot {
        guard let outer = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw HomeDashError.badResponse("JSON externo")
        }
        guard (outer["success"] as? Bool) == true else {
            throw HomeDashError.badResponse("success=false")
        }
        guard let escaped = outer["data"] as? String, !escaped.isEmpty else {
            throw HomeDashError.badResponse("falta data")
        }
        let inner = HTMLEntities.unescape(escaped)
        guard let innerData = inner.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: innerData)) as? [String: Any] else {
            throw HomeDashError.badResponse("data no es JSON")
        }

        let kpi = root["realKpi"] as? [String: Any] ?? [:]
        let curveObject = root["powerCurve"] as? [String: Any] ?? [:]
        let labels = (curveObject["xAxis"] as? [Any])?.compactMap { $0 as? String } ?? []
        let values = curveObject["activePower"] as? [Any] ?? []
        let station = (root["stationOverview"] as? [String: Any])?["stationName"] as? String

        let curve = curvePoints(labels: labels, values: values, day: day)
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
        for (i, label) in labels.enumerated() {
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
            points.append(CurvePoint(time: time, kW: i < values.count ? number(values[i]) : nil))
        }
        return points.sorted { $0.time < $1.time }
    }
}
