import Foundation
import Testing
@testable import HomeDashKit

private func timelineData(solar: SolarSnapshot? = nil, prices: PriceSeries? = nil,
                          solarStatus: SourceStatus = .fresh, priceStatus: SourceStatus = .fresh) -> HomeDashData {
    HomeDashData(solar: solar, prices: prices, solarStatus: solarStatus, priceStatus: priceStatus)
}

private func hour(_ d: Date) -> Int { Constants.madridCalendar.component(.hour, from: d) }

@Suite struct TimelineTests {
    @Test func normalDay() {
        let now = madrid(2026, 6, 1, 14, 37)
        let entries = TimelineBuilder.entries(for: timelineData(), now: now, hours: 24)
        #expect(entries.count == 25)
        #expect(entries[0].date == now)
        #expect(entries[1].date == madrid(2026, 6, 1, 15, 0))
        #expect(entries[2].date == madrid(2026, 6, 1, 16, 0))
        #expect(entries[24].date == madrid(2026, 6, 2, 14, 0))
        #expect(entries.allSatisfy { $0.data == entries[0].data })
    }

    @Test func exactlyOnTheHour() {
        let now = madrid(2026, 6, 1, 15, 0)
        let entries = TimelineBuilder.entries(for: timelineData(), now: now, hours: 2)
        #expect(entries.map(\.date) == [now, now.addingTimeInterval(3600), now.addingTimeInterval(7200)])
    }

    @Test func zeroHours() {
        #expect(TimelineBuilder.entries(for: timelineData(), now: madrid(2026, 6, 1, 10, 5), hours: 0).count == 1)
    }

    @Test func springForward() {
        let now = madrid(2026, 3, 29, 0, 30)
        let entries = TimelineBuilder.entries(for: timelineData(), now: now, hours: 4)
        #expect(entries.count == 5)
        #expect(entries.map { hour($0.date) } == [0, 1, 3, 4, 5])
        #expect(entries[2].date.timeIntervalSince(entries[1].date) == 3600)
        #expect(!entries.contains { hour($0.date) == 2 })
    }

    @Test func fallBack() {
        let now = madrid(2026, 10, 25, 1, 30)
        let entries = TimelineBuilder.entries(for: timelineData(), now: now, hours: 4)
        #expect(entries.count == 5)
        #expect(entries.map { hour($0.date) } == [1, 2, 2, 3, 4])
        for i in 1..<entries.count {
            #expect(entries[i].date.timeIntervalSince(entries[i - 1].date) == (i == 1 ? 1800 : 3600))
        }
    }

    @Test func nextReload() {
        let now = madrid(2026, 6, 1, 10, 0)
        #expect(TimelineBuilder.nextReload(after: now) == now.addingTimeInterval(15 * 60))
    }

    @Test func solarStaleFlags() {
        let fetched = madrid(2026, 6, 1, 12, 0)
        let solar = storeSampleSolar(fetchedAt: fetched)
        let d = timelineData(solar: solar)
        #expect(HomeDashEntry(date: fetched.addingTimeInterval(44 * 60), data: d).isSolarStale == false)
        #expect(HomeDashEntry(date: fetched.addingTimeInterval(50 * 60), data: d).isSolarStale == true)
        let st = timelineData(solar: solar, solarStatus: .stale(since: fetched))
        #expect(HomeDashEntry(date: fetched, data: st).isSolarStale == true)
        let nc = timelineData(solarStatus: .notConfigured)
        #expect(HomeDashEntry(date: fetched, data: nc).isSolarStale == false)
        let er = timelineData(solarStatus: .error(message: "x"))
        #expect(HomeDashEntry(date: fetched, data: er).isSolarStale == false)
    }

    @Test func priceStaleFlags() {
        let prices = storeSamplePrices(day: madrid(2026, 6, 1, 0, 0))
        let d = timelineData(prices: prices)
        #expect(HomeDashEntry(date: madrid(2026, 6, 1, 23, 30), data: d).isPriceStale == false)
        #expect(HomeDashEntry(date: madrid(2026, 6, 2, 0, 0), data: d).isPriceStale == true)
        let st = timelineData(prices: prices, priceStatus: .stale(since: madrid(2026, 6, 1, 8, 0)))
        #expect(HomeDashEntry(date: madrid(2026, 6, 1, 9, 0), data: st).isPriceStale == true)
    }
}
