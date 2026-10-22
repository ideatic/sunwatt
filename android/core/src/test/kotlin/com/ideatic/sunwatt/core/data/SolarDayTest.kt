package com.ideatic.sunwatt.core.data

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.timeline.TimelineBuilder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun solarData(solar: SolarSnapshot?, status: SourceStatus = SourceStatus.Fresh) =
    SunWattData(solar, null, status, SourceStatus.Error("x"))

private fun snapshot(fetchedAt: Instant, dataAsOf: Instant? = null) = SolarSnapshot(
    stationName = "Casa", currentPowerKW = 3.2, dailyEnergyKWh = 18.4, monthEnergyKWh = 410.0, yearEnergyKWh = 2900.0,
    cumulativeEnergyKWh = 9000.0,
    curve = listOf(CurvePoint(fetchedAt.minusSeconds(300), 3.1), CurvePoint(fetchedAt, 3.2)),
    fetchedAt = fetchedAt, dataAsOf = dataAsOf ?: fetchedAt, endpointID = "id",
)

class SolarDayTest {
    @Test
    fun sameDayShowsTheSnapshotUntouched() {
        val solar = snapshot(madrid(2026, 6, 1, 12))
        val entry = SunWattEntry(madrid(2026, 6, 1, 23, 59), solarData(solar))
        assertFalse(entry.isSolarFromEarlierDay)
        assertEquals(solar, entry.solarForDisplay)
    }

    @Test
    fun afterMidnightYesterdaysValuesAreHidden() {
        val solar = snapshot(madrid(2026, 6, 1, 23, 50))
        // 20 minutes later: well inside the 45-minute stale limit, but already another day.
        val entry = SunWattEntry(madrid(2026, 6, 2, 0, 10), solarData(solar))
        assertTrue(entry.isSolarFromEarlierDay)
        assertTrue(entry.isSolarStale)
        val shown = assertNotNull(entry.solarForDisplay)
        assertTrue(shown.curve.isEmpty())
        assertNull(shown.currentPowerKW)
        assertNull(shown.dailyEnergyKWh)
        assertNull(shown.dataAsOf)
        assertTrue(shown.isIdle)
        // Not daily values: kept.
        assertEquals("Casa", shown.stationName)
        assertEquals(410.0, shown.monthEnergyKWh)
        assertEquals(2900.0, shown.yearEnergyKWh)
        assertEquals(9000.0, shown.cumulativeEnergyKWh)
        assertEquals(solar.fetchedAt, shown.fetchedAt)
    }

    @Test
    fun aReadingFromAnEarlierDayThanTheFetchCounts() {
        // dataAsOf on June 1, fetched on June 2: the curve is still yesterday's.
        val solar = snapshot(madrid(2026, 6, 2, 0, 5), dataAsOf = madrid(2026, 6, 1, 23, 55))
        val entry = SunWattEntry(madrid(2026, 6, 2, 0, 10), solarData(solar))
        assertTrue(entry.isSolarFromEarlierDay)
        assertTrue(entry.solarForDisplay?.curve?.isEmpty() == true)
    }

    @Test
    fun noSolarMeansNothingToHide() {
        val entry = SunWattEntry(madrid(2026, 6, 2, 0, 10), solarData(null, SourceStatus.NotConfigured))
        assertFalse(entry.isSolarFromEarlierDay)
        assertNull(entry.solarForDisplay)
    }

    @Test
    fun timelineEntriesPastMidnightHideYesterday() {
        val fetched = madrid(2026, 6, 1, 22, 30)
        val entries = TimelineBuilder.entries(solarData(snapshot(fetched)), fetched, hours = 4)
        // 22:30, 23:00, 00:00, 01:00, 02:00
        assertEquals(listOf(false, false, true, true, true), entries.map { it.isSolarFromEarlierDay })
        assertTrue(entries[1].solarForDisplay?.curve?.isNotEmpty() == true)
        assertTrue(entries[2].solarForDisplay?.curve?.isEmpty() == true)
    }

    // --- DST (Europe/Madrid)

