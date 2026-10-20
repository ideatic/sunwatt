import Foundation
import Testing
@testable import SunWattKit

/// FusionSolar Northbound API ("API account"): credentials, the session, parsing and the account.
@Suite struct NorthboundTests {
    private let now = madrid(2026, 10, 17, 13, 40)
    private let credentials = NorthboundCredentials(host: "", userName: "api_user", systemCode: "SYSTEMCODE99")!

    private func stub() throws -> StubHTTPClient {
        let http = StubHTTPClient()
        for (match, fixture) in northboundRoutes {
            http.on(match, .ok(try Fixture.data(fixture), headers: match.hasSuffix("login") ? ["xsrf-token": "token-1"] : [:]))
        }
        return http
    }

    @Test func credentialsOnlyGoToHuawei() {
        #expect(credentials.host == NorthboundCredentials.defaultHost)
        #expect(NorthboundCredentials(host: " https://INTL.fusionsolar.huawei.com/ ", userName: "u", systemCode: "c")?.host == "intl.fusionsolar.huawei.com")
        for bad in ["evil.example", "fusionsolar.huawei.com.evil.example", "eu5..fusionsolar.huawei.com", "eu5.fusionsolar.huawei.com:8080"] {
            #expect(NorthboundCredentials(host: bad, userName: "u", systemCode: "c") == nil, "\(bad)")
        }
        #expect(NorthboundCredentials(host: "", userName: " ", systemCode: "c") == nil)
        #expect(NorthboundCredentials(host: "", userName: "u", systemCode: "") == nil)
        #expect(!"\(credentials) \(String(reflecting: credentials))".contains("SYSTEMCODE99"))
    }

    @Test func fetchReadsThePlant() async throws {
        let http = try stub()
        let client = NorthboundClient(credentials: credentials, http: http, now: { now }, sessions: NorthboundSessionStore())
        let s = try await client.fetch()
        #expect(s.stationName == "Demo plant")
        #expect(s.currentPowerKW == 4.812 && s.dailyEnergyKWh == 14.37 && s.monthEnergyKWh == 412.6 && s.cumulativeEnergyKWh == 44150.55)
        // Hourly points (mid-hour, before now) and the reading now.
        #expect(s.curve.last == CurvePoint(time: now, kW: 4.812))
        #expect(s.curve.contains(CurvePoint(time: madrid(2026, 10, 17, 12, 30), kW: 4.9)))
        #expect(!s.curve.contains { $0.time > now })
        #expect(s.dataAsOf == now && s.fetchedAt == now)
        // Login first, with the account; the token on every later call; only the inverters asked for power.
        let requests = http.requests
        #expect(requests.first?.url?.path == "/thirdData/login")
        let login = try #require(requests.first?.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: String] })
        #expect(login == ["userName": "api_user", "systemCode": "SYSTEMCODE99"])
        #expect(requests.dropFirst().allSatisfy { $0.value(forHTTPHeaderField: "xsrf-token") == "token-1" && $0.httpMethod == "POST" })
        let power = try #require(requests.first { $0.url?.path == "/thirdData/getDevRealKpi" }?.httpBody)
        let body = try #require(try JSONSerialization.jsonObject(with: power) as? [String: Any])
        #expect(body["devIds"] as? String == "1000000033812345" && body["devTypeId"] as? Int == 1)
    }

    @Test func laterFetchesReuseTheSession() async throws {
        let http = try stub()
        let sessions = NorthboundSessionStore()
        _ = try await NorthboundClient(credentials: credentials, http: http, now: { now }, sessions: sessions).fetch()
        let first = http.requests.count
        _ = try await NorthboundClient(credentials: credentials, http: http, now: { now.addingTimeInterval(600) }, sessions: sessions).fetch()
        let later = http.requests.dropFirst(first).compactMap { $0.url?.lastPathComponent }
        // No login, plant list, device list or hourly curve again: only the plant KPIs and the inverters' power.
        #expect(later == ["getStationRealKpi", "getDevRealKpi"])
        // An hour later the curve is read again.
        _ = try await NorthboundClient(credentials: credentials, http: http, now: { now.addingTimeInterval(3700) }, sessions: sessions).fetch()
        #expect(http.requests.last?.url?.lastPathComponent == "getKpiStationHour")
    }

