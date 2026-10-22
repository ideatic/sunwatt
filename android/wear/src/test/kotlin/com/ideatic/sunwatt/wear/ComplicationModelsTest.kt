package com.ideatic.sunwatt.wear

import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SampleData.Scenario
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.wear.complication.PriceComplicationModel
import com.ideatic.sunwatt.wear.complication.PriceGauge
import com.ideatic.sunwatt.wear.complication.SolarComplicationModel
import com.ideatic.sunwatt.wear.complication.SolarGauge
import com.ideatic.sunwatt.wear.complication.WatchSolarState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.abs

class ComplicationModelsTest {
    private val spanish = Locale.forLanguageTag("es-ES")

    @Test
    fun solarPowerIsPositionedWithinTodaysPeak() {
        val entry = SampleData.entry(Scenario.DAY)
        val gauge = requireNotNull(SolarGauge.of(entry))
        assertTrue(gauge.upper >= 1)
        assertTrue(gauge.value >= 0 && gauge.value <= gauge.upper)
        assertTrue(abs(gauge.fraction - gauge.value / gauge.upper) < 1e-9)
        val power = entry.data.solar!!.currentPowerKW!!
        assertTrue(abs(gauge.value - minOf(power, gauge.upper)) < 1e-9)
    }

    @Test
    fun nightRingIsEmpty() {
        val gauge = requireNotNull(SolarGauge.of(SampleData.entry(Scenario.NIGHT)))
        assertEquals(0.0, gauge.value)
        assertEquals(0.0, gauge.fraction)
    }

    @Test
    fun peakFloorIsOneKilowatt() {
        val entry = SampleData.entry(Scenario.DAY)
        val solar = entry.data.solar!!
        val dull = solar.copy(curve = solar.curve.map { it.copy(kW = it.kW?.coerceAtMost(0.2)) }, currentPowerKW = 0.2)
        val gauge = requireNotNull(SolarGauge.of(SunWattEntry(entry.date, entry.data.copy(solar = dull))))
        assertEquals(1.0, gauge.upper)
        assertTrue(abs(gauge.fraction - 0.2) < 1e-9)
    }

    @Test
    fun unavailableStatesHaveNoGauge() {
        assertNull(SolarGauge.of(SampleData.entry(Scenario.ERROR)))
        assertNull(SolarGauge.of(SampleData.notConfiguredEntry()))
        assertNull(PriceGauge.of(SampleData.entry(Scenario.ERROR)))
    }

    @Test
    fun afterMidnightShowsNoDataYet() {
        val day = SampleData.entry(Scenario.DAY)
        val entry = SunWattEntry(day.date.plusSeconds(11 * 3600), day.data)
        assertEquals(WatchSolarState.NoDataYet, WatchSolarState.of(entry))
        // Yesterday's power must not move the ring, and the energy reads zero.
        assertEquals(0.0, SolarGauge.of(entry)?.value)
        assertEquals("0,0", SolarComplicationModel(entry, spanish).energyText)
    }

    @Test
    fun statesMapFromScenarios() {
        assertEquals(WatchSolarState.Data(idle = false, stale = false), WatchSolarState.of(SampleData.entry(Scenario.DAY)))
        assertEquals(WatchSolarState.Data(idle = true, stale = false), WatchSolarState.of(SampleData.entry(Scenario.NIGHT)))
        assertEquals(WatchSolarState.Data(idle = false, stale = true), WatchSolarState.of(SampleData.entry(Scenario.STALE)))
        assertEquals(WatchSolarState.Error, WatchSolarState.of(SampleData.entry(Scenario.ERROR)))
        assertEquals(WatchSolarState.NotConfigured, WatchSolarState.of(SampleData.notConfiguredEntry()))
    }

    @Test
    fun priceGaugeStaysInRangeAndMatchesFraction() {
        val gauge = requireNotNull(PriceGauge.of(SampleData.entry(Scenario.DAY)))
        assertTrue(gauge.lower <= gauge.value && gauge.value <= gauge.upper)
        assertTrue(gauge.fraction in 0.0..1.0)
    }

    @Test
    fun priceTextComesFromTheCurrentHour() {
        // 13:40 on the sample day: PVPC of hour 13 is 17.81 EUR/MWh.
        val model = PriceComplicationModel(SampleData.entry(Scenario.DAY), spanish)
        assertEquals("0,018", model.valueText)
        assertEquals("0.018", PriceComplicationModel(SampleData.entry(Scenario.DAY), Locale.US).valueText)
        assertEquals(com.ideatic.sunwatt.core.models.PriceLevel.LOW, model.level)
        assertNull(PriceComplicationModel(SampleData.entry(Scenario.ERROR), spanish).valueText)
    }

    @Test
    fun solarTextsComeFromTheSnapshot() {
        val model = SolarComplicationModel(SampleData.entry(Scenario.DAY), spanish)
        assertEquals("16,2", model.energyText)
        assertEquals("5,3", model.powerText)
        assertEquals("13:30", model.timeText)
        assertEquals(false, model.muted)
        assertEquals(true, SolarComplicationModel(SampleData.entry(Scenario.NIGHT), spanish).isNight)
        assertEquals(true, SolarComplicationModel(SampleData.entry(Scenario.STALE), spanish).muted)
    }
}