    @Test
    fun fallBackDayIs25HoursNot24() {
        // 2026-10-25 has 25 hours. Fetched at its first minutes, 24 h 15 min later it is still the same day.
        val fetched = madrid(2026, 10, 25, 0, 5)
        val data = solarData(snapshot(fetched))
        val late = SunWattEntry(fetched.plusSeconds(24 * 3600 + 15 * 60), data)
        assertEquals(late.date.atZone(Constants.madrid).toLocalDate(), fetched.atZone(Constants.madrid).toLocalDate())
        assertFalse(late.isSolarFromEarlierDay)
        assertEquals(18.4, late.solarForDisplay?.dailyEnergyKWh)
        // 25 hours later it is the 26th.
        assertTrue(SunWattEntry(fetched.plusSeconds(25 * 3600), data).isSolarFromEarlierDay)
    }

    @Test
    fun midnightsAroundTheFallBackDay() {
        val before = solarData(snapshot(madrid(2026, 10, 24, 23, 50)))
        assertTrue(SunWattEntry(madrid(2026, 10, 25, 0, 10), before).isSolarFromEarlierDay)
        val during = solarData(snapshot(madrid(2026, 10, 25, 23, 50)))
        assertTrue(SunWattEntry(madrid(2026, 10, 26, 0, 10), during).isSolarFromEarlierDay)
        assertFalse(SunWattEntry(madrid(2026, 10, 25, 23, 55), during).isSolarFromEarlierDay)
    }

    @Test
    fun repeatedHourOnFallBackDayStaysSameDay() {
        // 02:30 happens twice on 2026-10-25 (CEST, then CET).
        val firstTwoThirty = madrid(2026, 10, 25, 0, 30).plusSeconds(2 * 3600)
        val secondTwoThirty = firstTwoThirty.plusSeconds(3600)
        assertFalse(SunWattEntry(secondTwoThirty, solarData(snapshot(firstTwoThirty))).isSolarFromEarlierDay)
    }

    @Test
    fun springForwardDayIs23Hours() {
        val fetched = madrid(2026, 3, 29, 0, 5)
        val data = solarData(snapshot(fetched))
        assertFalse(SunWattEntry(fetched.plusSeconds(22 * 3600 + 30 * 60), data).isSolarFromEarlierDay)
        assertTrue(SunWattEntry(fetched.plusSeconds(23 * 3600), data).isSolarFromEarlierDay)
    }

    // --- Month and year totals follow the calendar (Swift ReviewFixTests #11)

    @Test
    fun monthAndYearTotalsFollowTheCalendar() {
        var snapshot = sampleSolar(madrid(2026, 10, 31, 20)).copy(
            dataAsOf = madrid(2026, 10, 31, 19), monthEnergyKWh = 450.0, yearEnergyKWh = 5000.0,
        )
        val data = SunWattData(snapshot, null, SourceStatus.Stale(snapshot.fetchedAt), SourceStatus.Fresh)
        val nextDay = SunWattEntry(madrid(2026, 11, 1, 0, 20), data).solarForDisplay
        assertNull(nextDay?.monthEnergyKWh)
        assertEquals(5000.0, nextDay?.yearEnergyKWh)
        snapshot = snapshot.copy(fetchedAt = madrid(2026, 12, 31, 20), dataAsOf = madrid(2026, 12, 31, 19))
        val newYear = SunWattEntry(madrid(2027, 1, 1, 0, 20), solarData(snapshot)).solarForDisplay
        assertNull(newYear?.monthEnergyKWh)
        assertNull(newYear?.yearEnergyKWh)
        // Same month, next day: the month total stays.
        snapshot = snapshot.copy(fetchedAt = madrid(2026, 10, 30, 20), dataAsOf = madrid(2026, 10, 30, 19))
        assertEquals(450.0, SunWattEntry(madrid(2026, 10, 31, 0, 20), solarData(snapshot)).solarForDisplay?.monthEnergyKWh)
    }

    @Test
    fun staleStatusOfYesterdaysCacheGetsTheDayCheckFromTheEntry() {
        // The loader (phase 3b) keeps Stale(since = fetchedAt); the entry adds the day rule.
        val old = sampleSolar(madrid(2026, 5, 31, 20))
        val entry = SunWattEntry(madrid(2026, 6, 1, 12), SunWattData(old, null, SourceStatus.Stale(old.fetchedAt), SourceStatus.Fresh))
        assertTrue(entry.isSolarFromEarlierDay)
        assertTrue(entry.solarForDisplay?.curve?.isEmpty() == true)
        assertNull(entry.solarForDisplay?.dailyEnergyKWh)
    }
}
