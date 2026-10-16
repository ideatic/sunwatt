import Foundation
import Testing
@testable import HomeDashKit

/// Wraps an inner JSON string the way the kiosk does: HTML-escaped inside `data`.
func kioskBody(inner: String, success: Bool = true) -> Data {
    let escaped = inner.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "\"", with: "&quot;")
    let outer: [String: Any] = ["data": escaped, "success": success, "failCode": 0]
    return try! JSONSerialization.data(withJSONObject: outer)
}

func kioskCurveJSON(labels: [String], values: [String]) -> String {
    let x = labels.map { "\"\($0)\"" }.joined(separator: ",")
    let y = values.map { "\"\($0)\"" }.joined(separator: ",")
    return "{\"powerCurve\":{\"xAxis\":[\(x)],\"activePower\":[\(y)]}}"
}

@Suite struct KioskParsingTests {
    let day = madrid(2026, 10, 17, 9, 0)

    @Test func nightFixture() throws {
        let data = try Fixture.data("kiosk-night.json")
        let fetched = madrid(2026, 10, 17, 1, 20)
        let s = try FusionSolarKioskClient.parse(data, fetchedAt: fetched, day: day)
        #expect(s.curve.count == 288)
        #expect(s.curve.allSatisfy { $0.kW == nil })
        #expect(s.currentPowerKW == 0)
        #expect(s.dailyEnergyKWh == 0)
        #expect(s.dataAsOf == nil)
        #expect(s.fetchedAt == fetched)
        #expect(s.stationName == "Demo Station")
        #expect(s.curve.first?.time == madrid(2026, 10, 17, 0, 0))
        #expect(s.curve.last?.time == madrid(2026, 10, 17, 23, 55))
        #expect(s.isIdle)
        #expect(s.monthEnergyKWh != nil && s.yearEnergyKWh != nil && s.cumulativeEnergyKWh != nil)
    }

    @Test func syntheticDayFixture() throws {
        let data = try Fixture.data("kiosk-day-synthetic.json")
        let s = try FusionSolarKioskClient.parse(data, fetchedAt: madrid(2026, 10, 17, 13, 40), day: day)
        #expect(s.curve.count == 288)
        #expect(s.currentPowerKW == 4.812)
        #expect(s.dailyEnergyKWh == 14.37)
        #expect(s.monthEnergyKWh == 697.33)
        #expect(s.yearEnergyKWh == 17056.36)
        #expect(s.cumulativeEnergyKWh == 44159.44)
        #expect(s.dataAsOf == madrid(2026, 10, 17, 13, 30))
        #expect(s.latestPoint?.time == madrid(2026, 10, 17, 13, 30))
        let peak = try #require(s.peakKW)
        #expect(peak > 4.9 && peak <= 5.0)
        #expect(s.curve.first { $0.time == madrid(2026, 10, 17, 7, 55) }?.kW == nil)
        #expect(s.curve.first { $0.time == madrid(2026, 10, 17, 8, 0) }?.kW != nil)
        #expect(s.curve.filter { $0.time > madrid(2026, 10, 17, 13, 30) }.allSatisfy { $0.kW == nil })
        #expect(!s.isIdle)
    }

    @Test func stringsAndNumbersInRealKpi() throws {
        let inner = """
        {"realKpi":{"realTimePower":"4.81","dailyEnergy":12.5,"monthEnergy":"300","yearEnergy":null,"cumulativeEnergy":"n/a"}}
        """
        let s = try FusionSolarKioskClient.parse(kioskBody(inner: inner), fetchedAt: day, day: day)
        #expect(s.currentPowerKW == 4.81)
        #expect(s.dailyEnergyKWh == 12.5)
        #expect(s.monthEnergyKWh == 300)
        #expect(s.yearEnergyKWh == nil)
        #expect(s.cumulativeEnergyKWh == nil)
        #expect(s.curve.isEmpty)
    }

    @Test func missingMarkersBecomeNil() throws {
        let inner = """
        {"powerCurve":{"xAxis":["10:00","10:05","10:10","10:15","10:20","10:25"],"activePower":[1.5,"2.25","-","--","",null]}}
        """
        let s = try FusionSolarKioskClient.parse(kioskBody(inner: inner), fetchedAt: madrid(2026, 10, 17, 12, 0), day: day)
        #expect(s.curve.map(\.kW) == [1.5, 2.25, nil, nil, nil, nil])
        #expect(s.dataAsOf == madrid(2026, 10, 17, 10, 5))
    }

    @Test func successFalseThrows() {
        let body = kioskBody(inner: "{}", success: false)
        #expect(throws: HomeDashError.self) { try FusionSolarKioskClient.parse(body, fetchedAt: day, day: day) }
    }

