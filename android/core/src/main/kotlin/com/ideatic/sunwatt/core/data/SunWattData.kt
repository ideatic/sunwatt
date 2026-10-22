package com.ideatic.sunwatt.core.data

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.InstantIso8601Serializer
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.Year
import java.time.YearMonth
import kotlin.time.toJavaDuration

/** Freshness of one data source inside an entry. */
@Serializable
sealed interface SourceStatus {
    /** Data fetched in this load. */
    @Serializable
    data object Fresh : SourceStatus

    /** Fetch failed (or skipped); showing cached data from [since]. */
    @Serializable
    data class Stale(@Serializable(with = InstantIso8601Serializer::class) val since: Instant) : SourceStatus

    /**
     * No data at all. [error] says what went wrong so the app can show a localized text; [message] is the original text
     * (shown for an error the app does not know, and for failures stored by an older version, which have no [code]).
     */
    @Serializable
    data class Error(
        val message: String,
        /** [SunWattError.code] of the failure; null for an error that is not a [SunWattError] and for old stored data. */
        val code: String? = null,
        /** [SunWattError.detail] of the failure. */
        val detail: String? = null,
    ) : SourceStatus {
        /** The failure as a [SunWattError], when [code] names one. */
        val error: SunWattError? get() = code?.let { SunWattError.fromCode(it, detail) }

        companion object {
            /** The status for [failure]. Never carries a secret: a [SunWattError] message holds none. */
            fun of(failure: Throwable): Error = when (failure) {
                is SunWattError -> Error(failure.message.orEmpty(), failure.code, failure.detail)
                else -> Error(failure.message ?: failure::class.java.simpleName)
            }
        }
    }

    /** Solar only: no kiosk URL configured. */
    @Serializable
    data object NotConfigured : SourceStatus
}

/**
 * One plant's solar data in a load: what [SunWattData.solar], [SunWattData.solarStatus] and
 * [SunWattData.solarLinkExpired] hold for the plant shown, for any plant.
 */
@Serializable
data class PlantSolarData(
    /** [SolarPlant.id]. */
    val id: String,
    /** The name to show ([SolarPlant.displayName]). */
    val name: String,
    val solar: SolarSnapshot?,
    val status: SourceStatus,
    val linkExpired: Boolean = false,
)

/**
 * Everything the UI needs at one moment: last good data for both sources plus their status.
 *
 * With several plants, [solar], [solarStatus] and [solarLinkExpired] describe the plant shown ([plantID], the first one
 * unless [showingPlant] picked another) and [otherPlants] the rest, in settings order after it. With one plant,
 * [otherPlants] is empty and [plantName] null: everything reads as before.
 */
@Serializable
data class SunWattData(
    val solar: SolarSnapshot? = null,
    val prices: PriceSeries? = null,
    val solarStatus: SourceStatus,
    val priceStatus: SourceStatus,
    /**
     * True when Huawei has rejected the kiosk link for at least `SunWattLoader.kioskExpiredAfter`: the link most likely
     * expired. Views then ask the user to replace it. Not stored (worked out by the loader).
     */
    val solarLinkExpired: Boolean = false,
    /** The plant in [solar]. */
    val plantID: String = SolarPlant.PRIMARY_ID,
    /** Its name, set only with several plants (views show it to tell the plants apart). */
    val plantName: String? = null,
    /** The other plants. Empty with one plant. */
    val otherPlants: List<PlantSolarData> = emptyList(),
) {
    /** Most recent successful fetch time across both sources. */
    val lastUpdated: Instant? get() = listOfNotNull(solar?.fetchedAt, prices?.fetchedAt).maxOrNull()

    /** True with more than one plant. */
    val hasSeveralPlants: Boolean get() = otherPlants.isNotEmpty()

    /** Every plant, the one shown first. */
    val allPlants: List<PlantSolarData>
        get() = listOf(PlantSolarData(plantID, plantName ?: "", solar, solarStatus, solarLinkExpired)) + otherPlants

    /**
     * The same data with the plant at [index] of [allPlants] shown (widgets rotate, the dashboard pages). The others
     * keep their order. Out-of-range indexes wrap around.
     */
    fun showingPlant(index: Int): SunWattData {
        val plants = allPlants
        if (plants.size <= 1) return this
        val position = Math.floorMod(index, plants.size)
        return showing(plants[position], plants.filterIndexed { i, _ -> i != position })
    }

    /** The same data with the plant [id] shown; unchanged when there is no such plant. */
    fun showingPlant(id: String): SunWattData {
        val index = allPlants.indexOfFirst { it.id == id }
        return if (index < 0) this else showingPlant(index)
    }

    private fun showing(plant: PlantSolarData, others: List<PlantSolarData>): SunWattData = copy(
        plantID = plant.id, plantName = plant.name, solar = plant.solar, solarStatus = plant.status,
        solarLinkExpired = plant.linkExpired, otherPlants = others,
    )

    /**
     * Fills the solar part from one result per plant, the first shown (Swift `setPlants`; the loader, phase 3b, calls
     * it). A plant name is kept only with several plants, so one plant reads exactly as before.
     */
    fun withPlants(plants: List<PlantSolarData>): SunWattData {
        val first = plants.firstOrNull() ?: return this
        return copy(
            plantID = first.id, plantName = if (plants.size > 1) first.name else null, solar = first.solar,
            solarStatus = first.status, solarLinkExpired = first.linkExpired, otherPlants = plants.drop(1),
        )
    }
}

