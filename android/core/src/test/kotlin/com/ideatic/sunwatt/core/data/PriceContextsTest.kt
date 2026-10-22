package com.ideatic.sunwatt.core.data

import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceDayContexts
import com.ideatic.sunwatt.core.models.hourly
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.hoursOnDayOf
import com.ideatic.sunwatt.core.models.hoursAround
import com.ideatic.sunwatt.core.models.statsOnDayOf
import com.ideatic.sunwatt.core.models.fraction
import com.ideatic.sunwatt.core.models.level
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceContextsTest {
    @Test
    fun matchesPerPointCallsOnATwoDayChart() {
        // Two different price levels per day, so a context mixed up between days would show.
        val series = hourlySeries(madrid(2026, 6, 1), 48) { if (it < 24) 50.0 + it * 7 else 400.0 + (it - 24) * 3 }
        val contexts = PriceDayContexts(series, series.points)
        assertEquals(2, contexts.dayCount)
        for (point in series.points) {
            assertEquals(series.fraction(point), contexts.fraction(point))
            assertEquals(series.level(point), contexts.level(point))
        }
    }

    @Test
    fun matchesOnTheFallBackDayWith25Hours() {
        val series = hourlySeries(madrid(2026, 10, 25), 25 + 24) { ((it * 37) % 90) + 20.0 }
        val contexts = PriceDayContexts(series, series.points)
        assertEquals(2, contexts.dayCount)
        for (point in series.points) assertEquals(series.fraction(point), contexts.fraction(point))
    }

    @Test
    fun oneContextPerDayNotPerPoint() {
        val series = hourlySeries(madrid(2026, 6, 1), 24) { it.toDouble() }
        assertEquals(1, PriceDayContexts(series, series.points).dayCount)
        // A window around now that crosses midnight needs two.
        val more = hourlySeries(madrid(2026, 6, 1), 48) { it.toDouble() }
        val around = more.hoursAround(madrid(2026, 6, 1, 20), before = 6, after = 12)
        assertEquals(19, around.size)
        assertEquals(2, PriceDayContexts(more, around).dayCount)
    }

    @Test
    fun flatDayAndUnknownDayGiveTheMiddle() {
        val flat = hourlySeries(madrid(2026, 6, 1), 24) { 100.0 }
        val contexts = PriceDayContexts(flat, flat.points)
        assertEquals(0.5, contexts.fraction(flat.points[5]))
        val outside = PricePoint(madrid(2026, 7, 1, 10), 3600, 999.0)
        assertEquals(0.5, contexts.fraction(outside))
        assertEquals(contexts.fraction(outside), flat.fraction(outside))
        assertEquals(0, PriceDayContexts(flat, emptyList()).dayCount)
    }

    @Test
    fun otherRegionsUseTheirOwnTimeZone() {
        // Lisbon midnight is one hour after Madrid midnight in summer.
        val lisbon = PriceRegion.PORTUGAL.timeZone
        val start = LocalDate.of(2026, 6, 1).atStartOfDay(lisbon).toInstant()
        val series = hourlySeries(start, 48, PriceRegion.PORTUGAL) { if (it < 24) it.toDouble() else 200.0 - it }
        val contexts = PriceDayContexts(series, series.points)
        assertEquals(2, contexts.dayCount)
        for (point in series.points) assertEquals(series.fraction(point), contexts.fraction(point))
        // Madrid's calendar would put the first hour of Lisbon's day in the previous day of its own.
        assertEquals(24, series.hoursOnDayOf(start).size)
    }

    // --- Analysis the contexts build on

    @Test
    fun quarterHourPointsAverageIntoHours() {
        val day = madrid(2026, 6, 1)
        val points = (0 until 8).map { PricePoint(day.plusSeconds(it * 900L), 900, if (it < 4) 100.0 else 200.0) }
        val series = PriceSeries(points, PriceGranularity.QUARTER_HOURLY, day)
        assertEquals(listOf(100.0, 200.0), series.hourly().map { it.eurPerMWh })
        assertEquals(day, series.currentHour(day.plusSeconds(1000))?.start)
    }

    @Test
    fun levelsSplitTheDayInThirds() {
        val series = hourlySeries(madrid(2026, 6, 1), 24) { it.toDouble() * 10 }
        assertEquals(PriceLevel.LOW, series.level(series.points[2]))
        assertEquals(PriceLevel.MEDIUM, series.level(series.points[12]))
        assertEquals(PriceLevel.HIGH, series.level(series.points[22]))
        assertEquals(0.0, series.statsOnDayOf(madrid(2026, 6, 1, 5))?.min)
        assertEquals(0.23, series.statsOnDayOf(madrid(2026, 6, 1, 5))?.max)
    }

    @Test
    fun cheapestUpcomingSkipsThePastAndSortsByStart() {
        val prices = listOf(50.0, 10.0, 30.0, 20.0, 40.0, 5.0)
        val series = hourlySeries(madrid(2026, 6, 1), 6) { prices[it] }
        val now = madrid(2026, 6, 1, 1, 30)
        // Hour 5 (5.0) is the cheapest, then hour 1 (10.0, current hour counts), then hour 3 (20.0).
        assertEquals(listOf(1, 3, 5), series.cheapestUpcoming(now).map { ((it.start.epochSecond - madrid(2026, 6, 1).epochSecond) / 3600).toInt() })
        assertTrue(series.cheapestUpcoming(now, count = 0).isEmpty())
        assertEquals(listOf(1, 3), series.cheapestUpcoming(now, count = 2, until = madrid(2026, 6, 1, 4)).map { ((it.start.epochSecond - madrid(2026, 6, 1).epochSecond) / 3600).toInt() })
    }
}
