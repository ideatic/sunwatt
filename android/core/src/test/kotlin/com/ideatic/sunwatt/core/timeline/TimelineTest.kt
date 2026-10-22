package com.ideatic.sunwatt.core.timeline

import com.ideatic.sunwatt.core.data.*
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun timelineData(
    solar: SolarSnapshot? = null, prices: PriceSeries? = null,
    solarStatus: SourceStatus = SourceStatus.Fresh, priceStatus: SourceStatus = SourceStatus.Fresh,
) = SunWattData(solar, prices, solarStatus, priceStatus)

private fun hour(date: Instant) = date.atZone(Constants.madrid).hour

class TimelineTest {
    @Test
    fun normalDay() {
        val now = madrid(2026, 6, 1, 14, 37)
        val entries = TimelineBuilder.entries(timelineData(), now, hours = 24)
        assertEquals(25, entries.size)
        assertEquals(now, entries[0].date)
        assertEquals(madrid(2026, 6, 1, 15), entries[1].date)
        assertEquals(madrid(2026, 6, 1, 16), entries[2].date)
        assertEquals(madrid(2026, 6, 2, 14), entries[24].date)
        assertTrue(entries.all { it.data == entries[0].data })
    }

    @Test
    fun exactlyOnTheHour() {
        val now = madrid(2026, 6, 1, 15)
        val entries = TimelineBuilder.entries(timelineData(), now, hours = 2)
        assertEquals(listOf(now, now.plusSeconds(3600), now.plusSeconds(7200)), entries.map { it.date })
    }

    @Test
    fun zeroHours() {
        assertEquals(1, TimelineBuilder.entries(timelineData(), madrid(2026, 6, 1, 10, 5), hours = 0).size)
    }

    @Test
    fun springForward() {
        val entries = TimelineBuilder.entries(timelineData(), madrid(2026, 3, 29, 0, 30), hours = 4)
        assertEquals(5, entries.size)
        assertEquals(listOf(0, 1, 3, 4, 5), entries.map { hour(it.date) })
        assertEquals(3600, Duration.between(entries[1].date, entries[2].date).seconds)
        assertFalse(entries.any { hour(it.date) == 2 })
    }

    @Test
    fun fallBack() {
        val entries = TimelineBuilder.entries(timelineData(), madrid(2026, 10, 25, 1, 30), hours = 4)
        assertEquals(5, entries.size)
        assertEquals(listOf(1, 2, 2, 3, 4), entries.map { hour(it.date) })
        for (i in 1 until entries.size) {
            assertEquals(if (i == 1) 1800L else 3600L, Duration.between(entries[i - 1].date, entries[i].date).seconds)
        }
    }

    @Test
    fun nextReload() {
        val now = madrid(2026, 6, 1, 10)
        assertEquals(now.plusSeconds(15 * 60), TimelineBuilder.nextReload(now))
    }

    @Test
    fun solarStaleFlags() {
        val fetched = madrid(2026, 6, 1, 12)
        val solar = sampleSolar(fetched)
        val data = timelineData(solar = solar)
        assertFalse(SunWattEntry(fetched.plusSeconds(44 * 60), data).isSolarStale)
        assertTrue(SunWattEntry(fetched.plusSeconds(50 * 60), data).isSolarStale)
        assertTrue(SunWattEntry(fetched, timelineData(solar = solar, solarStatus = SourceStatus.Stale(fetched))).isSolarStale)
        assertFalse(SunWattEntry(fetched, timelineData(solarStatus = SourceStatus.NotConfigured)).isSolarStale)
        assertFalse(SunWattEntry(fetched, timelineData(solarStatus = SourceStatus.Error("x"))).isSolarStale)
    }

    @Test
    fun priceStaleFlags() {
        val prices = samplePrices(day = madrid(2026, 6, 1))
        val data = timelineData(prices = prices)
        assertFalse(SunWattEntry(madrid(2026, 6, 1, 23, 30), data).isPriceStale)
        assertTrue(SunWattEntry(madrid(2026, 6, 2), data).isPriceStale)
        val stale = timelineData(prices = prices, priceStatus = SourceStatus.Stale(madrid(2026, 6, 1, 8)))
        assertTrue(SunWattEntry(madrid(2026, 6, 1, 9), stale).isPriceStale)
    }

