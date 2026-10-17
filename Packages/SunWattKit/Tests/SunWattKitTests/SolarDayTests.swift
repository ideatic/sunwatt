import Foundation
import Testing
@testable import SunWattKit

private func solarData(_ solar: SolarSnapshot?, status: SourceStatus = .fresh) -> SunWattData {
    SunWattData(solar: solar, prices: nil, solarStatus: status, priceStatus: .error(message: "x"))
}

private func snapshot(fetchedAt: Date, dataAsOf: Date? = nil) -> SolarSnapshot {
    SolarSnapshot(
        stationName: "Casa", currentPowerKW: 3.2, dailyEnergyKWh: 18.4, monthEnergyKWh: 410, yearEnergyKWh: 2900, cumulativeEnergyKWh: 9000,
        curve: [CurvePoint(time: fetchedAt.addingTimeInterval(-300), kW: 3.1), CurvePoint(time: fetchedAt, kW: 3.2)],
        fetchedAt: fetchedAt, dataAsOf: dataAsOf ?? fetchedAt, endpointID: "id")
}

@Suite struct SolarDayTests {
    @Test func sameDayShowsTheSnapshotUntouched() {
        let solar = snapshot(fetchedAt: madrid(2026, 6, 1, 12, 0))
        let entry = SunWattEntry(date: madrid(2026, 6, 1, 23, 59), data: solarData(solar))
        #expect(!entry.isSolarFromEarlierDay)
        #expect(entry.solarForDisplay == solar)
    }

    @Test func afterMidnightYesterdaysValuesAreHidden() throws {
        let solar = snapshot(fetchedAt: madrid(2026, 6, 1, 23, 50))
        // 20 minutes later: well inside the 45-minute stale limit, but already another day.
        let entry = SunWattEntry(date: madrid(2026, 6, 2, 0, 10), data: solarData(solar))
        #expect(entry.isSolarFromEarlierDay)
        #expect(entry.isSolarStale)
        let shown = try #require(entry.solarForDisplay)
        #expect(shown.curve.isEmpty)
        #expect(shown.currentPowerKW == nil)
        #expect(shown.dailyEnergyKWh == nil)
        #expect(shown.dataAsOf == nil)
        #expect(shown.isIdle)
        // Not daily values: kept.
        #expect(shown.stationName == "Casa")
        #expect(shown.monthEnergyKWh == 410 && shown.yearEnergyKWh == 2900 && shown.cumulativeEnergyKWh == 9000)
        #expect(shown.fetchedAt == solar.fetchedAt)
    }

    @Test func aReadingFromAnEarlierDayThanTheFetchCounts() {
        // `dataAsOf` on June 1, fetched on June 2: the curve is still yesterday's.
        let solar = snapshot(fetchedAt: madrid(2026, 6, 2, 0, 5), dataAsOf: madrid(2026, 6, 1, 23, 55))
        let entry = SunWattEntry(date: madrid(2026, 6, 2, 0, 10), data: solarData(solar))
        #expect(entry.isSolarFromEarlierDay)
        #expect(entry.solarForDisplay?.curve.isEmpty == true)
    }

    @Test func noSolarMeansNothingToHide() {
        let entry = SunWattEntry(date: madrid(2026, 6, 2, 0, 10), data: solarData(nil, status: .notConfigured))
        #expect(!entry.isSolarFromEarlierDay)
        #expect(entry.solarForDisplay == nil)
    }

    @Test func timelineEntriesPastMidnightHideYesterday() {
        let fetched = madrid(2026, 6, 1, 22, 30)
        let data = solarData(snapshot(fetchedAt: fetched))
        let entries = TimelineBuilder.entries(for: data, now: fetched, hours: 4)
        let flags = entries.map(\.isSolarFromEarlierDay)
        // 22:30, 23:00, 00:00, 01:00, 02:00
        #expect(flags == [false, false, true, true, true])
        #expect(entries[1].solarForDisplay?.curve.isEmpty == false)
        #expect(entries[2].solarForDisplay?.curve.isEmpty == true)
    }

    // MARK: DST (Europe/Madrid)

    @Test func fallBackDayIs25HoursNot24() {
        // 2026-10-25 has 25 hours. Fetched at its first minute, 24 h 15 min later it is still the same day.
        let fetched = madrid(2026, 10, 25, 0, 5)
        let data = solarData(snapshot(fetchedAt: fetched))
        let late = SunWattEntry(date: fetched.addingTimeInterval(24 * 3600 + 15 * 60), data: data)
        #expect(Constants.madridCalendar.isDate(late.date, inSameDayAs: fetched))
        #expect(!late.isSolarFromEarlierDay)
        #expect(late.solarForDisplay?.dailyEnergyKWh == 18.4)
        // 25 hours later it is the 26th.
        let next = SunWattEntry(date: fetched.addingTimeInterval(25 * 3600), data: data)
        #expect(next.isSolarFromEarlierDay)
    }

