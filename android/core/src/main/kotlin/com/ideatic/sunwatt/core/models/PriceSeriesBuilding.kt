package com.ideatic.sunwatt.core.models

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Helpers the price clients share to build series. */
object PriceSeriesBuilding {
    /** Granularity from the smallest gap between consecutive sorted instants: 15 min or less is quarter-hourly. */
    fun granularity(starts: List<Instant>): PriceGranularity {
        var minGapMillis = Long.MAX_VALUE
        for (i in 1 until starts.size) {
            minGapMillis = minOf(minGapMillis, ChronoUnit.MILLIS.between(starts[i - 1], starts[i]))
        }
        return if (minGapMillis <= 900_000L) PriceGranularity.QUARTER_HOURLY else PriceGranularity.HOURLY
    }

    /** Points from sorted, unique `(start, EUR/MWh)` pairs, all with the duration implied by the granularity. */
    fun makePoints(values: List<Pair<Instant, Double>>): List<PricePoint> {
        val duration = if (granularity(values.map { it.first }) == PriceGranularity.QUARTER_HOURLY) 900L else 3600L
        return values.map { PricePoint(it.first, duration, it.second) }
    }

    /** `yyyy-MM-dd` of [date] in [zone]. */
    fun dayString(date: Instant, zone: ZoneId): String = date.atZone(zone).toLocalDate().toString()

    /**
     * Merges series of the same region: sorted, no duplicate starts (first wins). Quarter-hourly when any point lasts
     * 900 s or less.
     */
    fun merged(series: List<PriceSeries>, fetchedAt: Instant): PriceSeries {
        val byStart = LinkedHashMap<Instant, PricePoint>()
        for (point in series.flatMap { it.points }) {
            if (point.start !in byStart) byStart[point.start] = point
        }
        val points = byStart.values.sortedBy { it.start }
        val quarter = points.any { it.durationSeconds <= 900 }
        return PriceSeries(
            points = points,
            granularity = if (quarter) PriceGranularity.QUARTER_HOURLY else PriceGranularity.HOURLY,
            fetchedAt = fetchedAt,
            sourceUpdatedAt = series.mapNotNull { it.sourceUpdatedAt }.maxOrNull(),
            region = series.firstOrNull()?.region ?: PriceRegion.SPAIN,
        )
    }
}