    @Test func missingDataThrows() {
        let body = Data(#"{"success":true,"failCode":0}"#.utf8)
        #expect(throws: HomeDashError.self) { try FusionSolarKioskClient.parse(body, fetchedAt: day, day: day) }
    }

    @Test func malformedInnerThrows() {
        let body = Data(#"{"data":"this is &quot;not json","success":true,"failCode":0}"#.utf8)
        #expect(throws: HomeDashError.self) { try FusionSolarKioskClient.parse(body, fetchedAt: day, day: day) }
        #expect(throws: HomeDashError.self) { try FusionSolarKioskClient.parse(Data("garbage".utf8), fetchedAt: day, day: day) }
    }

    @Test func springForwardDay() throws {
        // 2026-03-29: 02:00-02:59 does not exist in Madrid.
        let labels = ["01:55", "02:00", "02:30", "03:00", "03:05"]
        let body = kioskBody(inner: kioskCurveJSON(labels: labels, values: ["1", "2", "3", "4", "5"]))
        let s = try FusionSolarKioskClient.parse(body, fetchedAt: day, day: madrid(2026, 3, 29, 12, 0))
        #expect(s.curve.count == 3)
        #expect(s.curve.map(\.kW) == [1, 4, 5])
        for (a, b) in zip(s.curve, s.curve.dropFirst()) { #expect(a.time < b.time) }
        #expect(s.curve[0].time == madrid(2026, 3, 29, 1, 55))
        #expect(s.curve[1].time == madrid(2026, 3, 29, 3, 0))
    }

    @Test func fallBackDay() throws {
        // 2026-10-25: 02:00-02:59 happens twice. Repeated label maps to the later instant.
        let labels = ["01:55", "02:00", "02:30", "02:55", "02:00", "02:30", "03:00"]
        let body = kioskBody(inner: kioskCurveJSON(labels: labels, values: ["1", "2", "3", "4", "5", "6", "7"]))
        let s = try FusionSolarKioskClient.parse(body, fetchedAt: madrid(2026, 10, 25, 12, 0), day: madrid(2026, 10, 25, 12, 0))
        #expect(s.curve.count == 7)
        for (a, b) in zip(s.curve, s.curve.dropFirst()) { #expect(a.time < b.time) }
        let first = s.curve[1].time
        let second = s.curve[4].time
        #expect(second.timeIntervalSince(first) == 3600)
        #expect(first == madrid(2026, 10, 25, 2, 0))
        #expect(s.curve.last?.time == madrid(2026, 10, 25, 3, 0))
        #expect(s.curve.map(\.kW) == [1, 2, 3, 4, 5, 6, 7])
    }

    @Test func fullDayLabelsOnDSTDaysDoNotCrash() throws {
        let labels = (0..<288).map { String(format: "%02d:%02d", $0 / 12, ($0 % 12) * 5) }
        let body = kioskBody(inner: kioskCurveJSON(labels: labels, values: Array(repeating: "1.0", count: 288)))
        for d in [madrid(2026, 3, 29, 12, 0), madrid(2026, 10, 25, 12, 0)] {
            let s = try FusionSolarKioskClient.parse(body, fetchedAt: d, day: d)
            #expect(!s.curve.isEmpty)
            for (a, b) in zip(s.curve, s.curve.dropFirst()) { #expect(a.time < b.time) }
        }
    }
}

@Suite struct KioskFetchTests {
    let endpoint = KioskEndpoint(host: "host.example", kk: "TOKEN+1")
    let fixedNow = madrid(2026, 10, 17, 14, 0)

    private func client(_ http: StubHTTPClient) -> FusionSolarKioskClient {
        let date = fixedNow
        return FusionSolarKioskClient(endpoint: endpoint, http: http, now: { date })
    }

    @Test func fetchRequestsAPIURLAndParses() async throws {
        let http = StubHTTPClient()
        http.on("station-kiosk-file", .ok(try Fixture.data("kiosk-day-synthetic.json")))
        let s = try await client(http).fetch()
        #expect(http.requestedURLs == [endpoint.apiURL])
        #expect(http.requestedURLs.first?.absoluteString == "https://host.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=TOKEN%2B1")
        #expect(s.fetchedAt == fixedNow)
        #expect(s.dataAsOf == madrid(2026, 10, 17, 13, 30))
        #expect(s.curve.first?.time == madrid(2026, 10, 17, 0, 0))
    }

    @Test func http500() async {
        let http = StubHTTPClient()
        http.on("station-kiosk-file", .ok(Data(), status: 500))
        await #expect(throws: HomeDashError.http(status: 500)) { try await client(http).fetch() }
    }

    @Test func urlErrorBecomesNetwork() async {
        let http = StubHTTPClient(.failure(URLError(.notConnectedToInternet)))
        do {
            _ = try await client(http).fetch()
            Issue.record("expected throw")
        } catch let HomeDashError.network(detail) {
            #expect(!detail.isEmpty)
        } catch {
            Issue.record("wrong error: \(error)")
        }
    }

    @Test func successFalseFromServer() async throws {
        let http = StubHTTPClient()
        http.on("station-kiosk-file", .ok(kioskBody(inner: "{}", success: false)))
        do {
            _ = try await client(http).fetch()
            Issue.record("expected throw")
        } catch HomeDashError.badResponse {
        } catch {
            Issue.record("wrong error: \(error)")
        }
    }
}
