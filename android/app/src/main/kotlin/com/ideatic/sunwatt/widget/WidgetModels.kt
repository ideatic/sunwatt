package com.ideatic.sunwatt.widget

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceStats
import com.ideatic.sunwatt.core.models.PriceWindow
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.WidgetContent
import com.ideatic.sunwatt.core.models.dayContext
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.hoursAround
import java.time.Instant
import java.time.ZoneId
import com.ideatic.sunwatt.core.models.Constants

// Pure widget state: what the widget views need from one timeline entry. Port of the Swift `WidgetModels.swift` and the
// state rules of `SunWattWidgetView`. No Android types, so every rule is tested on the JVM.

/** Message shown instead of figures when a source has no data (Swift `SourceStateView.Kind`). */
enum class StateKind {
    NOT_CONFIGURED,
    SOLAR_ERROR,

    /** FusionSolar keeps rejecting the kiosk link: it most likely expired. */
    LINK_EXPIRED,
    PRICE_ERROR,

    /** Both sources are down. */
    UNAVAILABLE,
}

/** Solar half of an entry, reduced to what the views need. */
class SolarModel(entry: SunWattEntry) {
    enum class Phase { NOT_CONFIGURED, ERROR, ACTIVE, IDLE }

    val snapshot: SolarSnapshot? = entry.solarForDisplay

    /** "Data as of" time shown next to the figures. */
    val dataTime: Instant? = snapshot?.let { it.dataAsOf ?: it.fetchedAt }

    /** The kiosk link keeps being rejected (most likely expired). */
    val linkExpired: Boolean = entry.data.solarLinkExpired

    /** Name of the plant shown; null with one plant (then nothing about plants is shown). */
    val plantName: String? = entry.data.plantName

    val phase: Phase = when (entry.data.solarStatus) {
        SourceStatus.NotConfigured -> Phase.NOT_CONFIGURED
        is SourceStatus.Error -> Phase.ERROR
        SourceStatus.Fresh, is SourceStatus.Stale ->
            if (snapshot == null) Phase.ERROR else if (snapshot.isIdle) Phase.IDLE else Phase.ACTIVE
    }

    /** Set when the data is stale: the time to show in the badge. */
    val staleSince: Instant? = if (entry.isSolarStale && snapshot != null) dataTime else null

    /** The message to show when there is no solar data. */
    val stateKind: StateKind
        get() = when {
            phase == Phase.NOT_CONFIGURED -> StateKind.NOT_CONFIGURED
            linkExpired -> StateKind.LINK_EXPIRED
            else -> StateKind.SOLAR_ERROR
        }

    val isAvailable: Boolean get() = phase == Phase.ACTIVE || phase == Phase.IDLE
    val isIdle: Boolean get() = phase == Phase.IDLE
    val powerKW: Double? get() = snapshot?.currentPowerKW
    val dailyKWh: Double? get() = snapshot?.dailyEnergyKWh

    /** The house line of the large widget; null without a meter at home (export-only plants show nothing). */
    val home: HomeCaption?
        get() {
            val use = snapshot?.home?.consumptionKW ?: return null
            val grid = snapshot.home?.gridKW
            return when {
                grid == null || Math.abs(grid) < 0.05 -> HomeCaption(use, HomeCaption.Grid.NONE, null)
                grid > 0 -> HomeCaption(use, HomeCaption.Grid.BUYING, Math.abs(grid))
                else -> HomeCaption(use, HomeCaption.Grid.SELLING, Math.abs(grid))
            }
        }
}

/** "Home 1,2 kW · selling 0,8 kW" as values. */
data class HomeCaption(val consumptionKW: Double, val grid: Grid, val gridKW: Double?) {
    enum class Grid { NONE, BUYING, SELLING }
}

/** Price half of an entry for one price window. */
class PriceModel(entry: SunWattEntry, window: PriceWindow) {
    val now: Instant = entry.date

    /** Null when unavailable: an error status means unavailable, even with leftover data. */
    val series: PriceSeries? = if (entry.data.priceStatus is SourceStatus.Error) null else entry.data.prices

    /** Points for the chart (whole day or around now). */
    val points: List<PricePoint>
    val current: PricePoint?
    val stats: PriceStats?
    val level: PriceLevel?

    /** Position 0...1 of the current price in the day's range (colour). */
    val fraction: Double?
    val staleSince: Instant?

    init {
        val s = series
        if (s == null) {
            points = emptyList(); current = null; stats = null; level = null; fraction = null; staleSince = null
        } else {
            // One day context for the whole model: the day's hours, statistics and the current hour's position.
            val context = s.dayContext(now)
            points = when (window) {
                PriceWindow.WHOLE_DAY -> context?.hours ?: emptyList()
                PriceWindow.AROUND -> s.hoursAround(now, 6, 12)
            }
            current = s.currentHour(now)
            stats = context?.stats
            level = if (current != null && context != null) context.level(current) else null
            fraction = if (current != null && context != null) context.fraction(current) else null
            staleSince = if (entry.isPriceStale) s.fetchedAt else null
        }
    }

    val timeZone: ZoneId get() = series?.region?.timeZone ?: Constants.madrid
    val region: PriceRegion get() = series?.region ?: PriceRegion.SPAIN
}

/** The three layouts, one per widget size (Swift `SunWattFamily` system sizes). */
enum class WidgetSize { SMALL, MEDIUM, LARGE }

object WidgetState {
    /**
     * When every source the widget shows is unavailable: the one message for the whole widget, else null (Swift
     * `fullStateKind`).
     */
    fun fullStateKind(solar: SolarModel, price: PriceModel, content: WidgetContent): StateKind? {
        val solarDown = !solar.isAvailable
        val priceDown = price.series == null
        return when (content) {
            WidgetContent.SOLAR_ONLY -> if (solarDown) solar.stateKind else null
            WidgetContent.PRICE_ONLY -> if (priceDown) StateKind.PRICE_ERROR else null
            WidgetContent.BOTH -> when {
                !(solarDown && priceDown) -> null
                solar.phase == SolarModel.Phase.NOT_CONFIGURED -> StateKind.PRICE_ERROR
                else -> StateKind.UNAVAILABLE
            }
        }
    }

    /** Small widget: `.both` falls back to whichever half is available. */
    fun smallContent(solar: SolarModel, price: PriceModel, content: WidgetContent): WidgetContent {
        if (content != WidgetContent.BOTH) return content
        if (solar.isAvailable) return WidgetContent.BOTH
        return if (price.series != null) WidgetContent.PRICE_ONLY else WidgetContent.BOTH
    }
}

/**
 * The entry a widget shows now: cached data (never the network, widgets only draw) at [now], with the plant its
 * settings choose. With several plants and "rotate" the turn depends on the time alone, so every widget agrees.
 */
fun widgetEntry(data: com.ideatic.sunwatt.core.data.SunWattData, now: Instant, plant: com.ideatic.sunwatt.core.timeline.WidgetPlantChoice): SunWattEntry =
    com.ideatic.sunwatt.core.timeline.TimelineBuilder.entries(data, now, plant, hours = 0).first()
