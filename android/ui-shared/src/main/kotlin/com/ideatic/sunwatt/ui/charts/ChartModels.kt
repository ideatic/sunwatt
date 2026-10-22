package com.ideatic.sunwatt.ui.charts

import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PriceDayContexts
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.hoursAround
import com.ideatic.sunwatt.core.models.hoursOnDayOf
import com.ideatic.sunwatt.ui.theme.PriceColors
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Which hourly points a price chart shows. Both read the hourly aggregation, so a 15-minute series draws hourly bars. */
sealed interface PriceWindow {
    /** The market day containing "now" (23, 24 or 25 bars on DST days). */
    data object Day : PriceWindow

    /** From [before] hours before to [after] hours after the current hour. */
    data class Around(val before: Int = 6, val after: Int = 12) : PriceWindow

    fun points(series: PriceSeries, now: Instant): List<PricePoint> = when (this) {
        Day -> series.hoursOnDayOf(now)
        is Around -> series.hoursAround(now, before, after)
    }
}

/** How a price bar is drawn relative to the current time. */
enum class PriceBarState {
    PAST,
    CURRENT,
    FUTURE;

    companion object {
        fun of(point: PricePoint, now: Instant): PriceBarState = when {
            now in point -> CURRENT
            point.end <= now -> PAST
            else -> FUTURE
        }
    }
}

/** One bar of the price chart, with every value the renderer needs. */
data class PriceBar(
    val point: PricePoint,
    val state: PriceBarState,
    /** Position 0...1 inside the day's range (colour scale). */
    val fraction: Double,
    /** Bar end value in EUR/kWh, with the minimum visible height applied (bars start at 0). */
    val end: Double,
)

/** Text of the selection callout. */
data class ChartCallout(val title: String, val detail: String, val tintFraction: Double? = null)

/** What the price chart draws, computed without drawing. Times are epoch seconds; the renderer maps them to pixels. */
class PriceChartModel(
    val bars: List<PriceBar>,
    val variant: ChartVariant,
    val zone: ZoneId,
    val xStart: Instant,
    val xEnd: Instant,
    /** Visible Y range, with headroom above the tallest bar for the "now" marker. */
    val yMin: Double,
    val yMax: Double,
    /** Height of the data range (before the headroom), used for outline and marker offsets. */
    val span: Double,
    /** Index of the highlighted bar: [PriceChartModel.from]'s `marked` or the bar containing now. */
    val currentIndex: Int?,
    val average: Double?,
    val yTicks: List<Double>,
    /** Hour labels of the X axis (every 3 hours of the market clock). */
    val xLabels: List<Instant>,
) {
    val isFull: Boolean get() = variant.isFull

    /** Callout for the bar at [index] (EUR/kWh and time range in the market zone). */
    fun callout(index: Int, locale: Locale = Locale.getDefault()): ChartCallout? {
        val bar = bars.getOrNull(index) ?: return null
        val p = bar.point
        return ChartCallout(
            title = "${Format.eurPerKWh(p.eurPerKWh, locale = locale)} €/kWh",
            detail = "${Format.time(p.start, zone, locale)}–${Format.time(p.end, zone, locale)}",
            tintFraction = bar.fraction,
        )
    }

    companion object {
        /** Model for the points of [window] (hourly) of [series]. */
        fun from(
            series: PriceSeries,
            now: Instant,
            window: PriceWindow = PriceWindow.Day,
            variant: ChartVariant = ChartVariant.APP,
            marked: PricePoint? = null,
        ): PriceChartModel = from(series, window.points(series, now), now, variant, marked)

        /**
         * Model for explicit [points] (already hourly). [marked]: a bar to mark instead of the current hour (a time tapped
         * on the watch); past/future shading still follows [now].
         */
        fun from(
            series: PriceSeries,
            points: List<PricePoint>,
            now: Instant,
            variant: ChartVariant = ChartVariant.APP,
            marked: PricePoint? = null,
        ): PriceChartModel {
            val zone = series.region.timeZone
            val isFull = variant.isFull
            val base = ChartLayout.priceYDomain(points)
            val span = base.endInclusive - base.start
            val current = marked ?: points.firstOrNull { now in it }
            val yMax = base.endInclusive + if (current == null) 0.0 else span * 0.14
            val minHeight = span * (if (isFull) PriceColors.MIN_BAR_FRACTION_FULL else PriceColors.MIN_BAR_FRACTION_MINI)
            val contexts = PriceDayContexts(series, points)
            val bars = points.map { point ->
                val value = point.eurPerKWh
                PriceBar(
                    point = point,
                    state = PriceBarState.of(point, now),
                    fraction = contexts.fraction(point),
                    end = if (value >= 0) max(value, minHeight) else min(value, -minHeight),
                )
            }
            val domain = ChartLayout.priceDomain(points)
            return PriceChartModel(
                bars = bars,
                variant = variant,
                zone = zone,
                xStart = domain?.start ?: now,
                xEnd = domain?.endInclusive ?: now.plusSeconds(1),
                yMin = base.start,
                yMax = yMax,
                span = span,
                currentIndex = current?.let { c -> points.indexOfFirst { it.start == c.start } }?.takeIf { it >= 0 },
                average = if (points.isEmpty()) null else points.sumOf { it.eurPerKWh } / points.size,
                yTicks = ChartLayout.priceTicks(base.endInclusive),
                xLabels = ChartLayout.axisHours(points, 3, zone),
            )
        }
    }
}

