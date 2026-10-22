package com.ideatic.sunwatt.core.models

import java.time.Instant

/**
 * Hourly points of one market day plus their statistics. Build it once per render with [dayContext] instead of
 * calling `statsOnDayOf` / `fraction` / `level` per point.
 */
data class PriceDayContext(
    /** Hourly points of the day (23, 24 or 25 on DST days), sorted by start. */
    val hours: List<PricePoint>,
    val stats: PriceStats,
) {
    /** Position 0...1 of [point] within the day's min...max (0.5 if flat). */
    fun fraction(point: PricePoint): Double {
        if (stats.max <= stats.min) return 0.5
        return ((point.eurPerKWh - stats.min) / (stats.max - stats.min)).coerceIn(0.0, 1.0)
    }

    /** Level of [point] relative to the day's range (lower third = low, upper third = high). */
    fun level(point: PricePoint): PriceLevel = PriceLevel.classify(fraction(point))
}

/**
 * Day contexts for the points of one render, built once: one [PriceDayContext] per market day that appears in
 * `points` (a two-day chart builds two). Gives the same answers as `PriceSeries.fraction` / `level` without recomputing
 * the hourly aggregation and the day statistics for every bar.
 */
class PriceDayContexts(series: PriceSeries, points: List<PricePoint>) {
    private val zone = series.region.timeZone
    private val contexts = HashMap<Instant, PriceDayContext>()

    init {
        for (point in points) {
            val key = dayKey(point.start)
            if (key in contexts) continue
            series.dayContext(point.start)?.let { contexts[key] = it }
        }
    }

    private fun dayKey(date: Instant): Instant = date.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    /** Position 0...1 of [point] within its day's range; 0.5 when the day is unknown or flat. */
    fun fraction(point: PricePoint): Double = contexts[dayKey(point.start)]?.fraction(point) ?: 0.5

    fun level(point: PricePoint): PriceLevel = PriceLevel.classify(fraction(point))

    /** Number of distinct days built (tests). */
    internal val dayCount: Int get() = contexts.size
}

// Price analysis. All day math uses `region.timeZone` (the market time zone of the series), never the device zone.

/** Start of the absolute hour containing [date] (offsets of all supported regions are whole hours, so this is DST-safe). */
private fun hourStart(date: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(date.epochSecond, 3600L) * 3600L)

/** Points aggregated to hourly (average of the 15-min points in each hour). Identity when already hourly. */
fun PriceSeries.hourly(): List<PricePoint> {
    if (granularity == PriceGranularity.HOURLY) return points
    // Per hour: running sum and count, in first-seen order.
    val sums = LinkedHashMap<Instant, DoubleArray>()
    for (point in points) {
        val acc = sums.getOrPut(hourStart(point.start)) { DoubleArray(2) }
        acc[0] += point.eurPerMWh
        acc[1] += 1.0
    }
    return sums.entries.sortedBy { it.key }.map { PricePoint(it.key, 3600, it.value[0] / it.value[1]) }
}

/** Hourly point that contains [date]. */
fun PriceSeries.currentHour(date: Instant): PricePoint? = hourly().firstOrNull { date in it }

/** Hourly points of the market day (in the region's time zone) containing [date] (23, 24 or 25 points). */
fun PriceSeries.hoursOnDayOf(date: Instant): List<PricePoint> {
    val zone = region.timeZone
    val day = date.atZone(zone).toLocalDate()
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    return hourly().filter { it.start >= start && it.start < end }
}

/**
 * Hourly points from [before] hours before to [after] hours after the hour containing [date] (inclusive at both
 * ends), aligned to hour starts.
 */
fun PriceSeries.hoursAround(date: Instant, before: Int = 6, after: Int = 12): List<PricePoint> {
    val current = hourStart(date)
    val lower = current.minusSeconds(before * 3600L)
    val upper = current.plusSeconds(after * 3600L)
    return hourly().filter { it.start >= lower && it.start <= upper }
}

/** Hours and statistics of the market day containing [date], computed once. Null when the series has no point that day. */
fun PriceSeries.dayContext(date: Instant): PriceDayContext? {
    val day = hoursOnDayOf(date)
    if (day.isEmpty()) return null
    val prices = day.map { it.eurPerKWh }
    val average = prices.fold(0.0) { sum, price -> sum + price } / day.size
    return PriceDayContext(day, PriceStats(prices.min(), prices.max(), average))
}

/** Min/max/average EUR/kWh of the market day containing [date]. */
fun PriceSeries.statsOnDayOf(date: Instant): PriceStats? = dayContext(date)?.stats

/**
 * Level of [point] relative to its day's range (lower third = low, upper third = high). Rebuilds the day context on
 * every call: for many points use [PriceDayContexts] or [dayContext].
 */
fun PriceSeries.level(point: PricePoint): PriceLevel = PriceLevel.classify(fraction(point))

/**
 * Position 0...1 of [point] within its day's min...max (0.5 if flat or unknown). Rebuilds the day context on every
 * call: for many points use [PriceDayContexts] or [dayContext].
 */
fun PriceSeries.fraction(point: PricePoint): Double = dayContext(point.start)?.fraction(point) ?: 0.5

/**
 * The [count] cheapest hourly points that start at or after the current hour, sorted by start time. [until]: only
 * hours starting before it (e.g. the end of the shown day), so a cheaper next day does not push the rest of this day
 * out of the ranking.
 */
fun PriceSeries.cheapestUpcoming(from: Instant, count: Int = 3, until: Instant? = null): List<PricePoint> {
    if (count <= 0) return emptyList()
    val current = hourStart(from)
    val upcoming = hourly().filter { it.start >= current && (until == null || it.start < until) }
    return upcoming.sortedWith(compareBy<PricePoint> { it.eurPerMWh }.thenBy { it.start })
        .take(count)
        .sortedBy { it.start }
}

/** Classification by fraction of the day's range: < 1/3 low, > 2/3 high. */
fun PriceLevel.Companion.classify(fraction: Double): PriceLevel = when {
    fraction < 1.0 / 3.0 -> PriceLevel.LOW
    fraction > 2.0 / 3.0 -> PriceLevel.HIGH
    else -> PriceLevel.MEDIUM
}
