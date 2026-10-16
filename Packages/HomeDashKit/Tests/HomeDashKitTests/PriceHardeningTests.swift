import Foundation
import Testing
@testable import HomeDashKit

private func iso(_ text: String) -> Date { PVPCClient.parseDate(text)! }

private func hourly(from start: Date, count: Int, price: (Int) -> Double, region: PriceRegion = .spain) -> PriceSeries {
    let points = (0..<count).map { PricePoint(start: start.addingTimeInterval(Double($0) * 3600), duration: 3600, eurPerMWh: price($0)) }
    return PriceSeries(points: points, granularity: .hourly, fetchedAt: start, region: region)
}

private func quarters(from start: Date, count: Int, price: (Int) -> Double, region: PriceRegion = .spain) -> PriceSeries {
    let points = (0..<count).map { PricePoint(start: start.addingTimeInterval(Double($0) * 900), duration: 900, eurPerMWh: price($0)) }
    return PriceSeries(points: points, granularity: .quarterHourly, fetchedAt: start, region: region)
}

@Suite struct PVPCParsingHardeningTests {
    private func body(_ values: String) -> Data {
        Data(#"{"included":[{"type":"PVPC","attributes":{"values":[\#(values)]}}]}"#.utf8)
    }

    @Test func unparseablePointsAreSkipped() throws {
        let data = body(#"""
        {"value":100,"datetime":"2026-10-17T00:00:00.000+02:00"},
        {"value":"x","datetime":"2026-10-17T01:00:00.000+02:00"},
        {"value":120,"datetime":"garbage"},
        {"value":130},
        {"value":140,"datetime":"2026-10-17T04:00:00.000+02:00"}
        """#)
        let s = try PVPCClient.parse(data, fetchedAt: Date())
        #expect(s.points.map(\.eurPerMWh) == [100, 140])
        #expect(s.region == .spain)
    }

    @Test func allPointsUnparseableThrows() {
        let data = body(#"{"value":"x","datetime":"nope"},{"value":1}"#)
        #expect(throws: HomeDashError.self) { try PVPCClient.parse(data, fetchedAt: Date()) }
    }

    @Test func dateParsingVariants() {
        let expected = Date(timeIntervalSince1970: 1_792_188_000) // 2026-10-16T22:00:00Z
        #expect(PVPCClient.parseDate("2026-10-17T00:00:00.000+02:00") == expected)
        #expect(PVPCClient.parseDate("2026-10-17T00:00:00+02:00") == expected)
        #expect(PVPCClient.parseDate("2026-10-16T22:00:00Z") == expected)
        #expect(PVPCClient.parseDate("2026-10-16T22:00:00.000Z") == expected)
        #expect(PVPCClient.parseDate("not a date") == nil)
    }

    @Test func granularityFromMinimumGap() throws {
        // Hourly points with one extra 15-min neighbour: minimum gap decides.
        let data = body(#"""
        {"value":1,"datetime":"2026-10-17T00:00:00+02:00"},
        {"value":2,"datetime":"2026-10-17T01:00:00+02:00"},
        {"value":3,"datetime":"2026-10-17T01:15:00+02:00"}
        """#)
        let s = try PVPCClient.parse(data, fetchedAt: Date())
        #expect(s.granularity == .quarterHourly)
        #expect(s.points.allSatisfy { $0.duration == 900 })
        let single = try PVPCClient.parse(body(#"{"value":1,"datetime":"2026-10-17T00:00:00+02:00"}"#), fetchedAt: Date())
        #expect(single.granularity == .hourly)
    }

    @Test func mergedSeriesIsQuarterHourlyWhenAnyPointIs() {
        let day1 = hourly(from: iso("2026-10-16T22:00:00Z"), count: 24) { _ in 100 }
        let day2 = quarters(from: iso("2026-10-17T22:00:00Z"), count: 96) { _ in 100 }
        let merged = PriceSeries.merged([day1, day2], fetchedAt: Date(timeIntervalSince1970: 5))
        #expect(merged.granularity == .quarterHourly)
        #expect(merged.points.count == 120)
        #expect(merged.fetchedAt == Date(timeIntervalSince1970: 5))
        #expect(PriceSeries.merged([day1], fetchedAt: Date()).granularity == .hourly)
    }

    @Test func fetchDayUsesMadridDayOfTheInstant() async throws {
        // 2026-10-17T22:30:00Z is already 2026-10-18 00:30 in Madrid (UTC+2).
        let now = iso("2026-10-17T22:30:00Z")
        let stub = StubHTTPClient()
        stub.on("apidatos", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        let client = PVPCClient(http: stub, now: { now })
        _ = try await client.fetch(day: now)
        let url = try #require(stub.requestedURLs.first).absoluteString
        #expect(url.contains("start_date=2026-10-18T00:00"))
        #expect(url.contains("end_date=2026-10-18T23:59"))

        let range = StubHTTPClient()
        range.on("apidatos", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        _ = try await PVPCClient(http: range, now: { now }).fetchTodayAndTomorrow(includeTomorrow: true)
        let urls = range.requestedURLs.map(\.absoluteString)
        #expect(urls.count == 2)
        #expect(urls.contains { $0.contains("start_date=2026-10-18T00:00") })
        #expect(urls.contains { $0.contains("start_date=2026-10-19T00:00") })
    }

    @Test func fetchDayAcrossFallBackBoundary() async throws {
        // 2026-10-24T22:30:00Z = 2026-10-25 00:30 Madrid (still CEST).
        let now = iso("2026-10-24T22:30:00Z")
        let stub = StubHTTPClient()
        stub.on("apidatos", .ok(try Fixture.data("pvpc-2026-10-17.json")))
        _ = try await PVPCClient(http: stub, now: { now }).fetchTodayAndTomorrow(includeTomorrow: true)
        let urls = stub.requestedURLs.map(\.absoluteString)
        #expect(urls.contains { $0.contains("start_date=2026-10-25T00:00") })
        #expect(urls.contains { $0.contains("start_date=2026-10-26T00:00") })
    }
}

@Suite struct PriceAnalysisHardeningTests {
    private func fixture(_ name: String) throws -> PriceSeries {
        try PVPCClient.parse(Fixture.data(name), fetchedAt: madrid(2026, 10, 17, 10))
    }

    @Test func emptySeriesThroughEveryFunction() {
        let s = PriceSeries(points: [], granularity: .hourly, fetchedAt: Date())
        let t = madrid(2026, 10, 17, 12)
        let p = PricePoint(start: t, duration: 3600, eurPerMWh: 100)
        #expect(s.hourly().isEmpty)
        #expect(s.currentHour(at: t) == nil)
        #expect(s.hours(onDayOf: t).isEmpty)
        #expect(s.hours(around: t).isEmpty)
        #expect(s.stats(onDayOf: t) == nil)
        #expect(s.dayContext(for: t) == nil)
        #expect(s.fraction(of: p) == 0.5)
        #expect(s.level(of: p) == .medium)
        #expect(s.cheapestUpcoming(from: t).isEmpty)
        let q = PriceSeries(points: [], granularity: .quarterHourly, fetchedAt: Date())
        #expect(q.hourly().isEmpty)
    }

    @Test func singlePointThroughEveryFunction() throws {
        let p = PricePoint(start: madrid(2026, 10, 17, 12), duration: 3600, eurPerMWh: 150)
        let s = PriceSeries(points: [p], granularity: .hourly, fetchedAt: Date())
        let t = madrid(2026, 10, 17, 12, 30)
        #expect(s.hourly() == [p])
        #expect(s.currentHour(at: t) == p)
        #expect(s.hours(onDayOf: t) == [p])
        #expect(s.hours(around: t) == [p])
        let stats = try #require(s.stats(onDayOf: t))
        #expect(stats == PriceStats(min: 0.15, max: 0.15, average: 0.15))
        #expect(s.fraction(of: p) == 0.5)
        #expect(s.level(of: p) == .medium)
        #expect(s.cheapestUpcoming(from: t, count: 3) == [p])
        #expect(s.cheapestUpcoming(from: madrid(2026, 10, 17, 13), count: 3).isEmpty)
        let ctx = try #require(s.dayContext(for: t))
        #expect(ctx.hours == [p])
        #expect(ctx.fraction(of: p) == 0.5)
    }

    @Test func allEqualPrices() throws {
        let s = hourly(from: madrid(2026, 10, 17), count: 24) { _ in 80 }
        let stats = try #require(s.stats(onDayOf: madrid(2026, 10, 17, 9)))
        #expect(stats.min == stats.max && abs(stats.max - stats.average) < 1e-12)
        for p in s.points {
            #expect(s.fraction(of: p) == 0.5)
            #expect(s.level(of: p) == .medium)
        }
        #expect(s.cheapestUpcoming(from: madrid(2026, 10, 17, 10), count: 3).map(\.start)
            == (10...12).map { madrid(2026, 10, 17, $0) })
    }

    @Test func twoMergedDaysHaveSeparateStatsAndFractions() throws {
        let day1 = hourly(from: madrid(2026, 10, 17), count: 24) { 100 + Double($0) }          // 100...123
        let day2 = hourly(from: madrid(2026, 10, 18), count: 24) { 1000 + Double($0) * 10 }    // 1000...1230
        let s = PriceSeries.merged([day1, day2], fetchedAt: Date())
        #expect(s.hours(onDayOf: madrid(2026, 10, 17, 5)).count == 24)
        #expect(s.hours(onDayOf: madrid(2026, 10, 18, 5)).count == 24)
        let a = try #require(s.stats(onDayOf: madrid(2026, 10, 17, 5)))
        let b = try #require(s.stats(onDayOf: madrid(2026, 10, 18, 5)))
        #expect(abs(a.min - 0.100) < 1e-12 && abs(a.max - 0.123) < 1e-12)
        #expect(abs(b.min - 1.0) < 1e-12 && abs(b.max - 1.23) < 1e-12)
        #expect(s.fraction(of: s.points[0]) == 0)
        #expect(s.fraction(of: s.points[23]) == 1)
        #expect(s.fraction(of: s.points[24]) == 0)
        #expect(s.fraction(of: s.points[47]) == 1)
        #expect(abs(s.fraction(of: s.points[12]) - 12.0 / 23.0) < 1e-9)
    }

    @Test func dayBoundaries() {
        let s = hourly(from: madrid(2026, 10, 17), count: 48) { Double($0) }
        let lastSecond = Constants.madridCalendar.date(from: DateComponents(year: 2026, month: 10, day: 17, hour: 23, minute: 59, second: 59))!
        #expect(s.hours(onDayOf: lastSecond).count == 24)
        #expect(s.hours(onDayOf: lastSecond).first?.start == madrid(2026, 10, 17))
        #expect(s.hours(onDayOf: madrid(2026, 10, 18, 0, 0)).count == 24)
        #expect(s.hours(onDayOf: madrid(2026, 10, 18, 0, 0)).first?.start == madrid(2026, 10, 18))
        #expect(s.hours(onDayOf: madrid(2026, 10, 17, 0, 0)).first?.start == madrid(2026, 10, 17))
    }

    @Test func dstDayStatsAndCheapestUpcomingAcrossRepeatedHour() throws {
        let s = try fixture("pvpc-2025-10-26-dst25.json")
        let noon = madrid(2025, 10, 26, 12)
        let ctx = try #require(s.dayContext(for: noon))
        #expect(ctx.hours.count == 25)
        let prices = s.points.map(\.eurPerKWh)
        #expect(ctx.stats.min == prices.min() && ctx.stats.max == prices.max())
        #expect(abs(ctx.stats.average - prices.reduce(0, +) / 25) < 1e-12)
        #expect(s.stats(onDayOf: noon) == ctx.stats)

        // From before the repeated hour, asking for everything returns both 02:00 hours in order.
        let all = s.cheapestUpcoming(from: iso("2025-10-25T22:00:00Z"), count: 25)
        #expect(all.count == 25)
        #expect(all == all.sorted { $0.start < $1.start })
        #expect(all.filter { $0.start == iso("2025-10-26T02:00:00+02:00") || $0.start == iso("2025-10-26T02:00:00+01:00") }.count == 2)
        // From the second pass, the first 02:00 hour is gone.
        let later = s.cheapestUpcoming(from: iso("2025-10-26T02:30:00+01:00"), count: 25)
        #expect(!later.contains { $0.start == iso("2025-10-26T02:00:00+02:00") })
        #expect(later.first?.start == iso("2025-10-26T02:00:00+01:00"))
        #expect(later.count == 25 - 3)
    }

    @Test func quarterHourStatsAndCurrentHour() throws {
        let s = try fixture("pvpc-quarter-hour-synthetic.json")
        let noon = madrid(2026, 10, 17, 12)
        let ctx = try #require(s.dayContext(for: noon))
        #expect(ctx.hours.count == 24)
        let hourlyPrices = s.hourly().map(\.eurPerKWh)
        #expect(ctx.stats.min == hourlyPrices.min())
        #expect(ctx.stats.max == hourlyPrices.max())
        // 05:40 sits in the 05:00 hour, whose price is the mean of its four quarters.
        let hour = try #require(s.currentHour(at: madrid(2026, 10, 17, 5, 40)))
        #expect(hour.start == madrid(2026, 10, 17, 5))
        #expect(abs(hour.eurPerMWh - 153) < 1e-9)
        #expect(hour.duration == 3600)
        #expect(s.fraction(of: hour) >= 0 && s.fraction(of: hour) <= 1)
    }

    @Test func dayContextMatchesTheStandaloneFunctions() throws {
        let s = try fixture("pvpc-2026-10-17.json")
        let ctx = try #require(s.dayContext(for: madrid(2026, 10, 17, 3)))
        #expect(ctx.hours == s.hours(onDayOf: madrid(2026, 10, 17, 3)))
        #expect(ctx.stats == s.stats(onDayOf: madrid(2026, 10, 17, 3)))
        for p in ctx.hours {
            #expect(ctx.fraction(of: p) == s.fraction(of: p))
            #expect(ctx.level(of: p) == s.level(of: p))
        }
    }

    @Test func portugalUsesLisbonDayBoundaries() {
        // Lisbon is UTC+1 on 2026-10-17: its day starts at 23:00Z the evening before.
        let start = iso("2026-10-16T23:00:00Z")
        let pt = hourly(from: start, count: 48, price: { Double($0) }, region: .portugal)
        let justBeforeNextDay = iso("2026-10-17T22:59:59Z")
        #expect(pt.hours(onDayOf: justBeforeNextDay).count == 24)
        #expect(pt.hours(onDayOf: justBeforeNextDay).first?.start == start)
        let nextDay = iso("2026-10-17T23:00:00Z")
        #expect(pt.hours(onDayOf: nextDay).count == 24)
        #expect(pt.hours(onDayOf: nextDay).first?.start == nextDay)
        #expect(pt.stats(onDayOf: justBeforeNextDay)?.max == 0.023)
        #expect(pt.fraction(of: pt.points[24]) == 0)

        // The same instants read as Madrid days (UTC+2) split at 22:00Z.
        let es = hourly(from: start, count: 48, price: { Double($0) }, region: .spain)
        #expect(es.hours(onDayOf: iso("2026-10-17T21:59:59Z")).count == 23)
        #expect(es.hours(onDayOf: iso("2026-10-17T22:00:00Z")).first?.start == iso("2026-10-17T22:00:00Z"))
    }

    @Test func regionCalendars() {
        #expect(PriceRegion.portugal.calendar.timeZone.identifier == "Europe/Lisbon")
        for r in PriceRegion.allCases where r != .portugal {
            #expect(r.calendar.timeZone.identifier == "Europe/Madrid")
        }
    }
}