    // --- From the Swift LoaderHardeningTests / EntryStalenessTests: only the pure entry rules

    @Test
    fun everyHourOfADstDayIsCoveredAndTheNextOneIsNot() {
        for ((day, nextDay, hours) in listOf(Triple(madrid(2026, 3, 29), madrid(2026, 3, 30), 23), Triple(madrid(2026, 10, 25), madrid(2026, 10, 26), 25))) {
            assertEquals(hours, Duration.between(day, nextDay).toHours().toInt())
            val series = hourlySeries(day, hours) { 50.0 }
            val data = timelineData(prices = series, solarStatus = SourceStatus.NotConfigured)
            for (point in series.points) assertFalse(SunWattEntry(point.start.plusSeconds(1800), data).isPriceStale)
            assertTrue(SunWattEntry(nextDay.plusSeconds(60), data).isPriceStale)
        }
    }

    @Test
    fun timelineSpanningMidnightWithoutTomorrowBecomesStale() {
        val series = hourlySeries(madrid(2026, 6, 1), 24) { 50.0 }
        val data = timelineData(prices = series, solarStatus = SourceStatus.NotConfigured)
        val entries = TimelineBuilder.entries(data, madrid(2026, 6, 1, 22, 30), hours = 6)
        // 22:30, 23:00, 00:00, 01:00, 02:00, 03:00, 04:00
        assertEquals(listOf(false, false, true, true, true, true, true), entries.map { it.isPriceStale })
    }

    // --- Rotation between plants

    private fun twoPlants(): SunWattData = timelineData(solar = sampleSolar(), solarStatus = SourceStatus.Fresh).withPlants(
        listOf(
            PlantSolarData("primary", "Home", sampleSolar(), SourceStatus.Fresh),
            PlantSolarData("second", "Barn", sampleSolar().copy(currentPowerKW = 1.0), SourceStatus.Fresh),
        ),
    )

    @Test
    fun aFixedPlantGetsHourlyEntriesWithThatPlantShown() {
        val entries = TimelineBuilder.entries(twoPlants(), madrid(2026, 6, 1, 12, 10), WidgetPlantChoice.Plant("second"), hours = 3)
        assertEquals(4, entries.size)
        assertTrue(entries.all { it.data.plantID == "second" })
        // A plant that no longer exists falls back to the data as given (the first plant).
        val gone = TimelineBuilder.entries(twoPlants(), madrid(2026, 6, 1, 12, 10), WidgetPlantChoice.Plant("gone"), hours = 3)
        assertTrue(gone.all { it.data.plantID == "primary" })
    }

    @Test
    fun rotatingEntriesFollowTheClockAndAgreeAcrossReloads() {
        val now = madrid(2026, 6, 1, 12, 10)
        val entries = TimelineBuilder.entries(twoPlants(), now, WidgetPlantChoice.Rotate, hours = 1)
        assertEquals(5, entries.size)
        assertEquals(now, entries[0].date)
        // The following entries sit on quarter hours, in order.
        assertEquals(listOf(madrid(2026, 6, 1, 12, 15), madrid(2026, 6, 1, 12, 30), madrid(2026, 6, 1, 12, 45), madrid(2026, 6, 1, 13)), entries.drop(1).map { it.date })
        for (entry in entries) {
            assertEquals(TimelineBuilder.turn(entry.date, 2), if (entry.data.plantID == "primary") 0 else 1)
        }
        // Consecutive quarter hours alternate between the two plants.
        assertEquals(entries.drop(1).map { it.data.plantID }.zipWithNext { a, b -> a != b }, listOf(true, true, true))
        // The same time gives the same plant in another reload.
        val again = TimelineBuilder.entries(twoPlants(), madrid(2026, 6, 1, 12, 14), WidgetPlantChoice.Rotate, hours = 1)
        assertEquals(entries.drop(1).map { it.data.plantID }, again.drop(1).map { it.data.plantID })
    }

    @Test
    fun onePlantIgnoresTheChoice() {
        val data = timelineData(solar = sampleSolar())
        val now = madrid(2026, 6, 1, 12, 10)
        assertEquals(TimelineBuilder.entries(data, now, 5), TimelineBuilder.entries(data, now, WidgetPlantChoice.Rotate, 5))
        assertEquals(0, TimelineBuilder.turn(now, 1))
    }
}
