package com.ideatic.sunwatt.core.price

import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.fraction
import com.ideatic.sunwatt.core.models.hoursAround
import com.ideatic.sunwatt.core.models.hoursOnDayOf
import com.ideatic.sunwatt.core.models.level
import com.ideatic.sunwatt.core.models.statsOnDayOf
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriceAnalysisTest {
    private fun series(name: String) = pvpcSeries(name)

    @Test
    fun currentHourPicksContainingPoint() {
        val s = series("pvpc-2026-10-17.json")
        assertEquals(madrid(2026, 10, 17, 14), s.currentHour(madrid(2026, 10, 17, 14, 37))?.start)
        assertEquals(madrid(2026, 10, 17, 15), s.currentHour(madrid(2026, 10, 17, 15))?.start)
        assertNull(s.currentHour(madrid(2026, 10, 18, 0, 5)))
        assertNull(s.currentHour(madrid(2026, 10, 16, 23, 59)))
    }

    @Test
    fun dstDayCounts() {
        assertEquals(23, series("pvpc-2026-03-29-dst23.json").hoursOnDayOf(madrid(2026, 3, 29, 12)).size)
        val fall = series("pvpc-2025-10-26-dst25.json")
        assertEquals(25, fall.hoursOnDayOf(madrid(2025, 10, 26, 12)).size)
        assertTrue(fall.hoursOnDayOf(madrid(2025, 10, 27, 12)).isEmpty())
    }

    @Test
    fun fallBackSecondPassIsSeparate() {
        val s = series("pvpc-2025-10-26-dst25.json")
        val point = assertNotNull(s.currentHour(iso("2025-10-26T02:30:00.000+01:00")))
        assertEquals(iso("2025-10-26T02:00:00.000+01:00"), point.start)
        assertEquals(126.46, point.eurPerMWh)
        val first = assertNotNull(s.currentHour(iso("2025-10-26T02:30:00.000+02:00")))
        assertEquals(131.07, first.eurPerMWh)
    }

    @Test
    fun hoursAroundAcrossDst() {
        val s = series("pvpc-2025-10-26-dst25.json")
        val around = s.hoursAround(iso("2025-10-26T02:30:00.000+01:00"), before = 6, after = 12)
        // 6 hours before the 02:00+01:00 hour starts at 20:00 prior day (absent); data runs 00:00+02:00 .. 13:00 UTC.
        assertEquals(16, around.size)
        assertEquals(iso("2025-10-26T00:00:00.000+02:00"), around.first().start)
        assertEquals(iso("2025-10-26T14:00:00.000+01:00"), around.last().start)
        assertTrue(around.any { it.eurPerMWh == 126.46 })
        assertTrue(around.any { it.eurPerMWh == 131.07 })
    }

    @Test
    fun hoursAroundIsInclusive() {
        val s = series("pvpc-2026-10-17.json")
        val around = s.hoursAround(madrid(2026, 10, 17, 10, 20), before = 2, after = 3)
        assertEquals((8..13).map { madrid(2026, 10, 17, it) }, around.map { it.start })
    }

    @Test
    fun statsMatchHandComputedValues() {
        val s = series("pvpc-2026-10-17.json")
        val stats = assertNotNull(s.statsOnDayOf(madrid(2026, 10, 17, 9)))
        assertTrue(abs(stats.min - 0.01781) < 1e-12)
        assertTrue(abs(stats.max - 0.24676) < 1e-12)
        assertTrue(abs(stats.average - 0.14432291666666666) < 1e-9)
        assertNull(s.statsOnDayOf(madrid(2026, 10, 19)))
    }

    @Test
    fun fractionAndLevel() {
        val s = series("pvpc-2026-10-17.json")
        val lowest = s.points.minBy { it.eurPerMWh }
        val highest = s.points.maxBy { it.eurPerMWh }
        assertEquals(0.0, s.fraction(lowest))
        assertEquals(1.0, s.fraction(highest))
        assertEquals(PriceLevel.LOW, s.level(lowest))
        assertEquals(PriceLevel.HIGH, s.level(highest))
        val mid = PricePoint(madrid(2026, 10, 17, 3), 3600, (17.81 + 246.76) / 2)
        assertTrue(abs(s.fraction(mid) - 0.5) < 1e-9)
        assertEquals(PriceLevel.MEDIUM, s.level(mid))
    }

    @Test
    fun flatDayFractionIsHalf() {
        val pts = (0 until 24).map { PricePoint(madrid(2026, 10, 17, it), 3600, 100.0) }
        val s = PriceSeries(pts, PriceGranularity.HOURLY, Instant.EPOCH)
        assertEquals(0.5, s.fraction(pts[5]))
        assertEquals(PriceLevel.MEDIUM, s.level(pts[5]))
    }

    @Test
    fun fractionUsesPointsOwnDay() {
        val today = series("pvpc-2026-10-17.json").points
        val tomorrow = today.map { PricePoint(it.start.plusSeconds(86_400), 3600, 100.0) }
        val s = PriceSeries(today + tomorrow, PriceGranularity.HOURLY, Instant.EPOCH)
        assertEquals(0.5, s.fraction(tomorrow[3]))
        assertEquals(1.0, s.fraction(today[0]))
    }

    @Test
    fun cheapestUpcoming() {
        val s = series("pvpc-2026-10-17.json")
        val picks = s.cheapestUpcoming(madrid(2026, 10, 17, 12, 30), count = 3)
        assertEquals(3, picks.size)
        assertTrue(picks.all { it.start >= madrid(2026, 10, 17, 12) })
        assertEquals(picks.sortedBy { it.start }, picks)
        val expected = s.points.filter { it.start >= madrid(2026, 10, 17, 12) }
            .sortedBy { it.eurPerMWh }.take(3).map { it.start }.sorted()
        assertEquals(expected, picks.map { it.start })
    }

    @Test
    fun cheapestUpcomingNearEndOfDay() {
        val s = series("pvpc-2026-10-17.json")
        val picks = s.cheapestUpcoming(madrid(2026, 10, 17, 22, 30), count = 3)
        assertEquals(listOf(madrid(2026, 10, 17, 22), madrid(2026, 10, 17, 23)), picks.map { it.start })
        assertTrue(s.cheapestUpcoming(madrid(2026, 10, 18, 3), count = 3).isEmpty())
        assertTrue(s.cheapestUpcoming(madrid(2026, 10, 17, 12), count = 0).isEmpty())
    }
}
