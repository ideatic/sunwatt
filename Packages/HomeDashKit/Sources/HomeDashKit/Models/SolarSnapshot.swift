import Foundation

/// One point of today's production curve. `kW == nil` means "no data" (the kiosk sends "-" / "--" / "").
public struct CurvePoint: Codable, Hashable, Sendable {
    public var time: Date
    public var kW: Double?

    public init(time: Date, kW: Double?) {
        self.time = time
        self.kW = kW
    }
}

/// Solar data parsed from a FusionSolar kiosk response.
public struct SolarSnapshot: Codable, Hashable, Sendable {
    public var stationName: String?
    /// Current power in kW (`realKpi.realTimePower`).
    public var currentPowerKW: Double?
    public var dailyEnergyKWh: Double?
    public var monthEnergyKWh: Double?
    public var yearEnergyKWh: Double?
    public var cumulativeEnergyKWh: Double?
    /// Today's curve, 5-minute points in Europe/Madrid, sorted by time.
    public var curve: [CurvePoint]
    /// When this app fetched the data (device clock).
    public var fetchedAt: Date
    /// Time of the last non-nil curve point, if any. Huawei caches up to ~30 min, so this is the "data as of" time.
    public var dataAsOf: Date?

    public init(
        stationName: String? = nil,
        currentPowerKW: Double?,
        dailyEnergyKWh: Double?,
        monthEnergyKWh: Double? = nil,
        yearEnergyKWh: Double? = nil,
        cumulativeEnergyKWh: Double? = nil,
        curve: [CurvePoint],
        fetchedAt: Date,
        dataAsOf: Date?
    ) {
        self.stationName = stationName
        self.currentPowerKW = currentPowerKW
        self.dailyEnergyKWh = dailyEnergyKWh
        self.monthEnergyKWh = monthEnergyKWh
        self.yearEnergyKWh = yearEnergyKWh
        self.cumulativeEnergyKWh = cumulativeEnergyKWh
        self.curve = curve
        self.fetchedAt = fetchedAt
        self.dataAsOf = dataAsOf
    }

    /// True when the plant is not producing (night or no data).
    public var isIdle: Bool { (currentPowerKW ?? 0) <= 0.01 }

    /// Latest point with a value.
    public var latestPoint: CurvePoint? { curve.last { $0.kW != nil } }

    /// Peak power of the curve.
    public var peakKW: Double? { curve.compactMap(\.kW).max() }
}
