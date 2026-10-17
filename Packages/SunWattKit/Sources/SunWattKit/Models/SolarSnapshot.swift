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

/// What the house uses and trades with the grid, from a meter at the connection point. Only plants with such a
/// meter report it (SolarEdge with a meter, PVOutput when the uploader sends consumption). Export-only plants and the
/// FusionSolar kiosk have none: `SolarSnapshot.home` is nil and the views show nothing about it.
public struct HomeEnergy: Codable, Hashable, Sendable {
    /// What the house uses now (kW).
    public var consumptionKW: Double?
    /// Power from the grid now (kW): positive when buying, negative when selling.
    public var gridKW: Double?
    /// Energy the house used today (kWh).
    public var dailyConsumptionKWh: Double?
    /// Energy bought from the grid today (kWh).
    public var dailyImportKWh: Double?
    /// Energy sold to the grid today (kWh).
    public var dailyExportKWh: Double?
    /// Today's consumption curve, same time base as the production curve.
    public var curve: [CurvePoint]

    public init(
        consumptionKW: Double?,
        gridKW: Double?,
        dailyConsumptionKWh: Double?,
        dailyImportKWh: Double? = nil,
        dailyExportKWh: Double? = nil,
        curve: [CurvePoint] = []
    ) {
        self.consumptionKW = consumptionKW
        self.gridKW = gridKW
        self.dailyConsumptionKWh = dailyConsumptionKWh
        self.dailyImportKWh = dailyImportKWh
        self.dailyExportKWh = dailyExportKWh
        self.curve = curve
    }

    /// Share of today's consumption covered by the panels (0...1): 1 − import / consumption. Nil when unknown.
    public var selfSufficiency: Double? {
        guard let used = dailyConsumptionKWh, used > 0.05, let bought = dailyImportKWh else { return nil }
        return min(max(1 - bought / used, 0), 1)
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
    /// One-way identifier of the kiosk link (host + token) this snapshot came from, set by `SunWattLoader`
    /// (see `KioskEndpoint.cacheID`). Nil in caches written before this field existed: those never match a link.
    public var endpointID: String?
    /// Home consumption and grid exchange. Nil when the plant has no meter (export only, or the source has no such
    /// data): then nothing about consumption is shown.
    public var home: HomeEnergy?

    public init(
        stationName: String? = nil,
        currentPowerKW: Double?,
        dailyEnergyKWh: Double?,
        monthEnergyKWh: Double? = nil,
        yearEnergyKWh: Double? = nil,
        cumulativeEnergyKWh: Double? = nil,
        curve: [CurvePoint],
        fetchedAt: Date,
        dataAsOf: Date?,
        endpointID: String? = nil,
        home: HomeEnergy? = nil
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
        self.endpointID = endpointID
        self.home = home
    }

    /// True when the plant is not producing (night or no data).
    public var isIdle: Bool { (currentPowerKW ?? 0) <= 0.01 }

    /// Latest point with a value.
    public var latestPoint: CurvePoint? { curve.last { $0.kW != nil } }

    /// Peak power of the curve.
    public var peakKW: Double? { curve.compactMap(\.kW).max() }
}
