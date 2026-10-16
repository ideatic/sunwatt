import Foundation
import Testing
@testable import HomeDashKit

struct PVPCClientTests {
    private func parse(_ name: String) throws -> PriceSeries {
        try PVPCClient.parse(Fixture.data(name), fetchedAt: madrid(2026, 10, 17, 10))
    }

    @Test func parsesRealFixtures() throws {
        let day = try parse("pvpc-2026-10-17.json")
        #expect(day.points.count == 24)
        #expect(day.points[0].eurPerMWh == 246.76)
        #expect(abs(day.points[0].eurPerKWh - 0.24676) < 1e-12)
        #expect(day.granularity == .hourly)
        #expect(day.points[0].duration == 3600)
        #expect(day.points[0].start == madrid(2026, 10, 17, 0, 0))
        #expect(day.sourceUpdatedAt == madrid(2026, 10, 16, 20, 46).addingTimeInterval(35))
        #expect(day.fetchedAt == madrid(2026, 10, 17, 10))

        #expect(try parse("pvpc-2026-03-29-dst23.json").points.count == 23)
        #expect(try parse("pvpc-2025-10-26-dst25.json").points.count == 25)
    }

    @Test func errorBodyIsUnavailable() throws {
        #expect(throws: HomeDashError.unavailable) { try parse("pvpc-error-unavailable.json") }
    }

    @Test func malformedJSONIsBadResponse() {
        #expect(throws: HomeDashError.self) { try PVPCClient.parse(Data("not json".utf8), fetchedAt: Date()) }
        do {
            _ = try PVPCClient.parse(Data("not json".utf8), fetchedAt: Date())
        } catch let error as HomeDashError {
            guard case .badResponse = error else { Issue.record("expected badResponse, got \(error)"); return }
        } catch {
            Issue.record("unexpected \(error)")
        }
    }

    @Test func missingPVPCEntryIsUnavailable() {
        let body = #"{"included":[{"type":"Precio mercado spot","attributes":{"values":[]}}]}"#
        #expect(throws: HomeDashError.unavailable) { try PVPCClient.parse(Data(body.utf8), fetchedAt: Date()) }
        let empty = #"{"included":[{"type":"PVPC","attributes":{"values":[]}}]}"#
        #expect(throws: HomeDashError.unavailable) { try PVPCClient.parse(Data(empty.utf8), fetchedAt: Date()) }
    }

    @Test func parsesDatesWithoutFractionalSeconds() throws {
        let body = #"{"included":[{"type":"PVPC","attributes":{"last-update":"2026-10-16T20:46:35+02:00","values":[{"value":100,"datetime":"2026-10-17T00:00:00+02:00"}]}}]}"#
        let s = try PVPCClient.parse(Data(body.utf8), fetchedAt: Date())
        #expect(s.points.count == 1)
        #expect(s.granularity == .hourly)
        #expect(s.points[0].start == madrid(2026, 10, 17))
    }

    @Test func quarterHourGranularity() throws {
        let s = try parse("pvpc-quarter-hour-synthetic.json")
        #expect(s.points.count == 96)
        #expect(s.granularity == .quarterHourly)
        #expect(s.points[0].duration == 900)
        let hourly = s.hourly()
        #expect(hourly.count == 24)
        #expect(hourly.allSatisfy { $0.duration == 3600 })
        // Hour 5: 100 + 50 + {0, 2, 4, 6} -> average 153.
        #expect(abs(hourly[5].eurPerMWh - 153) < 1e-9)
        #expect(hourly[5].start == madrid(2026, 10, 17, 5))
    }

    // MARK: - fetch

    @Test func http500IsHTTPError() async {
        let stub = StubHTTPClient()
        stub.on("apidatos", .ok(Data("oops".utf8), status: 500))
        let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
        await #expect(throws: HomeDashError.http(status: 500)) { try await client.fetch(day: madrid(2026, 10, 17)) }
    }

