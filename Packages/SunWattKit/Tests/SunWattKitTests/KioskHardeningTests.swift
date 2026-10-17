import Foundation
import Testing
@testable import SunWattKit

private func labels288() -> [String] {
    (0..<288).map { String(format: "%02d:%02d", $0 / 12, ($0 % 12) * 5) }
}

private struct LeakyError: Error, LocalizedError {
    let text: String
    var errorDescription: String? { text }
}

@Suite struct KioskHardeningTests {
    let endpoint = KioskEndpoint(host: "host.example", kk: "SECRETKK")
    let fixedNow = madrid(2026, 10, 17, 14, 0)

    private func client(_ http: StubHTTPClient, now: Date? = nil) -> FusionSolarKioskClient {
        let date = now ?? fixedNow
        return FusionSolarKioskClient(endpoint: endpoint, http: http, now: { date })
    }

    // MARK: request + cancellation

    @Test func requestsIgnoreLocalCache() async throws {
        let kiosk = StubHTTPClient()
        kiosk.on("station-kiosk-file", .ok(try Fixture.data("kiosk-day-synthetic.json")))
        _ = try await client(kiosk).fetch()
        #expect(kiosk.requests.first?.cachePolicy == .reloadIgnoringLocalCacheData)

        let pvpc = StubHTTPClient()
        pvpc.on("apidatos", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        _ = try await PVPCClient(http: pvpc, now: { madrid(2026, 10, 17, 10) }).fetch(day: madrid(2026, 10, 17))
        #expect(pvpc.requests.first?.cachePolicy == .reloadIgnoringLocalCacheData)

        let ec = StubHTTPClient()
        ec.on("energy-charts", .ok(try Fixture.data("energycharts-DE-LU-2026-10-17.json")))
        _ = try await EnergyChartsClient(region: .germany, http: ec, now: { madrid(2026, 10, 17, 10) }).fetch(day: madrid(2026, 10, 17))
        #expect(ec.requests.first?.cachePolicy == .reloadIgnoringLocalCacheData)
    }

    @Test func cancelledBecomesCancellationError() async {
        for error in [URLError(.cancelled) as Error, CancellationError()] {
            let stub = StubHTTPClient(.failure(error))
            await #expect(throws: CancellationError.self) { try await client(stub).fetch() }
            await #expect(throws: CancellationError.self) { try await PVPCClient(http: stub).fetch(day: Date()) }
            await #expect(throws: CancellationError.self) { try await EnergyChartsClient(region: .france, http: stub).fetch(day: Date()) }
            await #expect(throws: CancellationError.self) { try await EnergyChartsClient(region: .france, http: stub).fetchTodayAndTomorrow(includeTomorrow: true) }
        }
    }

    @Test func nonURLErrorsBecomeNetworkErrors() async {
        let stub = StubHTTPClient(.failure(LeakyError(text: "boom")))
        await #expect(throws: SunWattError.network("boom")) { try await client(stub).fetch() }
        await #expect(throws: SunWattError.network("boom")) { try await PVPCClient(http: stub).fetch(day: Date()) }
        await #expect(throws: SunWattError.network("boom")) { try await EnergyChartsClient(region: .austria, http: stub).fetch(day: Date()) }
    }

    // MARK: parsing

    @Test func neitherKpiNorCurveIsBadResponse() {
        let day = madrid(2026, 10, 17, 9)
        for inner in ["{}", #"{"stationOverview":{"stationName":"x"}}"#, #"{"realKpi":"nope"}"#] {
            do {
                _ = try FusionSolarKioskClient.parse(kioskBody(inner: inner), fetchedAt: day, day: day)
                Issue.record("expected throw for \(inner)")
            } catch SunWattError.badResponse {
            } catch {
                Issue.record("wrong error \(error)")
            }
        }
    }

    @Test func curveFromYesterdayNearMidnightIsDiscarded() throws {
        let fetched = madrid(2026, 10, 17, 0, 10)
        let body = kioskBody(inner: kioskCurveJSON(labels: labels288(), values: Array(repeating: "1.5", count: 288)))
        let s = try FusionSolarKioskClient.parse(body, fetchedAt: fetched, day: fetched)
        #expect(s.curve.count == 288)
        // Up to fetchedAt + 5 min (00:15) is kept; everything later has no value.
        #expect(s.curve.filter { $0.kW != nil }.map(\.time).last == madrid(2026, 10, 17, 0, 15))
        #expect(s.curve.filter { $0.time > madrid(2026, 10, 17, 0, 15) }.allSatisfy { $0.kW == nil })
        #expect(s.dataAsOf == madrid(2026, 10, 17, 0, 15))
    }

    @Test func allFutureValuesLeaveNoDataAsOf() throws {
        let fetched = madrid(2026, 10, 17, 0, 10)
        let labels = ["05:00", "05:05"]
        let body = kioskBody(inner: kioskCurveJSON(labels: labels, values: ["1", "2"]))
        let s = try FusionSolarKioskClient.parse(body, fetchedAt: fetched, day: fetched)
        #expect(s.curve.count == 2)
        #expect(s.dataAsOf == nil)
        #expect(s.latestPoint == nil)
    }

    @Test func exactPointCountsOnDSTDays() throws {
        let values = Array(repeating: "1.0", count: 288)
        let body = kioskBody(inner: kioskCurveJSON(labels: labels288(), values: values))
        let spring = madrid(2026, 3, 29, 23, 0)
        let s1 = try FusionSolarKioskClient.parse(body, fetchedAt: spring, day: spring)
        #expect(s1.curve.count == 276)
        let fall = madrid(2026, 10, 25, 23, 0)
        let s2 = try FusionSolarKioskClient.parse(body, fetchedAt: fall, day: fall)
        #expect(s2.curve.count == 288)
        let normal = madrid(2026, 10, 17, 23, 55)
        #expect(try FusionSolarKioskClient.parse(body, fetchedAt: normal, day: normal).curve.count == 288)
    }

    @Test func badLabelsAreSkipped() throws {
        let day = madrid(2026, 10, 17, 23)
        let body = kioskBody(inner: kioskCurveJSON(labels: ["10:00", "24:00", "ab", "10:5", "10:61", "", "10:10"], values: ["1", "2", "3", "4", "5", "6", "7"]))
        let s = try FusionSolarKioskClient.parse(body, fetchedAt: day, day: day)
        #expect(s.curve.map(\.kW) == [1, 4, 7])
        #expect(s.curve.map(\.time) == [madrid(2026, 10, 17, 10, 0), madrid(2026, 10, 17, 10, 5), madrid(2026, 10, 17, 10, 10)])
    }

    @Test func labelValueMismatchUsesMinimumCount() throws {
        let day = madrid(2026, 10, 17, 23)
        let moreLabels = kioskBody(inner: kioskCurveJSON(labels: ["10:00", "10:05", "10:10"], values: ["1", "2"]))
        #expect(try FusionSolarKioskClient.parse(moreLabels, fetchedAt: day, day: day).curve.map(\.kW) == [1, 2])
        let moreValues = kioskBody(inner: kioskCurveJSON(labels: ["10:00", "10:05"], values: ["1", "2", "3"]))
        #expect(try FusionSolarKioskClient.parse(moreValues, fetchedAt: day, day: day).curve.map(\.kW) == [1, 2])
    }

    // MARK: secrets

    @Test func errorsNeverContainTheToken() async {
        let urlWithToken = URL(string: "https://host.example/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=SECRETKK")!
        let failures: [Error] = [
            URLError(.timedOut, userInfo: [NSURLErrorFailingURLErrorKey: urlWithToken, NSURLErrorFailingURLStringErrorKey: urlWithToken.absoluteString]),
            URLError(.notConnectedToInternet),
            LeakyError(text: "failed \(urlWithToken.absoluteString)"),
        ]
        for failure in failures {
            let stub = StubHTTPClient(.failure(failure))
            do { _ = try await client(stub).fetch(); Issue.record("expected throw") } catch {
                #expect(!error.localizedDescription.contains("SECRETKK"))
                #expect(!String(describing: error).contains("SECRETKK"))
            }
        }
        let http = StubHTTPClient()
        http.on("station-kiosk-file", .ok(Data("x".utf8), status: 503))
        do { _ = try await client(http).fetch() } catch {
            #expect(!error.localizedDescription.contains("SECRETKK"))
            #expect(!String(describing: error).contains("SECRETKK"))
        }
        let bad = StubHTTPClient()
        bad.on("station-kiosk-file", .ok(Data("{\"success\":false,\"kk\":\"SECRETKK\"}".utf8)))
        do { _ = try await client(bad).fetch() } catch {
            #expect(!error.localizedDescription.contains("SECRETKK"))
            #expect(!String(describing: error).contains("SECRETKK"))
        }
    }
}

@Suite struct KioskEndpointHardeningTests {
    @Test func descriptionsHideToken() {
        let e = KioskEndpoint(host: "host.example", kk: "SECRETKK")
        #expect(!String(describing: e).contains("SECRETKK"))
        #expect(!String(reflecting: e).contains("SECRETKK"))
        #expect(String(describing: e).contains("kk: ***"))
        var dumped = ""
        dump(e, to: &dumped)
        #expect(!dumped.contains("SECRETKK"))
        #expect("\(e)".contains("host.example"))
    }

