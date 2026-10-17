import Foundation

/// Sample data built in code (no bundle resources) for the widget placeholder, SwiftUI previews and snapshots.
/// The day is 2026-10-17 in Europe/Madrid; prices are the real PVPC values of that day.
public enum SampleData {
    public enum Scenario: String, CaseIterable, Sendable {
        /// 13:40, producing ~5 kW, curve up to 13:30.
        case day
        /// 22:10, no production, full-day curve ending ~19:45.
        case night
        /// Like `day`, but the solar data was fetched two hours earlier (status stale).
        case stale
        /// Both sources failed, nothing cached.
        case error
    }

    /// 2026-10-17 13:40 Europe/Madrid.
    public static let now = date(13, 40)
    public static let nightNow = date(22, 10)

    /// PVPC €/MWh for 2026-10-17, hours 00...23 (copied from the real fixture `pvpc-2026-10-17.json`).
    static let pvpcEurPerMWh: [Double] = [
        246.76, 233.00, 215.93, 191.83, 180.61, 172.48, 194.15, 224.46, 240.49, 176.68, 90.51, 19.74,
        18.15, 17.81, 18.04, 19.23, 22.76, 74.83, 155.24, 197.53, 198.48, 197.14, 186.96, 170.94,
    ]

    /// Illustrative day-ahead wholesale prices (€/MWh) for a French Saturday, with a slightly negative midday hour.
    static let dayAheadEurPerMWh: [Double] = [
        88.2, 82.5, 79.1, 76.4, 78.8, 86.3, 104.9, 128.4, 119.6, 92.7, 61.3, 38.5,
        24.2, 9.8, -3.4, 12.4, 41.9, 98.7, 143.5, 168.2, 152.9, 131.0, 112.6, 98.4,
    ]

    /// One timeline entry for `scenario`, with prices of `region`.
    public static func entry(_ scenario: Scenario, region: PriceRegion = .spain) -> HomeDashEntry {
        switch scenario {
        case .day:
            let at = now
            let solar = snapshot(until: date(13, 30), fetchedAt: at.addingTimeInterval(-120))
            return HomeDashEntry(date: at, data: HomeDashData(
                solar: solar, prices: prices(region: region, fetchedAt: at.addingTimeInterval(-180)),
                solarStatus: .fresh, priceStatus: .fresh))
        case .night:
            let at = nightNow
            let solar = snapshot(until: date(22, 5), fetchedAt: at.addingTimeInterval(-90))
            return HomeDashEntry(date: at, data: HomeDashData(
                solar: solar, prices: prices(region: region, fetchedAt: at.addingTimeInterval(-180)),
                solarStatus: .fresh, priceStatus: .fresh))
        case .stale:
            let at = now
            let fetched = at.addingTimeInterval(-2 * 3600)
            let solar = snapshot(until: date(11, 35), fetchedAt: fetched)
            return HomeDashEntry(date: at, data: HomeDashData(
                solar: solar, prices: prices(region: region, fetchedAt: at.addingTimeInterval(-180)),
                solarStatus: .stale(since: fetched), priceStatus: .fresh))
        case .error:
            let message = "Data not available"
            return HomeDashEntry(date: now, data: HomeDashData(
                solar: nil, prices: nil, solarStatus: .error(message: message), priceStatus: .error(message: message)))
        }
    }

    /// Solar not configured, prices fine.
    public static func notConfiguredEntry() -> HomeDashEntry {
        let at = now
        return HomeDashEntry(date: at, data: HomeDashData(
            solar: nil, prices: prices(region: .spain, fetchedAt: at), solarStatus: .notConfigured, priceStatus: .fresh))
    }

    /// Price series of the sample day for `region` (PVPC for Spain, illustrative day-ahead otherwise).
    public static func prices(region: PriceRegion = .spain, fetchedAt: Date = now) -> PriceSeries {
        let values = region == .spain ? pvpcEurPerMWh : dayAheadEurPerMWh
        let start = date(0, 0)
        let points = values.enumerated().map { hour, value in
            PricePoint(start: start.addingTimeInterval(Double(hour) * 3600), duration: 3600, eurPerMWh: value)
        }
        return PriceSeries(points: points, granularity: .hourly, fetchedAt: fetchedAt, region: region)
    }

    /// Production snapshot with 5-minute points from 00:00 up to `until` (inclusive); later points are absent.
    public static func snapshot(until: Date, fetchedAt: Date) -> SolarSnapshot {
        let dayStart = date(0, 0)
        var curve: [CurvePoint] = []
        var minutes = 0
        while dayStart.addingTimeInterval(Double(minutes) * 60) <= until {
            let time = dayStart.addingTimeInterval(Double(minutes) * 60)
            curve.append(CurvePoint(time: time, kW: power(atMinute: minutes)))
            minutes += 5
        }
        let energy = curve.reduce(0.0) { $0 + ($1.kW ?? 0) } * 5.0 / 60.0
        let last = curve.last
        return SolarSnapshot(
            stationName: "Home",
            currentPowerKW: last?.kW,
            dailyEnergyKWh: (energy * 10).rounded() / 10,
            monthEnergyKWh: 412.6,
            yearEnergyKWh: 5120.3,
            curve: curve,
            fetchedAt: fetchedAt,
            dataAsOf: last?.time
        )
    }

    /// Smooth bell-shaped production (peak ~5.3 kW around 13:50) with one gentle, wide cloud dip in the afternoon;
    /// zero outside 08:00-19:45. No high-frequency terms, so the curve has no teeth.
    static func power(atMinute minute: Int) -> Double {
        let sunrise = 480.0, sunset = 1185.0
        let m = Double(minute)
        guard m > sunrise, m < sunset else { return 0 }
        let x = (m - sunrise) / (sunset - sunrise)
        let bell = pow(sin(Double.pi * x), 1.35)
        let dip = 0.06 * exp(-pow((m - 950) / 45, 2))
        return ((5.3 * bell * (1 - dip)) * 100).rounded() / 100
    }

    static func date(_ hour: Int, _ minute: Int) -> Date {
        Constants.madridCalendar.date(from: DateComponents(year: 2026, month: 10, day: 17, hour: hour, minute: minute))!
    }
}
