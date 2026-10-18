import CryptoKit
import Foundation

// Huawei FusionSolar Northbound API ("SmartPVMS Northbound API", the "API account" an installer creates in FusionSolar
// under Company Management → Northbound Management). Every call is a POST to https://<server>/thirdData/<interface>
// with a JSON body; answers are HTTP 200 with {"success", "failCode", "data", "message"}.
//
// - login: {"userName", "systemCode"} → the session token in the "xsrf-token" header (or the XSRF-TOKEN cookie), sent
//   back as the "xsrf-token" header. One session per account: a new login ends the previous one (failCode 305 →
//   log in again once).
// - getStationList (older servers) or stations (newer): the plant; the first one is used.
// - getStationRealKpi {"stationCodes"}: day_power, month_power, total_power (kWh).
// - getDevList {"stationCodes"} + getDevRealKpi {"devIds", "devTypeId"}: active_power (kW) of the inverters
//   (devTypeId 1 string inverter, 38 residential inverter); their sum is the power now.
// - getKpiStationHour {"stationCodes", "collectTime" (ms)}: inverter_power per hour (kWh in the hour ≈ average kW): the
//   curve. Re-read at most once an hour.
// Each interface is rate-limited (failCode 407): calls go one after another, and `SolarProviderProfile` limits fetches
// to one every 10 minutes.

