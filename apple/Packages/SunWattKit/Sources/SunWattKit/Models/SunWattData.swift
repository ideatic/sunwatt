import Foundation

/// Freshness of one data source inside an entry.
public enum SourceStatus: Hashable, Sendable, Codable {
    /// Data fetched in this load.
    case fresh
    /// Fetch failed (or skipped); showing cached data from `since`.
    case stale(since: Date)
    /// No data at all. `message` is user-facing and already localized.
    case error(message: String)
    /// Solar only: no kiosk URL configured.
    case notConfigured
}

/// One plant's solar data in a load: what `SunWattData.solar`, `solarStatus` and `solarLinkExpired` hold for the plant
/// shown, for any plant.
public struct PlantSolarData: Hashable, Sendable, Codable {
    /// `SolarPlant.id`.
    public var id: String
    /// The name to show (`SolarPlant.displayName(stationName:)`).
    public var name: String
    public var solar: SolarSnapshot?
    public var status: SourceStatus
    public var linkExpired: Bool

    public init(id: String, name: String, solar: SolarSnapshot?, status: SourceStatus, linkExpired: Bool = false) {
        self.id = id
        self.name = name
        self.solar = solar
        self.status = status
        self.linkExpired = linkExpired
    }
}

/// Everything the UI needs at one moment: last good data for both sources plus their status.
///
/// With several plants, `solar`, `solarStatus` and `solarLinkExpired` describe the plant shown (`plantID`, the first
/// one unless `showingPlant(at:)` picked another) and `otherPlants` the rest, in settings order after it. With one
/// plant, `otherPlants` is empty and `plantName` nil: everything reads as before.
public struct SunWattData: Hashable, Sendable, Codable {
    public var solar: SolarSnapshot?
    public var prices: PriceSeries?
    public var solarStatus: SourceStatus
    public var priceStatus: SourceStatus
    /// True when Huawei has rejected the kiosk link for at least `SunWattLoader.kioskExpiredAfter`: the link most
    /// likely expired. Views then ask the user to replace it. Not stored (worked out by the loader).
    public var solarLinkExpired = false
    /// The plant in `solar`.
    public var plantID = SolarPlant.primaryID
    /// Its name, set only with several plants (views show it to tell the plants apart).
    public var plantName: String?
    /// The other plants. Empty with one plant.
    public var otherPlants: [PlantSolarData] = []

    private enum CodingKeys: String, CodingKey { case solar, prices, solarStatus, priceStatus, plantID, plantName, otherPlants }

    public init(solar: SolarSnapshot?, prices: PriceSeries?, solarStatus: SourceStatus, priceStatus: SourceStatus) {
        self.solar = solar
        self.prices = prices
        self.solarStatus = solarStatus
        self.priceStatus = priceStatus
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        solar = try c.decodeIfPresent(SolarSnapshot.self, forKey: .solar)
        prices = try c.decodeIfPresent(PriceSeries.self, forKey: .prices)
        solarStatus = try c.decode(SourceStatus.self, forKey: .solarStatus)
        priceStatus = try c.decode(SourceStatus.self, forKey: .priceStatus)
        plantID = try c.decodeIfPresent(String.self, forKey: .plantID) ?? SolarPlant.primaryID
        plantName = try c.decodeIfPresent(String.self, forKey: .plantName)
        otherPlants = try c.decodeIfPresent([PlantSolarData].self, forKey: .otherPlants) ?? []
    }

    /// Most recent successful fetch time across both sources.
    public var lastUpdated: Date? {
        [solar?.fetchedAt, prices?.fetchedAt].compactMap { $0 }.max()
    }

    /// True with more than one plant.
    public var hasSeveralPlants: Bool { !otherPlants.isEmpty }

    /// Every plant, the one shown first.
    public var allPlants: [PlantSolarData] {
        [PlantSolarData(id: plantID, name: plantName ?? "", solar: solar, status: solarStatus, linkExpired: solarLinkExpired)] + otherPlants
    }

    /// The same data with the plant at `index` of `allPlants` shown (widgets rotate, the dashboard pages). The
    /// others keep their order. Out-of-range indexes wrap around.
    public func showingPlant(at index: Int) -> SunWattData {
        let plants = allPlants
        guard plants.count > 1 else { return self }
        let position = ((index % plants.count) + plants.count) % plants.count
        return showing(plants[position], others: plants.enumerated().filter { $0.offset != position }.map(\.element))
    }

    /// The same data with the plant `id` shown; unchanged when there is no such plant.
    public func showingPlant(id: String) -> SunWattData {
        guard let index = allPlants.firstIndex(where: { $0.id == id }) else { return self }
        return showingPlant(at: index)
    }

    private func showing(_ plant: PlantSolarData, others: [PlantSolarData]) -> SunWattData {
        var data = self
        data.plantID = plant.id
        data.plantName = plant.name
        data.solar = plant.solar
        data.solarStatus = plant.status
        data.solarLinkExpired = plant.linkExpired
        data.otherPlants = others
        return data
    }

    /// Fills the solar part from one result per plant, the first shown. A plant name is kept only with several
    /// plants, so one plant reads exactly as before.
    mutating func setPlants(_ plants: [PlantSolarData]) {
        guard let first = plants.first else { return }
        plantID = first.id
        plantName = plants.count > 1 ? first.name : nil
        solar = first.solar
        solarStatus = first.status
        solarLinkExpired = first.linkExpired
        otherPlants = Array(plants.dropFirst())
    }

}

