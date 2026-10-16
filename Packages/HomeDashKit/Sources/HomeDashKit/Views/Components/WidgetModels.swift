import SwiftUI

/// Localized text from the package `Widgets` catalog. Follows `\.locale` of the environment.
func wtext(_ key: LocalizedStringKey) -> Text {
    Text(key, tableName: "Widgets", bundle: .module)
}

extension PriceLevel {
    var label: LocalizedStringKey {
        switch self {
        case .low: "Cheap"
        case .medium: "Average"
        case .high: "Expensive"
        }
    }
}

extension PriceRegion {
    /// Short source label for the price footer.
    var sourceLabel: LocalizedStringKey {
        isRetailTariff ? "PVPC" : "Day-ahead"
    }
}

/// Solar half of an entry, reduced to what the views need.
struct SolarModel {
    enum Phase: Equatable {
        case notConfigured
        case error
        case active
        case idle
    }

    var phase: Phase
    var snapshot: SolarSnapshot?
    /// Set when the data is stale; the time to show in the badge.
    var staleSince: Date?
    /// "Data as of" time shown next to the figures.
    var dataTime: Date?

    init(entry: HomeDashEntry) {
        let data = entry.data
        snapshot = data.solar
        dataTime = data.solar.map { $0.dataAsOf ?? $0.fetchedAt }
        switch data.solarStatus {
        case .notConfigured:
            phase = .notConfigured
        case .error:
            phase = .error
        case .fresh, .stale:
            if let solar = data.solar {
                phase = solar.isIdle ? .idle : .active
            } else {
                phase = .error
            }
        }
        if entry.isSolarStale, let solar = data.solar {
            staleSince = solar.dataAsOf ?? solar.fetchedAt
        }
    }

    var isAvailable: Bool { phase == .active || phase == .idle }
    var isIdle: Bool { phase == .idle }
    var powerKW: Double? { snapshot?.currentPowerKW }
    var dailyKWh: Double? { snapshot?.dailyEnergyKWh }
}

/// Price half of an entry for one price window.
struct PriceModel {
    var series: PriceSeries?
    /// Points for the chart (whole day or around now).
    var points: [PricePoint] = []
    var current: PricePoint?
    var stats: PriceStats?
    var level: PriceLevel?
    var fraction: Double?
    var staleSince: Date?
    var now: Date

    init(entry: HomeDashEntry, window: PriceWindow) {
        now = entry.date
        // An error status means unavailable, even with leftover data.
        if case .error = entry.data.priceStatus { return }
        guard let series = entry.data.prices else { return }
        self.series = series
        fill(series: series, entry: entry, window: window)
    }

    private mutating func fill(series: PriceSeries, entry: HomeDashEntry, window: PriceWindow) {
        switch window {
        case .wholeDay: points = series.hours(onDayOf: entry.date)
        case .around: points = series.hours(around: entry.date, before: 6, after: 12)
        }
        current = series.currentHour(at: entry.date)
        stats = series.stats(onDayOf: entry.date)
        if let current {
            level = series.level(of: current)
            fraction = series.fraction(of: current)
        }
        if entry.isPriceStale {
            staleSince = series.fetchedAt
        }
    }

    var isAvailable: Bool { series != nil && !points.isEmpty }
    var timeZone: TimeZone { series?.region.timeZone ?? Constants.madrid }
    var region: PriceRegion { series?.region ?? .spain }
}