    @Test func errorBodyWithAnyStatusIsUnavailable() async throws {
        let body = try Fixture.data("pvpc-error-unavailable.json")
        for status in [200, 502] {
            let stub = StubHTTPClient()
            stub.on("apidatos", .ok(body, status: status))
            let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
            await #expect(throws: HomeDashError.unavailable) { try await client.fetch(day: madrid(2026, 10, 17)) }
        }
    }

    @Test func networkFailureIsNetworkError() async {
        let stub = StubHTTPClient(.failure(URLError(.notConnectedToInternet)))
        let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
        do {
            _ = try await client.fetch(day: madrid(2026, 10, 17))
            Issue.record("expected throw")
        } catch let error as HomeDashError {
            guard case .network = error else { Issue.record("got \(error)"); return }
        } catch {
            Issue.record("unexpected \(error)")
        }
    }

    @Test func fetchBuildsMadridDayURL() async throws {
        let stub = StubHTTPClient()
        stub.on("apidatos", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
        let series = try await client.fetch(day: madrid(2026, 10, 17, 18, 30))
        #expect(series.points.count == 24)
        let url = try #require(stub.requestedURLs.first).absoluteString
        #expect(url.hasPrefix("https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real?"))
        #expect(url.contains("start_date=2026-10-17T00:00"))
        #expect(url.contains("end_date=2026-10-17T23:59"))
        #expect(url.contains("time_trunc=hour"))
    }

    private func tomorrowData() throws -> Data {
        let text = String(decoding: try Fixture.data("pvpc-2026-10-17.json"), as: UTF8.self)
            .replacingOccurrences(of: "2026-10-17", with: "2026-10-18")
            .replacingOccurrences(of: "2026-10-16T20:46:35", with: "2026-10-17T20:46:35")
        return Data(text.utf8)
    }

    @Test func todayAndTomorrowMerged() async throws {
        let stub = StubHTTPClient()
        stub.on("start_date=2026-10-18", .ok(try tomorrowData()))
        stub.on("start_date=2026-10-17", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        let now = madrid(2026, 10, 17, 10)
        let client = PVPCClient(http: stub, now: { now })
        let series = try await client.fetchTodayAndTomorrow(includeTomorrow: true)
        #expect(series.points.count == 48)
        #expect(series.points == series.points.sorted { $0.start < $1.start })
        #expect(series.fetchedAt == now)
        #expect(series.granularity == .hourly)
        #expect(series.sourceUpdatedAt == madrid(2026, 10, 17, 20, 46).addingTimeInterval(35))
        let urls = stub.requestedURLs.map(\.absoluteString)
        #expect(urls.count == 2)
        #expect(urls.contains { $0.contains("start_date=2026-10-17T00:00") && $0.contains("end_date=2026-10-17T23:59") })
        #expect(urls.contains { $0.contains("start_date=2026-10-18T00:00") && $0.contains("end_date=2026-10-18T23:59") })
    }

    @Test func tomorrowFailureIsSwallowed() async throws {
        let stub = StubHTTPClient()
        stub.on("start_date=2026-10-18", .ok(try Fixture.data("pvpc-error-unavailable.json"), status: 502))
        stub.on("start_date=2026-10-17", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
        let series = try await client.fetchTodayAndTomorrow(includeTomorrow: true)
        #expect(series.points.count == 24)
        #expect(stub.requestedURLs.count == 2)
    }

    @Test func excludingTomorrowMakesOneRequest() async throws {
        let stub = StubHTTPClient()
        stub.on("start_date=2026-10-17", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
        let series = try await client.fetchTodayAndTomorrow(includeTomorrow: false)
        #expect(series.points.count == 24)
        #expect(stub.requestedURLs.count == 1)
    }

    @Test func todayFailureThrows() async throws {
        let stub = StubHTTPClient()
        stub.on("start_date=2026-10-18", .ok(try tomorrowData()))
        stub.on("start_date=2026-10-17", .ok(try Fixture.data("pvpc-error-unavailable.json"), status: 502))
        let client = PVPCClient(http: stub, now: { madrid(2026, 10, 17, 10) })
        await #expect(throws: HomeDashError.unavailable) { try await client.fetchTodayAndTomorrow(includeTomorrow: true) }
    }
}