/** One widget timeline entry. Views derive the current hour from [date]. */
data class SunWattEntry(val date: Instant, val data: SunWattData) {
    private val reference: Instant? get() = data.solar?.let { minOf(it.fetchedAt, it.dataAsOf ?: it.fetchedAt) }

    /**
     * True when the solar snapshot belongs to an earlier Europe/Madrid day than [date]: its curve and its "today" values
     * describe yesterday. Compares calendar days, not 24-hour spans (DST days have 23/25 hours).
     */
    val isSolarFromEarlierDay: Boolean
        get() {
            val earlier = reference ?: return false
            return date.atZone(Constants.madrid).toLocalDate() > earlier.atZone(Constants.madrid).toLocalDate()
        }

    /**
     * The snapshot views must show at [date]. After midnight a snapshot from the previous day loses its curve, the
     * current power and the daily energy (they belong to yesterday), so the views show "no production" with the stale
     * marker instead of yesterday's numbers as today's. Month, year and total energy are kept.
     */
    val solarForDisplay: SolarSnapshot?
        get() {
            val solar = data.solar
            if (solar == null || !isSolarFromEarlierDay) return solar
            // Month and year totals of an earlier month / year do not belong to the current one (1 November, 1 January).
            val reference = checkNotNull(reference).atZone(Constants.madrid)
            val now = date.atZone(Constants.madrid)
            return solar.copy(
                monthEnergyKWh = if (YearMonth.from(reference) == YearMonth.from(now)) solar.monthEnergyKWh else null,
                yearEnergyKWh = if (Year.from(reference) == Year.from(now)) solar.yearEnergyKWh else null,
                curve = emptyList(), currentPowerKW = null, dailyEnergyKWh = null, dataAsOf = null, home = null,
            )
        }

    /** Every plant as views show it at [date] ([solarForDisplay] of each), the one shown first. */
    val plantsForDisplay: List<PlantSolarData>
        get() = data.allPlants.map { plant ->
            plant.copy(solar = SunWattEntry(date, data.showingPlant(plant.id)).solarForDisplay)
        }

    /**
     * One plant: [solarForDisplay]. Several: their figures added up (power, today's, month's and year's energy; a figure
     * is null when no plant has it), without a curve. `fetchedAt` is the newest fetch, `dataAsOf` the oldest reading.
     * Null when no plant has data.
     */
    val combinedSolarForDisplay: SolarSnapshot?
        get() {
            if (!data.hasSeveralPlants) return solarForDisplay
            val shown = plantsForDisplay.mapNotNull { it.solar }
            val newest = shown.maxOfOrNull { it.fetchedAt } ?: return null
            fun sum(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
            return SolarSnapshot(
                currentPowerKW = sum(shown.map { it.currentPowerKW }), dailyEnergyKWh = sum(shown.map { it.dailyEnergyKWh }),
                monthEnergyKWh = sum(shown.map { it.monthEnergyKWh }), yearEnergyKWh = sum(shown.map { it.yearEnergyKWh }),
                curve = emptyList(), fetchedAt = newest, dataAsOf = shown.mapNotNull { it.dataAsOf }.minOrNull(),
            )
        }

    /**
     * The entry with every plant added up as one plant (complications, which have no room for names). Stale when any
     * plant is; not set up only when no plant is. One plant: unchanged.
     */
    val combinedPlants: SunWattEntry
        get() {
            if (!data.hasSeveralPlants) return this
            val plants = plantsForDisplay
            val combinedSolar = combinedSolarForDisplay
            val staleSince = plants.mapNotNull { (it.status as? SourceStatus.Stale)?.since }.minOrNull()
            val status = when {
                combinedSolar == null ->
                    if (plants.all { it.status == SourceStatus.NotConfigured }) SourceStatus.NotConfigured
                    else SourceStatus.Error.of(SunWattError.Unavailable)
                staleSince != null -> SourceStatus.Stale(staleSince)
                else -> SourceStatus.Fresh
            }
            return SunWattEntry(
                date,
                data.copy(
                    plantID = SolarPlant.PRIMARY_ID, plantName = null, otherPlants = emptyList(), solarLinkExpired = false,
                    solar = combinedSolar, solarStatus = status,
                ),
            )
        }

    /**
     * Solar data is shown as stale when older than [Constants.staleAfter] at [date], when its status is stale, or when it
     * was fetched on an earlier Madrid day.
     */
    val isSolarStale: Boolean
        get() {
            if (data.solarStatus is SourceStatus.Stale) return true
            val solar = data.solar
            if (solar != null && Duration.between(solar.fetchedAt, date) > Constants.staleAfter.toJavaDuration()) {
                return true
            }
            return isSolarFromEarlierDay
        }

    /**
     * True when the price status is stale or the series has no point covering [date]. False when there are no prices at
     * all: the view shows the status (error / empty) instead of a stale marker.
     */
    val isPriceStale: Boolean
        get() {
            if (data.priceStatus is SourceStatus.Stale) return true
            val prices = data.prices
            return prices != null && prices.points.none { date in it }
        }
}