/** What the solar chart draws, computed without drawing. */
class SolarChartModel(
    val variant: ChartVariant,
    val xStart: Instant,
    val xEnd: Instant,
    /** Y range top: `yScaleMax` plus callout headroom in the app variant. */
    val yMax: Double,
    /** Y scale the ticks come from (the widget `full` chart uses it as its top). */
    val yScaleMax: Double,
    val points: List<SolarPlotPoint>,
    val yTicks: List<Double>,
    val xLabels: List<Instant>,
    /** Where the glowing dot sits: the marked point, else the latest one unless the plant is idle. */
    val dot: SolarPlotPoint?,
    /** Peak to label, only in a full variant, when it is not where the dot is. */
    val peak: SolarPlotPoint?,
    /** True when "now" lies inside the X range (full variants draw the dashed rule). */
    val nowInDomain: Boolean,
    val now: Instant,
    val marked: SolarPlotPoint?,
    val zone: ZoneId,
) {
    val isFull: Boolean get() = variant.isFull

    /** The plotted point closest in time to [time], within 20 minutes (the app selection rule). */
    fun nearest(time: Instant): SolarPlotPoint? {
        val best = points.minByOrNull { kotlin.math.abs(it.time.epochSecond - time.epochSecond) } ?: return null
        return best.takeIf { kotlin.math.abs(it.time.epochSecond - time.epochSecond) <= 20 * 60 }
    }

    fun callout(point: SolarPlotPoint, locale: Locale = Locale.getDefault()): ChartCallout =
        ChartCallout("${Format.kW(point.kW, locale)} kW", Format.time(point.time, zone, locale))

    companion object {
        /** Solar time labels use Europe/Madrid, as in the Swift charts. */
        fun from(
            snapshot: SolarSnapshot,
            now: Instant,
            variant: ChartVariant = ChartVariant.APP,
            marked: CurvePoint? = null,
            zone: ZoneId = Constants.madrid,
        ): SolarChartModel {
            val domain = ChartLayout.solarDomain(snapshot.curve, now, zone)
            val points = ChartLayout.solarPoints(snapshot.curve, domain)
            val yScaleMax = ChartLayout.solarYMax(points.maxOfOrNull { it.kW })
            val markedPoint = marked?.kW?.let { SolarPlotPoint(marked.time, max(0.0, it)) }
            val dot = markedPoint ?: if (snapshot.isIdle) null else points.lastOrNull()
            val top = points.maxByOrNull { it.kW }
            val latest = points.lastOrNull()
            // The peak label only helps when the peak is not already where the glowing dot is.
            val showsPeak = snapshot.isIdle || latest == null || latest.time.epochSecond - (top?.time?.epochSecond ?: 0) > 90 * 60
            return SolarChartModel(
                variant = variant,
                xStart = domain.start,
                xEnd = domain.endInclusive,
                yMax = if (variant == ChartVariant.APP) yScaleMax * 1.18 else yScaleMax,
                yScaleMax = yScaleMax,
                points = points,
                yTicks = ChartLayout.solarTicks(yScaleMax),
                xLabels = ChartLayout.axisHours(domain, 3, zone),
                dot = dot,
                peak = top?.takeIf { variant.isFull && it.kW > 0.05 && showsPeak },
                nowInDomain = now in domain,
                now = now,
                marked = markedPoint,
                zone = zone,
            )
        }
    }
}
