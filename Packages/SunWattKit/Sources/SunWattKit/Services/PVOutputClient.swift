import Foundation

/// Reads a system from pvoutput.org (API key + system ID, sent as headers). One request per fetch:
/// `getstatus.jsp?h=1` returns today's status history (system time zone) as text:
/// `date,time,energy,efficiency,power,average,normalised,consumption,power,temperature,voltage;...`
/// with `yyyymmdd`, `HH:mm`, energy in Wh since midnight and power in W; missing values are `NaN`. The second
/// "power" column is consumption, filled only when the uploader sends it (a meter at home): then `home` is set.
/// Today's grid import and export are worked out from the power readings (PVOutput has no such totals here).
/// PVOutput allows 60 requests an hour per key. Before the first upload of the day it answers
/// `400 Bad request 400: No status found`, which is a day without production, not an error.
public struct PVOutputClient: SolarDataSource {
    public static let host = "pvoutput.org"
    /// The latest status counts as "power now" only if it is this recent; older means the plant stopped uploading.
    public static let currentPowerMaxAge: TimeInterval = 30 * 60

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

    var statusURL: URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = Self.host
        components.path = "/service/r2/getstatus.jsp"
        components.queryItems = [
            URLQueryItem(name: "h", value: "1"),
            URLQueryItem(name: "limit", value: "288"),
            URLQueryItem(name: "asc", value: "1"),
        ]
        return components.url ?? URL(string: "https://invalid.invalid")!
    }

    public func fetch() async throws -> SolarSnapshot {
        var request = HTTPFetch.request(statusURL)
        request.setValue("text/plain", forHTTPHeaderField: "Accept")
        request.setValue(credentials.apiKey, forHTTPHeaderField: "X-Pvoutput-Apikey")
        request.setValue(credentials.siteID, forHTTPHeaderField: "X-Pvoutput-SystemId")
        let (data, response) = try await HTTPFetch.perform(http, request, redacting: credentials.apiKey)
        let date = now()
        if let status = (response as? HTTPURLResponse)?.statusCode {
            if status == 429 || (status == 403 && Self.isRateLimit(data)) { throw SunWattError.rateLimited }
            if status == 401 || status == 403 { throw SunWattError.solarCredentialsRejected }
            if status == 400, Self.isNoStatus(data) {
                return SolarSnapshot(currentPowerKW: 0, dailyEnergyKWh: 0, curve: [], fetchedAt: date, dataAsOf: nil)
            }
        }
        try HTTPFetch.checkStatus(response)
        return try Self.parse(data, fetchedAt: date, calendar: calendar)
    }

    static func isNoStatus(_ data: Data) -> Bool {
        String(decoding: data, as: UTF8.self).localizedCaseInsensitiveContains("no status found")
    }

    /// "Forbidden 403: Exceeded 60 requests per hour" is a rate limit, not a wrong key.
    static func isRateLimit(_ data: Data) -> Bool {
        String(decoding: data, as: UTF8.self).localizedCaseInsensitiveContains("exceeded")
    }

    /// Parses a status history body. Records that do not parse are skipped; no valid record at all is an error.
    public static func parse(_ data: Data, fetchedAt: Date, calendar: Calendar) throws -> SolarSnapshot {
        let text = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        struct Record { var time: Date; var energyWh: Double?; var powerW: Double?; var consumedWh: Double?; var consumptionW: Double? }
        let parsed = text.split(separator: ";").compactMap { line -> Record? in
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
            guard fields.count >= 5, let time = timestamp(day: fields[0], time: fields[1], calendar: calendar) else { return nil }
            // Instantaneous power; the interval average when it is missing.
            let power = SolarLocalTime.number(fields[4]) ?? (fields.count > 5 ? SolarLocalTime.number(fields[5]) : nil)
            return Record(
                time: time, energyWh: SolarLocalTime.number(fields[2]), powerW: power,
                consumedWh: fields.count > 7 ? SolarLocalTime.number(fields[7]) : nil,
                consumptionW: fields.count > 8 ? SolarLocalTime.number(fields[8]) : nil)
        }
        // `asc=1`: oldest first, so the second 02:xx of the autumn DST day moves one hour later.
        let records = SolarLocalTime.inOrder(parsed, time: \.time, moving: { record, time in
            var moved = record
            moved.time = time
            return moved
        }, calendar: calendar)
        guard !records.isEmpty else { throw SunWattError.badResponse("status") }

        let limit = fetchedAt.addingTimeInterval(5 * 60)
        let curve = records.map { CurvePoint(time: $0.time, kW: $0.time > limit ? nil : $0.powerW.map { $0 / 1000 }) }
        let latest = records.last { $0.time <= limit && $0.powerW != nil }
        let current: Double? = latest.map { record in
            fetchedAt.timeIntervalSince(record.time) <= currentPowerMaxAge ? (record.powerW ?? 0) / 1000 : 0
        }
        let energy = records.last { $0.time <= limit && $0.energyWh != nil }?.energyWh.map { $0 / 1000 }

        // Home consumption: only when the uploader sends it.
        let past = records.filter { $0.time <= limit }
        var home: HomeEnergy?
        if past.contains(where: { $0.consumptionW != nil || $0.consumedWh != nil }) {
            let latestUse = past.last { $0.consumptionW != nil }
            let recent = latestUse.map { fetchedAt.timeIntervalSince($0.time) <= currentPowerMaxAge } ?? false
            let useKW = recent ? latestUse?.consumptionW.map { $0 / 1000 } : nil
            let gridKW = recent ? latestUse.flatMap { r in r.consumptionW.map { ($0 - (r.powerW ?? 0)) / 1000 } } : nil
            // Import / export: what each interval used beyond (or below) what the panels made, interval average of
            // the two readings at its ends. Gaps over 30 minutes are skipped.
            var importKWh = 0.0, exportKWh = 0.0
            for (a, b) in zip(past, past.dropFirst()) {
                let hours = b.time.timeIntervalSince(a.time) / 3600
                guard hours > 0, hours <= 0.5, let ua = a.consumptionW, let ub = b.consumptionW else { continue }
                let net = ((ua + ub) - ((a.powerW ?? 0) + (b.powerW ?? 0))) / 2 / 1000
                if net > 0 { importKWh += net * hours } else { exportKWh -= net * hours }
            }
            let hasPairs = zip(past, past.dropFirst()).contains { $0.consumptionW != nil && $1.consumptionW != nil }
            home = HomeEnergy(
                consumptionKW: useKW,
                gridKW: gridKW,
                dailyConsumptionKWh: past.last { $0.consumedWh != nil }?.consumedWh.map { $0 / 1000 },
                dailyImportKWh: hasPairs ? importKWh : nil,
                dailyExportKWh: hasPairs ? exportKWh : nil,
                curve: records.map { CurvePoint(time: $0.time, kW: $0.time > limit ? nil : $0.consumptionW.map { $0 / 1000 }) })
        }
        return SolarSnapshot(
            stationName: nil,
            currentPowerKW: current,
            dailyEnergyKWh: energy,
            curve: curve,
            fetchedAt: fetchedAt,
            dataAsOf: curve.last { $0.kW != nil }?.time,
            home: home
        ).clampingProduction()
    }

    /// `yyyymmdd` + `HH:mm` in `calendar`'s time zone.
    private static func timestamp(day: String, time: String, calendar: Calendar) -> Date? {
        guard day.count == 8, day.allSatisfy(\.isNumber),
              let y = Int(day.prefix(4)), let m = Int(day.dropFirst(4).prefix(2)), let d = Int(day.suffix(2)) else { return nil }
        let hm = time.split(separator: ":").compactMap { Int($0) }
        guard hm.count == 2 else { return nil }
        return SolarLocalTime.date(year: y, month: m, day: d, hour: hm[0], minute: hm[1], calendar: calendar)
    }
}
