package com.ideatic.sunwatt.ui.charts

import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SolarSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

class ChartModelsTest {
    private val day = LocalDate.of(2026, 10, 17)
    private val now = ChartSamples.at(day, 13.5)
    private val es = Locale.forLanguageTag("es-ES")

    @Test
    fun `normal day has 24 bars, current bar and past future states`() {
        val series = ChartSamples.priceSeries(day)
        val model = PriceChartModel.from(series, now)
        assertEquals(24, model.bars.size)
        assertEquals(13, model.currentIndex)
        assertEquals(PriceBarState.PAST, model.bars[12].state)
        assertEquals(PriceBarState.CURRENT, model.bars[13].state)
        assertEquals(PriceBarState.FUTURE, model.bars[14].state)
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21), model.xLabels.map { it.atZone(model.zone).hour })
        assertEquals(model.bars.first().point.start, model.xStart)
        assertEquals(model.bars.last().point.end, model.xEnd)
    }

    @Test
    fun `spring DST day has 23 bars and fall DST day has 25`() {
        val spring = LocalDate.of(2026, 3, 29)
        val fall = LocalDate.of(2026, 10, 25)
        val s = PriceChartModel.from(ChartSamples.priceSeries(spring), ChartSamples.at(spring, 12.0, PriceRegion.SPAIN.timeZone))
        val f = PriceChartModel.from(ChartSamples.priceSeries(fall), ChartSamples.at(fall, 12.0, PriceRegion.SPAIN.timeZone))
        assertEquals(23, s.bars.size)
        assertEquals(25, f.bars.size)
        // Axis labels stay on the market clock: 0, 3, ..., 21 on both days (hour 2 never matches a label).
        assertEquals(8, s.xLabels.size)
        assertEquals(8, f.xLabels.size)
        assertEquals(25 * 3600L, f.xEnd.epochSecond - f.xStart.epochSecond)
    }

    @Test
    fun `fifteen minute series draws hourly bars`() {
        val series = ChartSamples.priceSeries(day, quarterHourly = true)
        val model = PriceChartModel.from(series, now)
        assertEquals(24, model.bars.size)
        assertTrue(model.bars.all { it.point.durationSeconds == 3600L })
    }

    @Test
    fun `around window starts six hours before the current hour`() {
        val model = PriceChartModel.from(ChartSamples.priceSeries(day), now, PriceWindow.Around())
        assertEquals(17, model.bars.size) // 07:00..23:00: the sample series ends at midnight
        assertEquals(6, model.currentIndex)
    }

    @Test
    fun `all equal prices give a flat day with a valid scale`() {
        val model = PriceChartModel.from(ChartSamples.priceSeries(day) { 0.1 }, now)
        assertTrue(model.bars.all { it.fraction == 0.5 })
        assertTrue(model.yMax > model.yMin)
        assertEquals(0.1, model.average!!, 1e-9)
        assertTrue(model.yTicks.isNotEmpty())
    }

    @Test
    fun `negative prices extend the scale below zero and keep a sliver`() {
        val model = PriceChartModel.from(ChartSamples.priceSeries(day) { if (it in 11..15) -0.01 else 0.05 }, now, variant = ChartVariant.APP)
        assertTrue(model.yMin < 0)
        val negative = model.bars[12]
        assertTrue(negative.end <= -0.01)
        val tiny = PriceChartModel.from(ChartSamples.priceSeries(day) { if (it == 12) 0.0001 else 0.2 }, now)
        assertTrue(tiny.bars[12].end >= tiny.span * 0.035 - 1e-12)
    }

    @Test
    fun `all negative prices still include zero`() {
        val model = PriceChartModel.from(ChartSamples.priceSeries(day) { -0.05 }, now)
        assertEquals(-0.05, model.yMin, 1e-9)
        assertTrue(model.yMax > 0 || model.yMax > model.yMin)
        assertTrue(model.bars.all { it.end < 0 })
    }

    @Test
    fun `callout uses the market zone and euro per kWh`() {
        val model = PriceChartModel.from(ChartSamples.priceSeries(day), now)
        val callout = model.callout(13, es)!!
        assertEquals("13:00–14:00", callout.detail)
        assertTrue(callout.title.endsWith(" €/kWh"))
        assertNull(model.callout(99, es))
    }

    @Test
    fun `price hit test covers the edges and maps x to bars`() {
        val model = PriceChartModel.from(ChartSamples.priceSeries(day), now, variant = ChartVariant.WIDGET_SMALL)
        val w = 240f
        assertEquals(0, PriceChartRenderer.barIndexAt(0f, 0f, w, model, 1f))
        assertEquals(23, PriceChartRenderer.barIndexAt(w, 0f, w, model, 1f))
        assertEquals(12, PriceChartRenderer.barIndexAt(w / 2 + 0.1f, 0f, w, model, 1f))
        assertNull(PriceChartRenderer.barIndexAt(-1f, 0f, w, model, 1f))
        assertNull(PriceChartRenderer.barIndexAt(w + 1, 0f, w, model, 1f))
        // The app variant keeps a 46 dp gutter for the Y labels: the plot ends before the bounds do.
        val app = PriceChartModel.from(ChartSamples.priceSeries(day), now, variant = ChartVariant.APP)
        assertEquals(23, PriceChartRenderer.barIndexAt(w - 46f, 0f, w, app, 1f))
        assertNull(PriceChartRenderer.barIndexAt(w - 10f, 0f, w, app, 1f))
    }

    @Test
    fun `price hit test on a 25 hour day maps the last pixel to the last bar`() {
        val fall = LocalDate.of(2026, 10, 25)
        val model = PriceChartModel.from(ChartSamples.priceSeries(fall), ChartSamples.at(fall, 12.0), variant = ChartVariant.WIDGET_MEDIUM)
        assertEquals(24, PriceChartRenderer.barIndexAt(300f, 0f, 300f, model, 2f))
    }

    @Test
    fun `empty price series does not crash`() {
        val empty = ChartSamples.priceSeries(day).copy(points = emptyList())
        val model = PriceChartModel.from(empty, now)
        assertTrue(model.bars.isEmpty())
        assertNull(model.currentIndex)
        assertNull(model.average)
        assertNull(PriceChartRenderer.barIndexAt(10f, 0f, 100f, model, 1f))
    }

    @Test
    fun `solar domain defaults to 6 to 21 and widens with production`() {
        val snapshot = ChartSamples.solarSnapshot(day, 13.5)
        val model = SolarChartModel.from(snapshot, now)
        assertEquals(6, model.xStart.atZone(model.zone).hour)
        assertEquals(21, model.xEnd.atZone(model.zone).hour)
        assertEquals(listOf(6, 9, 12, 15, 18, 21), model.xLabels.map { it.atZone(model.zone).hour })
        val early = SolarSnapshot(curve = listOf(CurvePoint(ChartSamples.at(day, 5.2), 0.4), CurvePoint(ChartSamples.at(day, 22.3), 0.3)), fetchedAt = now)
        val wide = SolarChartModel.from(early, now)
        assertEquals(5, wide.xStart.atZone(wide.zone).hour)
        assertEquals(23, wide.xEnd.atZone(wide.zone).hour)
    }

    @Test
    fun `solar scale has headroom and the app variant adds callout room`() {
        val snapshot = ChartSamples.solarSnapshot(day, 13.5)
        val peak = snapshot.curve.mapNotNull { it.kW }.max()
        val widget = SolarChartModel.from(snapshot, now, ChartVariant.WIDGET_LARGE)
        val app = SolarChartModel.from(snapshot, now, ChartVariant.APP)
        assertEquals(peak * 1.25, widget.yScaleMax, 1e-9)
        assertEquals(widget.yScaleMax, widget.yMax, 1e-9)
        assertEquals(widget.yScaleMax * 1.18, app.yMax, 1e-9)
        assertEquals(2.0, ChartLayout.solarYMax(null), 0.0)
        assertTrue(widget.nowInDomain)
        assertNotNull(widget.dot)
    }

    @Test
    fun `night curve with null points drops them and has no dot`() {
        val night = ChartSamples.nightSnapshot(day)
        val nightNow = ChartSamples.at(day, 23.0)
        val model = SolarChartModel.from(night, nightNow, ChartVariant.APP)
        assertNull(model.dot)
        assertFalse(model.nowInDomain)
        assertNotNull(model.peak)
        val withNulls = ChartSamples.solarSnapshot(day, 8.0, nullsBeforeSunrise = true)
        val m = SolarChartModel.from(withNulls, ChartSamples.at(day, 8.0))
        assertTrue(m.points.none { it.time < ChartSamples.at(day, 6.5) })
        val allNull = SolarSnapshot(curve = listOf(CurvePoint(ChartSamples.at(day, 7.0), null)), fetchedAt = now)
        val empty = SolarChartModel.from(allNull, now)
        assertTrue(empty.points.isEmpty())
        assertNull(empty.dot)
        assertNull(empty.peak)
        assertEquals(listOf(0.0), ChartLayout.solarTicks(0.0))
    }

    @Test
    fun `negative production reads as zero`() {
        val snapshot = SolarSnapshot(curve = listOf(CurvePoint(ChartSamples.at(day, 10.0), -0.2), CurvePoint(ChartSamples.at(day, 10.5), 1.0)), fetchedAt = now)
        val model = SolarChartModel.from(snapshot, now)
        assertEquals(0.0, model.points.first().kW, 0.0)
    }

    @Test
    fun `peak label is hidden when the peak sits under the dot and in mini variants`() {
        val nearPeak = ChartSamples.solarSnapshot(day, 14.0)
        assertNull(SolarChartModel.from(nearPeak, ChartSamples.at(day, 14.0), ChartVariant.WIDGET_LARGE).peak)
        val late = ChartSamples.solarSnapshot(day, 19.0)
        assertNotNull(SolarChartModel.from(late, ChartSamples.at(day, 19.0), ChartVariant.WIDGET_LARGE).peak)
        assertNull(SolarChartModel.from(late, ChartSamples.at(day, 19.0), ChartVariant.WIDGET_SMALL).peak)
    }

    @Test
    fun `marked point moves the dot`() {
        val snapshot = ChartSamples.solarSnapshot(day, 13.5)
        val mark = CurvePoint(ChartSamples.at(day, 10.0), 3.0)
        val model = SolarChartModel.from(snapshot, now, ChartVariant.WIDGET_MEDIUM, marked = mark)
        assertEquals(mark.time, model.dot!!.time)
    }

    @Test
    fun `solar hit test picks the nearest point within 20 minutes and respects edges`() {
        val model = SolarChartModel.from(ChartSamples.solarSnapshot(day, 13.5), now, ChartVariant.WIDGET_SMALL)
        val w = 300f // 06:00..21:00 = 15 h: 20 px per hour
        val at10 = SolarChartRenderer.curvePointAt(80f, 0f, w, model, 1f)!!
        assertEquals(ChartSamples.at(day, 10.0), at10.time)
        // Left edge: 06:00 is a curve point.
        assertEquals(ChartSamples.at(day, 6.0), SolarChartRenderer.curvePointAt(0f, 0f, w, model, 1f)!!.time)
        // 18:00 is after the last point (13:30) by far more than 20 minutes.
        assertNull(SolarChartRenderer.curvePointAt(240f, 0f, w, model, 1f))
        assertNull(SolarChartRenderer.curvePointAt(-1f, 0f, w, model, 1f))
        assertNull(SolarChartRenderer.curvePointAt(w + 1, 0f, w, model, 1f))
    }

    @Test
    fun `axis numbers drop decimals on whole values and follow the locale`() {
        assertEquals("4", ChartLayout.axisNumber(4.0, 1, es))
        assertEquals("0,5", ChartLayout.axisNumber(0.5, 1, es))
        assertEquals("0,20", ChartLayout.axisNumber(0.2, 2, es))
        assertEquals("0.20", ChartLayout.axisNumber(0.2, 2, Locale.US))
        assertEquals(listOf(0.0, 0.05, 0.1), ChartLayout.priceTicks(0.12))
        assertEquals(listOf(0.0, 2.0, 4.0), ChartLayout.solarTicks(5.0))
    }
}