    @Test func midnightsAroundTheFallBackDay() {
        let before = solarData(snapshot(fetchedAt: madrid(2026, 10, 24, 23, 50)))
        #expect(SunWattEntry(date: madrid(2026, 10, 25, 0, 10), data: before).isSolarFromEarlierDay)
        let during = solarData(snapshot(fetchedAt: madrid(2026, 10, 25, 23, 50)))
        #expect(SunWattEntry(date: madrid(2026, 10, 26, 0, 10), data: during).isSolarFromEarlierDay)
        #expect(!SunWattEntry(date: madrid(2026, 10, 25, 23, 55), data: during).isSolarFromEarlierDay)
    }

    @Test func repeatedHourOnFallBackDayStaysSameDay() {
        // 02:30 happens twice on 2026-10-25 (CEST, then CET).
        let firstTwoThirty = madrid(2026, 10, 25, 0, 30).addingTimeInterval(2 * 3600)
        let secondTwoThirty = firstTwoThirty.addingTimeInterval(3600)
        let data = solarData(snapshot(fetchedAt: firstTwoThirty))
        #expect(!SunWattEntry(date: secondTwoThirty, data: data).isSolarFromEarlierDay)
    }

    @Test func springForwardDayIs23Hours() {
        let fetched = madrid(2026, 3, 29, 0, 5)
        let data = solarData(snapshot(fetchedAt: fetched))
        #expect(!SunWattEntry(date: fetched.addingTimeInterval(22 * 3600 + 30 * 60), data: data).isSolarFromEarlierDay)
        #expect(SunWattEntry(date: fetched.addingTimeInterval(23 * 3600), data: data).isSolarFromEarlierDay)
    }

    // MARK: Views and answers use the check

    @Test func widgetModelShowsNoProductionAndStaleAfterMidnight() {
        let solar = snapshot(fetchedAt: madrid(2026, 6, 1, 23, 50))
        let day = SolarModel(entry: SunWattEntry(date: madrid(2026, 6, 1, 23, 55), data: solarData(solar)))
        #expect(day.phase == .active && day.powerKW == 3.2 && day.dailyKWh == 18.4)
        #expect(day.staleSince == nil)
        let night = SolarModel(entry: SunWattEntry(date: madrid(2026, 6, 2, 0, 10), data: solarData(solar)))
        #expect(night.phase == .idle)
        #expect(night.powerKW == nil && night.dailyKWh == nil)
        #expect(night.snapshot?.curve.isEmpty == true)
        #expect(night.staleSince != nil)
    }

    @Test func intentAnswersDoNotReportYesterdayAsNow() {
        let solar = snapshot(fetchedAt: madrid(2026, 6, 1, 23, 50))
        let data = solarData(solar)
        let sameDay = SolarAnswer.make(from: data, now: madrid(2026, 6, 1, 23, 55))
        #expect(sameDay.outcome == .producing && sameDay.todayKWh == 18.4)
        let after = SolarAnswer.make(from: data, now: madrid(2026, 6, 2, 0, 10))
        #expect(after.outcome == .unavailable)
        #expect(after.currentKW == nil && after.todayKWh == nil)
        let control = ControlContent.solar(from: data, now: madrid(2026, 6, 2, 0, 10))
        #expect(control == ControlContent(title: IntentStrings.current.text("No data"), subtitle: IntentStrings.current.text("Tap to refresh")))
    }

    @Test func loaderKeepsStaleStatusForYesterdaysCacheAndTheEntryAddsTheDayCheck() async {
        let (store, _) = storeTempStore()
        configured(store)
        let old = storeSampleSolar(fetchedAt: madrid(2026, 5, 31, 20, 0))
        store.saveSolar(old)
        let data = await makeLoader(store: store, solar: FakeSolar(result: .failure(.unavailable)), prices: FakePlain(result: .success(storeSamplePrices()))).load()
        #expect(data.solarStatus == .stale(since: old.fetchedAt))
        let entry = SunWattEntry(date: loaderNow, data: data)
        #expect(entry.isSolarFromEarlierDay)
        #expect(entry.solarForDisplay?.curve.isEmpty == true)
        #expect(entry.solarForDisplay?.dailyEnergyKWh == nil)
    }
}