    // Second review, finding 6: the app and the widget extension (two processes) share one session through the keychain.
    @Test func anotherProcessReusesTheSession() async throws {
        let http = try stub()
        let keychain = MemorySecrets()
        _ = try await NorthboundClient(credentials: credentials, http: http, now: { now },
                                       sessions: NorthboundSessionStore(persistence: keychain)).fetch()
        let first = http.requests.count
        // A new process: a new store, the same keychain.
        _ = try await NorthboundClient(credentials: credentials, http: http, now: { now.addingTimeInterval(600) },
                                       sessions: NorthboundSessionStore(persistence: keychain)).fetch()
        let later = http.requests.dropFirst(first).compactMap { $0.url?.lastPathComponent }
        #expect(later == ["getStationRealKpi", "getDevRealKpi"])
        #expect(http.requests.dropFirst(first).allSatisfy { $0.value(forHTTPHeaderField: "xsrf-token") == "token-1" })
    }

    @Test func anEndedSessionLogsInAgainOnce() async throws {
        let http = ScriptedHTTP { request, count in
            switch request.url?.lastPathComponent {
            case "login": return (try Fixture.data("northbound-login-synthetic.json"), ["xsrf-token": "token-\(count("login"))"])
            // The first KPI call finds the session ended (another login elsewhere).
            case "getStationRealKpi" where count("getStationRealKpi") == 1:
                return (Data(#"{"success":false,"failCode":305,"data":null}"#.utf8), [:])
            default:
                let fixture = northboundRoutes.first { request.url!.path.hasSuffix($0.0) }!.1
                return (try Fixture.data(fixture), [:])
            }
        }
        let s = try await NorthboundClient(credentials: credentials, http: http, now: { now }, sessions: NorthboundSessionStore()).fetch()
        #expect(s.dailyEnergyKWh == 14.37)
        #expect(http.paths.filter { $0 == "login" }.count == 2)
        #expect(http.lastToken == "token-2")
    }

    @Test func failCodes() async throws {
        // Routes are first-match: the 407 goes before the fixtures.
        let http = StubHTTPClient()
        http.on("getStationRealKpi", .ok(try Fixture.data("northbound-failcode-synthetic.json")))
        for (match, fixture) in northboundRoutes { http.on(match, .ok(try Fixture.data(fixture), headers: ["xsrf-token": "t"])) }
        await #expect(throws: SunWattError.rateLimited) {
            try await NorthboundClient(credentials: credentials, http: http, now: { now }, sessions: NorthboundSessionStore()).fetch()
        }
        // A refused login: wrong user name or system code.
        let refused = StubHTTPClient(.ok(Data(#"{"success":false,"failCode":20001,"data":null}"#.utf8)))
        await #expect(throws: SunWattError.northboundAccountRejected) {
            try await NorthboundClient(credentials: credentials, http: refused, now: { now }, sessions: NorthboundSessionStore()).fetch()
        }
        // A rate limit on a curve read keeps the fetch going without the curve.
        let curveLimited = StubHTTPClient()
        curveLimited.on("getKpiStationHour", .ok(try Fixture.data("northbound-failcode-synthetic.json")))
        for (match, fixture) in northboundRoutes { curveLimited.on(match, .ok(try Fixture.data(fixture), headers: ["xsrf-token": "t"])) }
        let s = try await NorthboundClient(credentials: credentials, http: curveLimited, now: { now }, sessions: NorthboundSessionStore()).fetch()
        #expect(s.curve == [CurvePoint(time: now, kW: 4.812)])
    }

    @Test func newerServersListPlantsPaged() async throws {
        let http = StubHTTPClient()
        http.on("getStationList", .ok(Data(#"{"success":false,"failCode":401,"data":null}"#.utf8)))
        http.on("/thirdData/stations", .ok(Data(#"{"success":true,"failCode":0,"data":{"list":[{"plantCode":"NE=12345678","plantName":"Paged plant"}],"pageCount":1}}"#.utf8)))
        for (match, fixture) in northboundRoutes { http.on(match, .ok(try Fixture.data(fixture), headers: ["xsrf-token": "t"])) }
        let s = try await NorthboundClient(credentials: credentials, http: http, now: { now }, sessions: NorthboundSessionStore()).fetch()
        #expect(s.stationName == "Paged plant")
    }

    @Test func tokenFromTheCookie() {
        let response = HTTPURLResponse(url: URL(string: "https://x.fusionsolar.huawei.com")!, statusCode: 200, httpVersion: nil,
                                       headerFields: ["Set-Cookie": "XSRF-TOKEN=abc123; Path=/; Secure"])!
        #expect(NorthboundClient.token(from: response) == "abc123")
    }

    @Test func parsingTolerates() {
        #expect(NorthboundClient.number("12.5") == 12.5 && NorthboundClient.number(3) == 3 && NorthboundClient.number("-") == nil)
        #expect(NorthboundClient.number(NSNull()) == nil)
        let devices: [[String: Any]] = [["id": "7", "devTypeId": 38], ["id": 8, "devTypeId": 62]]
        #expect(NorthboundClient.inverters(devices).map(\.id) == ["7"])
        let readings: [[String: Any]] = [["dataItemMap": ["active_power": "1.5"]], ["dataItemMap": ["active_power": 2]]]
        #expect(NorthboundClient.activePower(readings) == 3.5)
    }

    // MARK: Account

    @Test func accountAndProfile() throws {
        var plant = SolarPlant(provider: .fusionSolarAPI, siteID: "api_user", apiKey: "SYSTEMCODE99")
        let account = try #require(SolarAccount(plant: plant))
        #expect(account == .fusionSolarAPI(credentials))
        #expect(account.client() is NorthboundClient)
        #expect(account.provider.minimumFetchInterval == 600)
        #expect(account.provider.pickerGroup == .fusionSolar)
        #expect(!SolarProvider.pickerCases.contains(.fusionSolarAPI) && SolarProvider.pickerCases == [.fusionSolar, .solarEdge, .pvOutput, .fronius])
        #expect(account.provider.profile.localInverter == .huawei)
        // With the dongle at home: live data on top, the Huawei reader.
        plant.huaweiLocalHost = "192.168.1.50"
        let local = try #require(SolarAccount(plant: plant))
        #expect(local.client() is LocalInverterSolarClient && local.localReader() is HuaweiModbusClient)
        #expect(local.cacheID == account.cacheID)
        // Typed but not valid: the account error.
        var settings = SunWattSettings(solarProvider: .fusionSolarAPI, solarSiteID: "api_user")
        #expect(SolarAccount(settings: settings) == nil && settings.hasSolarInput)
        #expect(settings.invalidSolarInputError == .invalidNorthboundAccount)
        settings.solarAPIKey = "SYSTEMCODE99"
        settings.northboundHost = "evil.example"
        #expect(SolarAccount(settings: settings) == nil)
    }
}

/// An HTTP client answering from a closure; `count(path)` is how many requests that interface has had so far.
final class ScriptedHTTP: HTTPClient, @unchecked Sendable {
    private let lock = NSLock()
    private var counts: [String: Int] = [:]
    private(set) var paths: [String] = []
    private(set) var lastToken: String?
    let reply: (URLRequest, (String) -> Int) throws -> (Data, [String: String])

    init(_ reply: @escaping (URLRequest, (String) -> Int) throws -> (Data, [String: String])) {
        self.reply = reply
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let path = request.url?.lastPathComponent ?? ""
        let snapshot: [String: Int] = lock.withLock {
            counts[path, default: 0] += 1
            paths.append(path)
            if let token = request.value(forHTTPHeaderField: "xsrf-token") { lastToken = token }
            return counts
        }
        let (data, headers) = try reply(request) { snapshot[$0] ?? 0 }
        return (data, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: headers)!)
    }
}
