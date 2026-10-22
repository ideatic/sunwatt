package com.ideatic.sunwatt.ui.charts

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PricePoint
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where a chart is shown. Small and medium widgets draw the "mini" look (no axes); large widget and app draw the "full" one. */
enum class ChartVariant {
    WIDGET_SMALL,
    WIDGET_MEDIUM,
    WIDGET_LARGE,
    APP;

    /** True for the variants with axes, grid and "now" rule (Swift `.full`). */
    val isFull: Boolean get() = this == WIDGET_LARGE || this == APP
}

/** One plotted point of the production curve. */
data class SolarPlotPoint(val time: Instant, val kW: Double)

/** Pure layout helpers for the charts (port of `ChartLayout.swift`; testable without drawing). */
object ChartLayout {
    /** Hours shown on the solar X axis when the data does not ask for more (local daylight span). */
    val defaultSolarHours: IntRange = 6..21

    /** X domain of the production chart: 06:00-21:00 of the curve's day, widened to the whole hours with production. */
    fun solarDomain(curve: List<CurvePoint>, now: Instant, zone: ZoneId = Constants.madrid): ClosedRange<Instant> {
        val reference = curve.lastOrNull { it.kW != null }?.time ?: now
        val dayStart = reference.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        var first = defaultSolarHours.first
        var last = defaultSolarHours.last
        for (point in curve) {
            val kW = point.kW ?: continue
            if (kW <= 0.05) continue
            val hour = point.time.atZone(zone).hour
            first = min(first, hour)
            last = max(last, hour + 1)
        }
        // Calendar "byAdding hour" is elapsed time in Swift: same here.
        val lower = dayStart.plusSeconds(max(first, 0) * 3600L)
        val upper = dayStart.plusSeconds(min(last, 24) * 3600L)
        return lower..upper
    }

    /** Curve points inside [domain] with a value, negative values clamped to 0. Null points are dropped (the line joins across them, as in Swift). */
    fun solarPoints(curve: List<CurvePoint>, domain: ClosedRange<Instant>): List<SolarPlotPoint> =
        curve.mapNotNull { point ->
            val kW = point.kW ?: return@mapNotNull null
            if (point.time !in domain) null else SolarPlotPoint(point.time, max(0.0, kW))
        }

    /** Y upper bound for the production chart, with headroom above the peak. */
    fun solarYMax(peak: Double?): Double = max(2.0, (peak ?: 0.0) * 1.25)

    /** X domain of the price chart: first bar start to last bar end. */
    fun priceDomain(points: List<PricePoint>): ClosedRange<Instant>? {
        val first = points.firstOrNull() ?: return null
        return first.start..points.last().end
    }

    /** Y domain of the price chart in EUR/kWh: always includes 0 (negative wholesale prices extend below). */
    fun priceYDomain(points: List<PricePoint>): ClosedFloatingPointRange<Double> {
        val values = points.map { it.eurPerKWh }
        val lo = min(0.0, values.minOrNull() ?: 0.0)
        val hi = max(max(values.maxOrNull() ?: 0.0, 0.0), lo + 0.001)
        return lo..(hi + (hi - lo) * 0.08)
    }

    /** Hour starts to label on the price X axis: every [step] hours of the local clock. */
    fun axisHours(points: List<PricePoint>, step: Int, zone: ZoneId): List<Instant> =
        points.map { it.start }.filter { it.atZone(zone).hour % step == 0 }

    /** Hour starts to label on the production X axis. */
    fun axisHours(domain: ClosedRange<Instant>, step: Int, zone: ZoneId): List<Instant> {
        val result = ArrayList<Instant>()
        var date = domain.start
        while (date <= domain.endInclusive) {
            if (date.atZone(zone).hour % step == 0) result.add(date)
            date = date.plusSeconds(3600)
        }
        return result
    }

    /** Up to three Y axis values (0 and two multiples of a round step) for a chart spanning `0...top`. */
    fun axisTicks(top: Double, steps: List<Double>): List<Double> {
        if (top <= 0) return listOf(0.0)
        val step = steps.firstOrNull { top / it <= 3.5 } ?: steps.lastOrNull() ?: top
        return listOf(0.0, step, step * 2).filter { it <= top }
    }

    fun solarTicks(yMax: Double): List<Double> = axisTicks(yMax, listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0))

    fun priceTicks(yMax: Double): List<Double> = axisTicks(yMax, listOf(0.02, 0.05, 0.1, 0.2, 0.5, 1.0))

    /** Axis label text: whole numbers without decimals, else [decimals] figures in the locale's format. */
    fun axisNumber(value: Double, decimals: Int, locale: Locale = Locale.getDefault()): String {
        val whole = abs(value - Math.rint(value)) < 0.0001
        val format = NumberFormat.getNumberInstance(locale)
        format.minimumFractionDigits = if (whole) 0 else decimals
        format.maximumFractionDigits = if (whole) 0 else decimals
        return format.format(value)
    }
}
