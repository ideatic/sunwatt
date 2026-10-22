package com.ideatic.sunwatt.wear.pages

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.ui.charts.ChartLayout
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A page of the watch app; keys are stable across data refreshes (plant ids, not positions). */
sealed interface WatchPage {
    val key: String

    data class Solar(val plantID: String) : WatchPage {
        override val key: String get() = "solar:$plantID"
    }

    data object Price : WatchPage {
        override val key: String get() = "price"
    }
}

/** Page order and the tap hit tests of the watch app (port of `WatchRootView` / `WatchPricePage` / `WatchSolarPage`). */
object WatchPages {
    /** A tapped hour or time goes back to "now" after this long (DECISIONS D29). */
    val SELECTION_TIMEOUT: Duration = 8.seconds

    /** One solar page per plant (one fixed page for a single plant), then the price page. */
    fun pages(data: SunWattData): List<WatchPage> {
        val solar = if (data.hasSeveralPlants) data.allPlants.map { WatchPage.Solar(it.id) } else listOf(WatchPage.Solar(data.plantID))
        return solar + WatchPage.Price
    }

    /** The first solar page, or the price page when no solar plant is set up (prices only: no setup prompt first). */
    fun firstPage(data: SunWattData): WatchPage {
        if (!data.hasSeveralPlants && data.solarStatus == SourceStatus.NotConfigured) return WatchPage.Price
        return WatchPage.Solar(data.plantID)
    }

    /** Index of [firstPage] in [pages]. */
    fun firstIndex(data: SunWattData): Int = pages(data).indexOf(firstPage(data)).coerceAtLeast(0)

    /**
     * The bar under a horizontal position (0 = left edge, 1 = right edge) of a chart without axes, whose x domain is
     * [ChartLayout.priceDomain].
     */
    fun pricePointAt(fraction: Double, points: List<PricePoint>): PricePoint? {
        val domain = ChartLayout.priceDomain(points) ?: return null
        val span = domain.endInclusive.epochSecond - domain.start.epochSecond
        val seconds = domain.start.epochSecond + (span * fraction.coerceIn(0.0, 0.9999)).toLong()
        val date = Instant.ofEpochSecond(seconds)
        return points.firstOrNull { date in it }
    }

    /**
     * The measured point nearest to a horizontal position (0 = left edge, 1 = right edge) of the mini chart, whose x
     * domain is [ChartLayout.solarDomain]. Null after the last measurement (the future) or without data.
     */
    fun solarPointAt(fraction: Double, curve: List<CurvePoint>, now: Instant): CurvePoint? {
        // Only points the chart draws (inside its domain).
        val domain = ChartLayout.solarDomain(curve, now)
        val measured = curve.filter { it.kW != null && it.time in domain }
        val last = measured.lastOrNull() ?: return null
        val span = domain.endInclusive.epochSecond - domain.start.epochSecond
        val seconds = domain.start.epochSecond + (span * fraction.coerceIn(0.0, 1.0)).toLong()
        // A tap well right of the curve's end is on empty chart, not on the latest point.
        if (seconds - last.time.epochSecond > 15 * 60) return null
        return measured.minByOrNull { kotlin.math.abs(it.time.epochSecond - seconds) }
    }
}
