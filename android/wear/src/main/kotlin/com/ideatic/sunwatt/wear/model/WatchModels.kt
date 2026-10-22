package com.ideatic.sunwatt.wear.model

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceDayContext
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceStats
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.dayContext
import java.time.Instant
import java.time.ZoneId

/** Solar half of an entry, reduced to what the watch views need (port of the Swift `SolarModel`). */
class SolarModel(entry: SunWattEntry) {
    enum class Phase { NOT_CONFIGURED, ERROR, ACTIVE, IDLE }

    val snapshot: SolarSnapshot? = entry.solarForDisplay

    /** "Data as of" time shown next to the figures. */
    val dataTime: Instant? = snapshot?.let { it.dataAsOf ?: it.fetchedAt }

    /** The kiosk link keeps being rejected (most likely expired). */
    val linkExpired: Boolean = entry.data.solarLinkExpired

    /** Name of the plant shown; null with one plant. */
    val plantName: String? = entry.data.plantName

    val phase: Phase = when (entry.data.solarStatus) {
        SourceStatus.NotConfigured -> Phase.NOT_CONFIGURED
        is SourceStatus.Error -> Phase.ERROR
        SourceStatus.Fresh, is SourceStatus.Stale ->
            if (snapshot == null) Phase.ERROR else if (snapshot.isIdle) Phase.IDLE else Phase.ACTIVE
    }

    /** Set when the data is stale; the time to show in the badge. */
    val staleSince: Instant? = if (entry.isSolarStale && snapshot != null) snapshot.dataAsOf ?: snapshot.fetchedAt else null

    val isAvailable: Boolean get() = phase == Phase.ACTIVE || phase == Phase.IDLE
    val isIdle: Boolean get() = phase == Phase.IDLE
    val powerKW: Double? get() = snapshot?.currentPowerKW
    val dailyKWh: Double? get() = snapshot?.dailyEnergyKWh

    /** Which message the page shows when there is no solar data. */
    val stateKind: StateKind
        get() = when {
            phase == Phase.NOT_CONFIGURED -> StateKind.NOT_CONFIGURED
            linkExpired -> StateKind.LINK_EXPIRED
            else -> StateKind.SOLAR_ERROR
        }
}

/** The full-area messages of a source without data (Swift `SourceStateView.Kind`). */
enum class StateKind { NOT_CONFIGURED, SOLAR_ERROR, LINK_EXPIRED, PRICE_ERROR }

/** Price half of an entry for the whole market day (port of the Swift `PriceModel` with the whole-day window). */
class PriceModel private constructor(
    val series: PriceSeries?,
    /** The day's hourly points (the chart bars). */
    val points: List<PricePoint>,
    val current: PricePoint?,
    val stats: PriceStats?,
    val level: PriceLevel?,
    val fraction: Double?,
    val staleSince: Instant?,
) {
    val isAvailable: Boolean get() = series != null && points.isNotEmpty()
    val timeZone: ZoneId get() = series?.region?.timeZone ?: Constants.madrid

    /** The same model with [point] in the place of the current hour (an hour tapped in the chart). */
    fun showing(point: PricePoint): PriceModel {
        val context = series?.dayContext(point.start) ?: return PriceModel(series, points, point, stats, level, fraction, staleSince)
        return PriceModel(series, points, point, stats, context.level(point), context.fraction(point), staleSince)
    }

    companion object {
        fun of(entry: SunWattEntry): PriceModel {
            val empty = PriceModel(null, emptyList(), null, null, null, null, null)
            // An error status means unavailable, even with leftover data.
            if (entry.data.priceStatus is SourceStatus.Error) return empty
            val series = entry.data.prices ?: return empty
            val context: PriceDayContext? = series.dayContext(entry.date)
            val current = series.currentHour(entry.date)
            return PriceModel(
                series = series,
                points = context?.hours ?: emptyList(),
                current = current,
                stats = context?.stats,
                level = if (current != null && context != null) context.level(current) else null,
                fraction = if (current != null && context != null) context.fraction(current) else null,
                staleSince = if (entry.isPriceStale) series.fetchedAt else null,
            )
        }
    }
}
