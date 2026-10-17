import Testing
import Foundation
import WidgetKit
@testable import SunWattKit

@Test func familyMappingFromWidgetKit() {
    #expect(SunWattFamily(.systemSmall) == .small)
    #expect(SunWattFamily(.systemMedium) == .medium)
    #expect(SunWattFamily(.systemLarge) == .large)
    #expect(SunWattFamily(.systemExtraLarge) == nil)
    #if os(iOS)
    #expect(SunWattFamily(.accessoryRectangular) == .accessoryRectangular)
    #expect(SunWattFamily(.accessoryCircular) == .accessoryCircular)
    #endif
}

@Test func familySizesAndAccessoryFlag() {
    #expect(SunWattFamily.small.size == CGSize(width: 170, height: 170))
    #expect(SunWattFamily.medium.size == CGSize(width: 364, height: 170))
    #expect(SunWattFamily.large.size == CGSize(width: 364, height: 382))
    #expect(SunWattFamily.accessoryRectangular.size == CGSize(width: 172, height: 76))
    #expect(SunWattFamily.accessoryCircular.size == CGSize(width: 76, height: 76))
    #expect(SunWattFamily.allCases.filter(\.isAccessory).count == 2)
}

@Test func solarDomainDefaultsToDaylightSpan() {
    let now = madrid(2026, 10, 17, 13, 40)
    let curve = (0..<12).map { CurvePoint(time: madrid(2026, 10, 17, 9, $0 * 5), kW: 2) }
    let domain = ChartLayout.solarDomain(curve: curve, now: now)
    #expect(domain.lowerBound == madrid(2026, 10, 17, 6))
    #expect(domain.upperBound == madrid(2026, 10, 17, 21))
}

@Test func solarDomainWidensForEarlyAndLateProduction() {
    let now = madrid(2026, 6, 20, 14)
    let curve = [
        CurvePoint(time: madrid(2026, 6, 20, 5, 10), kW: 0.4),
        CurvePoint(time: madrid(2026, 6, 20, 12), kW: 5),
        CurvePoint(time: madrid(2026, 6, 20, 21, 20), kW: 0.3),
        CurvePoint(time: madrid(2026, 6, 20, 22, 0), kW: 0),
    ]
    let domain = ChartLayout.solarDomain(curve: curve, now: now)
    #expect(domain.lowerBound == madrid(2026, 6, 20, 5))
    #expect(domain.upperBound == madrid(2026, 6, 20, 22))
}

@Test func solarPointsSkipMissingAndClampNegatives() {
    let a = madrid(2026, 10, 17, 10)
    let curve = [
        CurvePoint(time: a, kW: -0.2),
        CurvePoint(time: a.addingTimeInterval(300), kW: nil),
        CurvePoint(time: a.addingTimeInterval(600), kW: 1.5),
        CurvePoint(time: madrid(2026, 10, 17, 23), kW: 1),
    ]
    let domain = madrid(2026, 10, 17, 6)...madrid(2026, 10, 17, 21)
    let points = ChartLayout.solarPoints(curve: curve, domain: domain)
    #expect(points.map(\.kW) == [0, 1.5])
}

@Test func barStatePastCurrentFuture() {
    let start = madrid(2026, 10, 17, 13)
    let point = PricePoint(start: start, duration: 3600, eurPerMWh: 100)
    #expect(PriceBarState.state(of: point, now: start.addingTimeInterval(-1)) == .future)
    #expect(PriceBarState.state(of: point, now: start) == .current)
    #expect(PriceBarState.state(of: point, now: start.addingTimeInterval(3599)) == .current)
    #expect(PriceBarState.state(of: point, now: start.addingTimeInterval(3600)) == .past)
    #expect(PriceBarState.past.opacity == Theme.Price.pastOpacity)
    #expect(PriceBarState.current.opacity == 1)
    #expect(PriceBarState.future.opacity == Theme.Price.futureOpacity)
}

@Test func priceYDomainIncludesZeroAndNegatives() {
    let start = madrid(2026, 10, 17, 0)
    func point(_ hour: Int, _ eurPerMWh: Double) -> PricePoint {
        PricePoint(start: start.addingTimeInterval(Double(hour) * 3600), duration: 3600, eurPerMWh: eurPerMWh)
    }
    let positive = ChartLayout.priceYDomain(points: [point(0, 100), point(1, 250)])
    #expect(positive.lowerBound == 0)
    #expect(positive.upperBound > 0.25)
    let negative = ChartLayout.priceYDomain(points: [point(0, -20), point(1, 80)])
    #expect(negative.lowerBound == -0.02)
}

@Test func priceAxisHoursFollowLocalClock() {
    let series = SampleData.prices()
    let hours = ChartLayout.axisHours(points: series.points, step: 6, calendar: series.region.calendar)
    #expect(hours.count == 4)
    #expect(hours.first == madrid(2026, 10, 17, 0))
}

@Test func priceModelHonorsWindow() {
    let entry = SampleData.entry(.day)
    let whole = PriceModel(entry: entry, window: .wholeDay)
    let around = PriceModel(entry: entry, window: .around)
    #expect(whole.points.count == 24)
    #expect(around.points.count == 17) // 07:00 ... 23:00: a one-day series has nothing after 23:00
    #expect(whole.current?.start == madrid(2026, 10, 17, 13))
    #expect(whole.level == .low)
    #expect(whole.staleSince == nil)
}

@Test func solarModelPhases() {
    #expect(SolarModel(entry: SampleData.entry(.day)).phase == .active)
    #expect(SolarModel(entry: SampleData.entry(.night)).phase == .idle)
    #expect(SolarModel(entry: SampleData.entry(.error)).phase == .error)
    #expect(SolarModel(entry: SampleData.notConfiguredEntry()).phase == .notConfigured)
    #expect(SolarModel(entry: SampleData.entry(.stale)).staleSince != nil)
    #expect(SolarModel(entry: SampleData.entry(.day)).staleSince == nil)
}

@Test func priceModelMissingCurrentHourIsStale() {
    var entry = SampleData.entry(.day)
    entry.date = madrid(2026, 10, 18, 3) // series covers only 2026-10-17
    let model = PriceModel(entry: entry, window: .wholeDay)
    #expect(model.current == nil)
    #expect(model.staleSince != nil)
}

@Test func formatAvoidsNegativeZero() {
    Format.locale = Locale(identifier: "es_ES")
    #expect(Format.eurPerKWh(-0.0000004) == "0,000")
    #expect(Format.eurPerKWh(-0.003) == "-0,003")
}

@Test func formatLargeEnergyInMegawattHours() {
    Format.locale = Locale(identifier: "es_ES")
    let month = Format.energy(733.2), year = Format.energy(17_092)
    #expect(month.value == "733,2" && month.unit == "kWh")
    #expect(Format.energy(9_999).unit == "kWh")
    #expect(year.value == "17,1" && year.unit == "MWh")
    #expect(Format.energy(nil).value == "–")
}

@Test func formatTimeUsesGivenTimeZone() {
    Format.locale = Locale(identifier: "es_ES")
    let date = madrid(2026, 10, 17, 14, 32)
    #expect(Format.time(date) == "14:32")
    #expect(Format.time(date, timeZone: TimeZone(identifier: "Europe/Lisbon")!) == "13:32")
    #expect(Format.hour(date, timeZone: TimeZone(identifier: "Europe/Lisbon")!) == "13 h")
}