    @Test func hostIsLowercasedAndSchemeCaseInsensitive() throws {
        let e = try #require(KioskEndpoint(parsing: "HTTPS://EU5.FusionSolar.Example/x.html#/kiosk?kk=AbC"))
        #expect(e.host == "eu5.fusionsolar.example")
        #expect(e.kk == "AbC")
    }

    @Test func explicitPortIsRejected() {
        #expect(KioskEndpoint(parsing: "https://host.example:8443/x.html#/kiosk?kk=ABC") == nil)
        #expect(KioskEndpoint(parsing: "https://host.example:443/rest/a?kk=ABC") == nil)
    }

    @Test func userInfoIsRejected() {
        #expect(KioskEndpoint(parsing: "https://user:pw@host.example/rest/a?kk=ABC") == nil)
    }

    @Test func trailingSlashAndExtraFragmentParameters() throws {
        let slash = try #require(KioskEndpoint(parsing: "https://host.example/"+"?kk=ABC"))
        #expect(slash.kk == "ABC")
        let trailing = try #require(KioskEndpoint(parsing: "https://host.example/pvmswebsite/cloud.html/#/kiosk?kk=ABC"))
        #expect(trailing.kk == "ABC")
        let extra = try #require(KioskEndpoint(parsing: "https://host.example/x.html#/kiosk?foo=1&kk=X"))
        #expect(extra.kk == "X")
    }

    @Test func duplicateKKTakesFirst() throws {
        let q = try #require(KioskEndpoint(parsing: "https://host.example/rest/a?kk=FIRST&kk=SECOND"))
        #expect(q.kk == "FIRST")
        let f = try #require(KioskEndpoint(parsing: "https://host.example/x.html#/kiosk?kk=FIRST&kk=SECOND"))
        #expect(f.kk == "FIRST")
        let both = try #require(KioskEndpoint(parsing: "https://host.example/rest/a?kk=QUERY#/kiosk?kk=FRAGMENT"))
        #expect(both.kk == "QUERY")
    }
}