/// User name + system code of a FusionSolar API account and the server it belongs to. The system code is a secret:
/// descriptions and reflection hide it.
public struct NorthboundCredentials: Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    /// The European server; others: intl., la5., au5. … .fusionsolar.huawei.com.
    public static let defaultHost = "eu5.fusionsolar.huawei.com"

    public var host: String
    public var userName: String
    public var systemCode: String

    /// Nil when the user name or the system code is empty, or the server is not a fusionsolar.huawei.com host: the
    /// credentials are only ever sent to Huawei. An empty server means `defaultHost`; "https://" and a path are dropped.
    public init?(host: String, userName: String, systemCode: String) {
        var server = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        for prefix in ["https://", "http://"] where server.hasPrefix(prefix) { server.removeFirst(prefix.count) }
        if let slash = server.firstIndex(of: "/") { server = String(server[..<slash]) }
        if server.isEmpty { server = Self.defaultHost }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789.-")
        guard server == "fusionsolar.huawei.com" || server.hasSuffix(".fusionsolar.huawei.com"),
              server.unicodeScalars.allSatisfy(allowed.contains), !server.contains("..") else { return nil }
        let user = userName.trimmingCharacters(in: .whitespacesAndNewlines)
        let code = systemCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...128).contains(user.count), (1...128).contains(code.count) else { return nil }
        self.host = server
        self.userName = user
        self.systemCode = code
    }

    var baseURL: URL { URL(string: "https://\(host)/thirdData/") ?? URL(string: "https://\(Self.defaultHost)/thirdData/")! }

    /// One-way key of the account (session cache).
    var key: String {
        SHA256.hash(data: Data("\(host)\n\(userName)\n\(systemCode)".utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public var description: String { "NorthboundCredentials(host: \(host), userName: \(userName), systemCode: ***)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: ["host": host, "userName": userName, "systemCode": "***"], displayStyle: .struct)
    }
}

/// What a client keeps between fetches: the session token, the plant, its inverters and today's hourly curve. Keyed by
/// account. With `persistence` (the shared store: the keychain of the App Group) the app and the widget extension share
/// it: Huawei allows one session per account, so separate logins would end each other's session, and a new widget
/// process would need the login, plant and device calls again within its deadline. The token is a secret, so it is
/// only ever kept in the keychain (AGENTS.md rule 1).
public actor NorthboundSessionStore {
    public static let shared = NorthboundSessionStore(persistence: KeychainSecretStore(accessGroup: Constants.appGroupID))

    struct Station: Codable, Sendable, Hashable {
        var code: String
        var name: String?
    }

    struct Device: Codable, Sendable, Hashable {
        var id: String
        var typeID: Int
    }

    struct Session: Codable, Sendable {
        var token: String?
        var station: Station?
        var inverters: [Device]?
        var curve: [CurvePoint] = []
        var curveFetchedAt: Date?
    }

    private var sessions: [String: Session] = [:]
    private let persistence: SecretStore?

    /// `persistence`: where sessions are shared between processes; nil keeps them in this process only (tests).
    public init(persistence: SecretStore? = nil) {
        self.persistence = persistence
    }

    private static func itemName(_ key: String) -> String { "northbound.\(key)" }

    /// The latest session: from the keychain when shared (another process may have logged in since), else from memory.
    func session(_ key: String) -> Session {
        if let persistence, let text = persistence.secret(Self.itemName(key)),
           let stored = try? JSONDecoder().decode(Session.self, from: Data(text.utf8)) {
            sessions[key] = stored
            return stored
        }
        return sessions[key] ?? Session()
    }

    func update(_ key: String, _ change: @Sendable (inout Session) -> Void) {
        var session = self.session(key)
        change(&session)
        sessions[key] = session
        if let persistence, let data = try? JSONEncoder().encode(session) {
            persistence.setSecret(String(decoding: data, as: UTF8.self), for: Self.itemName(key))
        }
    }
}

public struct NorthboundClient: SolarDataSource {
    public let credentials: NorthboundCredentials
    let http: HTTPClient
    let now: @Sendable () -> Date
    let sessions: NorthboundSessionStore

    /// The hourly curve is re-read at most this often (the most limited interface; its points are hourly anyway).
    static let curveRefresh: TimeInterval = 60 * 60
    /// Inverter device types: string inverter, residential inverter.
    static let inverterTypes: Set<Int> = [1, 38]

    public init(credentials: NorthboundCredentials, http: HTTPClient = URLSession.sunWatt,
                now: @escaping @Sendable () -> Date = { Date() }, sessions: NorthboundSessionStore = .shared) {
        self.credentials = credentials
        self.http = http
        self.now = now
        self.sessions = sessions
    }

    public func fetch() async throws -> SolarSnapshot {
        let date = now()
        let station = try await station()
        let kpi = Self.stationKpi(try await call("getStationRealKpi", ["stationCodes": station.code]), code: station.code)
        let power = try await currentPower(station: station)
        var curve = await hourlyCurve(station: station, at: date)
        if let power {
            curve.append(CurvePoint(time: date, kW: power))
        }
        return SolarSnapshot(
            stationName: station.name, currentPowerKW: power, dailyEnergyKWh: kpi.day, monthEnergyKWh: kpi.month,
            cumulativeEnergyKWh: kpi.total, curve: curve, fetchedAt: date, dataAsOf: power == nil ? nil : date
        ).clampingProduction()
    }

    // MARK: Plant, inverters, curve

    private func station() async throws -> NorthboundSessionStore.Station {
        if let station = await sessions.session(credentials.key).station { return station }
        let station: NorthboundSessionStore.Station
        do {
            station = try Self.firstStation(fromList: try await call("getStationList", [:]))
        } catch SunWattError.northboundAccountRejected {
            // Newer servers answer "no access" to getStationList and offer the paged "stations" instead.
            station = try Self.firstStation(fromPaged: try await call("stations", ["pageNo": 1]))
        }
        await sessions.update(credentials.key) { $0.station = station }
        return station
    }

    /// Sum of the inverters' active power (kW); nil when the plant has no inverter the API reports.
    private func currentPower(station: NorthboundSessionStore.Station) async throws -> Double? {
        var inverters = await sessions.session(credentials.key).inverters
        if inverters == nil {
            let list = Self.inverters(try await call("getDevList", ["stationCodes": station.code]))
            inverters = list
            await sessions.update(credentials.key) { $0.inverters = list }
        }
        guard let inverters, !inverters.isEmpty else { return nil }
        var total: Double?
        for type in Set(inverters.map(\.typeID)).sorted() {
            let ids = inverters.filter { $0.typeID == type }.map(\.id).joined(separator: ",")
            if let kW = Self.activePower(try await call("getDevRealKpi", ["devIds": ids, "devTypeId": type])) {
                total = (total ?? 0) + kW
            }
        }
        return total
    }

    /// Today's hourly curve, read again once an hour; a failure (e.g. rate limit) keeps the last one.
    private func hourlyCurve(station: NorthboundSessionStore.Station, at date: Date) async -> [CurvePoint] {
        let calendar = Constants.madridCalendar
        let session = await sessions.session(credentials.key)
        let today = session.curve.filter { calendar.isDate($0.time, inSameDayAs: date) && $0.time < date }
        if let fetched = session.curveFetchedAt, calendar.isDate(fetched, inSameDayAs: date),
           date.timeIntervalSince(fetched) < Self.curveRefresh {
            return today
        }
        let body: [String: any Sendable] = ["stationCodes": station.code, "collectTime": Int64(date.timeIntervalSince1970 * 1000)]
        guard let data = try? await call("getKpiStationHour", body) else { return today }
        let points = Self.hourlyPoints(data, day: date, until: date)
        await sessions.update(credentials.key) {
            $0.curve = points
            $0.curveFetchedAt = date
        }
        return points
    }

    // MARK: Calls

    /// Response of an interface: `data`, after the session and error handling.
    private func call(_ interface: String, _ body: [String: any Sendable]) async throws -> Any? {
        let token: String
        if let saved = await sessions.session(credentials.key).token {
            token = saved
        } else {
            token = try await login()
        }
        do {
            return try await post(interface, body, token: token)
        } catch NorthboundFailure.sessionExpired {
            // Another login (the app, a widget, another tool) ended this session: log in again, once.
            return try await post(interface, body, token: try await login())
        }
    }

    private enum NorthboundFailure: Error { case sessionExpired }

    private func login() async throws -> String {
        var request = HTTPFetch.request(credentials.baseURL.appendingPathComponent("login"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["userName": credentials.userName, "systemCode": credentials.systemCode])
        let (data, response) = try await HTTPFetch.perform(http, request, redacting: credentials.systemCode)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        if status == 401 || status == 403 { throw SunWattError.northboundAccountRejected }
        try HTTPFetch.checkStatus(response)
        let answer = try Self.answer(data)
        if answer.failCode == 407 { throw SunWattError.rateLimited }
        guard answer.succeeded else { throw SunWattError.northboundAccountRejected }
        guard let token = Self.token(from: response) else { throw SunWattError.badResponse("login token") }
        await sessions.update(credentials.key) { $0.token = token }
        return token
    }

    private func post(_ interface: String, _ body: [String: any Sendable], token: String) async throws -> Any? {
        var request = HTTPFetch.request(credentials.baseURL.appendingPathComponent(interface))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "xsrf-token")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await HTTPFetch.perform(http, request, redacting: credentials.systemCode)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        if status == 401 || status == 403 { throw SunWattError.northboundAccountRejected }
        try HTTPFetch.checkStatus(response)
        let answer = try Self.answer(data)
        switch answer.failCode {
        case 305:
            await sessions.update(credentials.key) { $0.token = nil }
            throw NorthboundFailure.sessionExpired
        case 407: throw SunWattError.rateLimited
        // No rights to this interface or plant.
        case 401, 20056: throw SunWattError.northboundAccountRejected
        default: break
        }
        guard answer.succeeded else { throw SunWattError.badResponse("failCode \(answer.failCode)") }
        return answer.data
    }

    // MARK: Parsing (pure, tested with fixtures)

    struct Answer {
        var succeeded: Bool
        var failCode: Int
        var data: Any?
    }

    static func answer(_ data: Data) throws -> Answer {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw SunWattError.badResponse("not JSON")
        }
        let failCode = number(root["failCode"]).map { Int($0) } ?? 0
        let success = root["success"] as? Bool ?? (failCode == 0)
        return Answer(succeeded: success && failCode == 0, failCode: failCode, data: root["data"])
    }

    /// The token from the "xsrf-token" header, else from the XSRF-TOKEN cookie.
    static func token(from response: URLResponse) -> String? {
        guard let http = response as? HTTPURLResponse else { return nil }
        if let token = http.value(forHTTPHeaderField: "xsrf-token"), !token.isEmpty { return token }
        let cookies = http.value(forHTTPHeaderField: "Set-Cookie") ?? ""
        guard let range = cookies.range(of: "XSRF-TOKEN=") else { return nil }
        let value = cookies[range.upperBound...].prefix { $0 != ";" && $0 != "," }
        return value.isEmpty ? nil : String(value)
    }

    static func firstStation(fromList data: Any?) throws -> NorthboundSessionStore.Station {
        guard let list = data as? [[String: Any]], let first = list.first,
              let code = text(first["stationCode"]) else { throw SunWattError.unavailable }
        return NorthboundSessionStore.Station(code: code, name: text(first["stationName"]).map(HTMLEntities.unescape))
    }

    static func firstStation(fromPaged data: Any?) throws -> NorthboundSessionStore.Station {
        guard let page = data as? [String: Any], let list = page["list"] as? [[String: Any]], let first = list.first,
              let code = text(first["plantCode"]) else { throw SunWattError.unavailable }
        return NorthboundSessionStore.Station(code: code, name: text(first["plantName"]).map(HTMLEntities.unescape))
    }

    static func stationKpi(_ data: Any?, code: String) -> (day: Double?, month: Double?, total: Double?) {
        let items = data as? [[String: Any]] ?? []
        let item = items.first { text($0["stationCode"]) == code } ?? items.first
        let map = item?["dataItemMap"] as? [String: Any] ?? [:]
        return (number(map["day_power"]), number(map["month_power"]), number(map["total_power"]))
    }

    static func inverters(_ data: Any?) -> [NorthboundSessionStore.Device] {
        (data as? [[String: Any]] ?? []).compactMap { item in
            guard let id = text(item["id"]), let type = number(item["devTypeId"]).map({ Int($0) }),
                  inverterTypes.contains(type) else { return nil }
            return NorthboundSessionStore.Device(id: id, typeID: type)
        }
    }

    /// Sum of `active_power` over the devices in the answer; nil when none reports it.
    static func activePower(_ data: Any?) -> Double? {
        let values = (data as? [[String: Any]] ?? []).compactMap { number(($0["dataItemMap"] as? [String: Any])?["active_power"]) }
        return values.isEmpty ? nil : values.reduce(0, +)
    }

    /// One point per hour of `day` before `until`, in the middle of the hour (the value is the hour's energy).
    static func hourlyPoints(_ data: Any?, day: Date, until: Date) -> [CurvePoint] {
        let calendar = Constants.madridCalendar
        let points = (data as? [[String: Any]] ?? []).compactMap { item -> CurvePoint? in
            guard let ms = number(item["collectTime"]) else { return nil }
            let time = Date(timeIntervalSince1970: ms / 1000).addingTimeInterval(30 * 60)
            guard calendar.isDate(time, inSameDayAs: day), time < until else { return nil }
            let kWh = number((item["dataItemMap"] as? [String: Any])?["inverter_power"])
            return CurvePoint(time: time, kW: kWh)
        }
        var seen = Set<Date>()
        return points.sorted { $0.time < $1.time }.filter { seen.insert($0.time).inserted }
    }

    /// Numbers come as numbers or strings ("12.5"); null, "" and "-" are no value.
    static func number(_ value: Any?) -> Double? {
        switch value {
        case let number as NSNumber: return number.doubleValue.isFinite ? number.doubleValue : nil
        case let text as String: return Double(text.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    static func text(_ value: Any?) -> String? {
        switch value {
        case let text as String: return text.isEmpty ? nil : text
        case let number as NSNumber: return number.stringValue
        default: return nil
        }
    }
}
