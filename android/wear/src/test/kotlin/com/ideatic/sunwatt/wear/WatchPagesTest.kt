package com.ideatic.sunwatt.wear

import com.ideatic.sunwatt.core.data.PlantSolarData
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.ui.charts.ChartLayout
import com.ideatic.sunwatt.wear.model.PriceModel
import com.ideatic.sunwatt.wear.pages.WatchPage
import com.ideatic.sunwatt.wear.pages.WatchPages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

class WatchPagesTest {
    private fun data(solar: SourceStatus) = SunWattData(solar = null, prices = null, solarStatus = solar, priceStatus = SourceStatus.Fresh)

    @Test
    fun solarFirstWhenSetUp() {
        val data = data(SourceStatus.Fresh)
        assertEquals(WatchPage.Solar(SolarPlant.PRIMARY_ID), WatchPages.firstPage(data))
        assertEquals(listOf(WatchPage.Solar(SolarPlant.PRIMARY_ID), WatchPage.Price), WatchPages.pages(data))
        assertEquals(0, WatchPages.firstIndex(data))
    }

    @Test
    fun priceFirstWithoutSolar() {
        val data = data(SourceStatus.NotConfigured)
        assertEquals(WatchPage.Price, WatchPages.firstPage(data))
        assertEquals(1, WatchPages.firstIndex(data))
    }

    @Test
    fun onePagePerPlantThenPricesAndTheFirstPlantFirst() {
        val data = data(SourceStatus.NotConfigured).withPlants(
            listOf(
                PlantSolarData(SolarPlant.PRIMARY_ID, "Home", null, SourceStatus.NotConfigured),
                PlantSolarData("second", "Barn", null, SourceStatus.Fresh),
            ),
        )
        assertEquals(WatchPage.Solar(SolarPlant.PRIMARY_ID), WatchPages.firstPage(data))
        assertEquals(
            listOf(WatchPage.Solar(SolarPlant.PRIMARY_ID), WatchPage.Solar("second"), WatchPage.Price),
            WatchPages.pages(data),
        )
        // Page keys are plant ids, not positions.
        assertEquals("solar:second", WatchPages.pages(data)[1].key)
    }

    @Test
    fun selectionGoesBackToNowAfterEightSeconds() {
        assertEquals(8.seconds, WatchPages.SELECTION_TIMEOUT)
    }

    @Test
    fun tapPositionPicksTheBarUnderIt() {
        val points = PriceModel.of(SampleData.entry(SampleData.Scenario.DAY)).points
        assertEquals(24, points.size)
        assertEquals(points.first(), WatchPages.pricePointAt(0.0, points))
        assertEquals(points[12], WatchPages.pricePointAt(0.5, points))
        assertEquals(points.last(), WatchPages.pricePointAt(1.0, points))
        assertEquals(points.first(), WatchPages.pricePointAt(-0.2, points))
        assertNull(WatchPages.pricePointAt(0.5, emptyList()))
    }

    @Test
    fun tappedHourReplacesTheCurrentOne() {
        val model = PriceModel.of(SampleData.entry(SampleData.Scenario.DAY))
        val tapped = model.points.maxBy { it.eurPerKWh }
        val shown = model.showing(tapped)
        assertEquals(tapped, shown.current)
        assertEquals(com.ideatic.sunwatt.core.models.PriceLevel.HIGH, shown.level)
        assertTrue((shown.fraction ?: 0.0) > 2.0 / 3)
        assertEquals(model.points, shown.points)
        assertEquals(model.staleSince, shown.staleSince)
    }

    @Test
    fun tapOnTheCurvePicksTheNearestMeasuredPoint() {
        val entry = SampleData.entry(SampleData.Scenario.DAY)
        val curve = entry.data.solar!!.curve
        val domain = ChartLayout.solarDomain(curve, entry.date)
        val measured = curve.filter { it.kW != null && it.time in domain }
        val span = domain.endInclusive.epochSecond - domain.start.epochSecond
        // A tap at 10:00 picks the point measured nearest to 10:00.
        val tenOClock = entry.date.atZone(Constants.madrid).withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant()
        val fraction = (tenOClock.epochSecond - domain.start.epochSecond).toDouble() / span
        val point = requireNotNull(WatchPages.solarPointAt(fraction, curve, entry.date))
        assertTrue(abs(point.time.epochSecond - tenOClock.epochSecond) <= 5 * 60)
        assertNotNull(point.kW)
        // Left edge: the first point the chart draws.
        assertEquals(measured.first(), WatchPages.solarPointAt(0.0, curve, entry.date))
    }

    @Test
    fun tapAfterTheLastMeasurementSelectsNothing() {
        val entry = SampleData.entry(SampleData.Scenario.DAY)
        // The day sample ends at 13:30; the right edge is the evening.
        assertNull(WatchPages.solarPointAt(1.0, entry.data.solar!!.curve, entry.date))
        assertNull(WatchPages.solarPointAt(0.5, emptyList(), entry.date))
    }
}