/// One widget timeline entry. Views derive the current hour from `date`.
public struct SunWattEntry: Hashable, Sendable {
    public var date: Date
    public var data: SunWattData

    public init(date: Date, data: SunWattData) {
        self.date = date
        self.data = data
    }

    /// True when the solar snapshot belongs to an earlier Europe/Madrid day than `date`: its curve and its
    /// "today" values describe yesterday. Compares calendar days, not 24-hour spans (DST days have 23/25 hours).
    public var isSolarFromEarlierDay: Bool {
        guard let solar = data.solar else { return false }
        let calendar = Constants.madridCalendar
        let reference = min(solar.fetchedAt, solar.dataAsOf ?? solar.fetchedAt)
        return calendar.startOfDay(for: date) > calendar.startOfDay(for: reference)
    }

    /// The snapshot views must show at `date`. After midnight a snapshot from the previous day loses its curve,
    /// the current power and the daily energy (they belong to yesterday), so the views show "no production" with the
    /// stale marker instead of yesterday's numbers as today's. Month, year and total energy are kept.
    public var solarForDisplay: SolarSnapshot? {
        guard var solar = data.solar, isSolarFromEarlierDay else { return data.solar }
        // Month and year totals of an earlier month / year do not belong to the current one (1 November, 1 January).
        let calendar = Constants.madridCalendar
        let reference = min(solar.fetchedAt, solar.dataAsOf ?? solar.fetchedAt)
        if !calendar.isDate(reference, equalTo: date, toGranularity: .month) { solar.monthEnergyKWh = nil }
        if !calendar.isDate(reference, equalTo: date, toGranularity: .year) { solar.yearEnergyKWh = nil }
        solar.curve = []
        solar.currentPowerKW = nil
        solar.dailyEnergyKWh = nil
        solar.dataAsOf = nil
        solar.home = nil
        return solar
    }

    /// Every plant as views show it at `date` (`solarForDisplay` of each), the one shown first.
    public var plantsForDisplay: [PlantSolarData] {
        data.allPlants.map { plant in
            var shown = plant
            shown.solar = SunWattEntry(date: date, data: data.showingPlant(id: plant.id)).solarForDisplay
            return shown
        }
    }

    /// One plant: `solarForDisplay`. Several: their figures added up (power, today's, month's and year's energy; a
    /// figure is nil when no plant has it), without a curve. `fetchedAt` is the newest fetch, `dataAsOf` the oldest
    /// reading. Nil when no plant has data.
    public var combinedSolarForDisplay: SolarSnapshot? {
        guard data.hasSeveralPlants else { return solarForDisplay }
        let shown = plantsForDisplay.compactMap(\.solar)
        guard let newest = shown.map(\.fetchedAt).max() else { return nil }
        func sum(_ values: [Double?]) -> Double? {
            let present = values.compactMap { $0 }
            return present.isEmpty ? nil : present.reduce(0, +)
        }
        return SolarSnapshot(
            currentPowerKW: sum(shown.map(\.currentPowerKW)), dailyEnergyKWh: sum(shown.map(\.dailyEnergyKWh)),
            monthEnergyKWh: sum(shown.map(\.monthEnergyKWh)), yearEnergyKWh: sum(shown.map(\.yearEnergyKWh)),
            curve: [], fetchedAt: newest, dataAsOf: shown.compactMap(\.dataAsOf).min())
    }

    /// The entry with every plant added up as one plant (complications, which have no room for names). Stale when any
    /// plant is; not set up only when no plant is. One plant: unchanged.
    public var combinedPlants: SunWattEntry {
        guard data.hasSeveralPlants else { return self }
        let plants = plantsForDisplay
        var combined = data
        combined.plantID = SolarPlant.primaryID
        combined.plantName = nil
        combined.otherPlants = []
        combined.solarLinkExpired = false
        combined.solar = combinedSolarForDisplay
        let staleSince = plants.compactMap { plant -> Date? in
            if case .stale(let since) = plant.status { return since }
            return nil
        }.min()
        if combined.solar == nil {
            combined.solarStatus = plants.allSatisfy { $0.status == .notConfigured }
                ? .notConfigured
                : .error(message: SunWattError.unavailable.localizedDescription)
        } else if let staleSince {
            combined.solarStatus = .stale(since: staleSince)
        } else {
            combined.solarStatus = .fresh
        }
        return SunWattEntry(date: date, data: combined)
    }

    /// Solar data is shown as stale when older than `Constants.staleAfter` at `date`, when its status is stale,
    /// or when it was fetched on an earlier Madrid day.
    public var isSolarStale: Bool {
        if case .stale = data.solarStatus { return true }
        if let solar = data.solar, date.timeIntervalSince(solar.fetchedAt) > Constants.staleAfter { return true }
        return isSolarFromEarlierDay
    }

    /// True when the price status is stale or the series has no point covering `date`.
    /// False when there are no prices at all: the view shows the status (error / empty) instead of a stale marker.
    public var isPriceStale: Bool {
        if case .stale = data.priceStatus { return true }
        if let prices = data.prices, !prices.points.contains(where: { $0.contains(date) }) { return true }
        return false
    }
}
