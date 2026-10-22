package com.ideatic.sunwatt.widget

import com.ideatic.sunwatt.core.data.PlantSolarData
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SampleData.Scenario
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PriceWindow
import com.ideatic.sunwatt.core.models.WidgetContent
import com.ideatic.sunwatt.core.timeline.TimelineBuilder
import com.ideatic.sunwatt.core.timeline.WidgetPlantChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class WidgetModelsTest {
    private val day = SampleData.entry(Scenario.DAY)

    private fun solar(scenario: Scenario) = SolarModel(SampleData.entry(scenario))

    @Test
    fun `day entry is producing and fresh`() {
        val model = solar(Scenario.DAY)
        assertEquals(SolarModel.Phase.ACTIVE, model.phase)
        assertTrue(model.isAvailable)
        assertFalse(model.isIdle)
        assertNull(model.staleSince)
        assertEquals(model.snapshot?.dataAsOf, model.dataTime)
    }

    @Test
    fun `night entry is idle`() {
        val model = solar(Scenario.NIGHT)
        assertEquals(SolarModel.Phase.IDLE, model.phase)
        assertTrue(model.isAvailable)
        assertTrue(model.isIdle)
    }

    @Test
    fun `stale entry carries the time of its data`() {
        val model = solar(Scenario.STALE)
        assertEquals(SolarModel.Phase.ACTIVE, model.phase)
        assertEquals(model.snapshot?.dataAsOf, model.staleSince)
    }

    @Test
    fun `old data becomes stale with time alone`() {
        val later = day.data
        val entry = com.ideatic.sunwatt.core.data.SunWattEntry(day.date.plus(Duration.ofMinutes(50)), later)
        assertNotNull(SolarModel(entry).staleSince)
        assertNotNull(PriceModel(entry, PriceWindow.WHOLE_DAY).current)
    }

    @Test
    fun `error entry has no solar and a state message`() {
        val model = solar(Scenario.ERROR)
        assertEquals(SolarModel.Phase.ERROR, model.phase)
        assertFalse(model.isAvailable)
        assertEquals(StateKind.SOLAR_ERROR, model.stateKind)
    }

    @Test
    fun `not configured and expired link pick their message`() {
        val notSet = SolarModel(SampleData.notConfiguredEntry())
        assertEquals(SolarModel.Phase.NOT_CONFIGURED, notSet.phase)
        assertEquals(StateKind.NOT_CONFIGURED, notSet.stateKind)
        val expired = SolarModel(com.ideatic.sunwatt.core.data.SunWattEntry(day.date, day.data.copy(solarStatus = SourceStatus.Error("x"), solar = null, solarLinkExpired = true)))
        assertEquals(StateKind.LINK_EXPIRED, expired.stateKind)
    }

    @Test
    fun `a day-old snapshot shows no production figures`() {
        val tomorrow = com.ideatic.sunwatt.core.data.SunWattEntry(day.date.plus(Duration.ofHours(14)), day.data)
        val model = SolarModel(tomorrow)
        assertNull(model.powerKW)
        assertNotNull(model.staleSince)
    }

    @Test
    fun `window picks the chart points`() {
        val whole = PriceModel(day, PriceWindow.WHOLE_DAY)
        assertEquals(24, whole.points.size)
        val around = PriceModel(day, PriceWindow.AROUND)
        // 6 h before 13:00 to the end of the sample series (23:00): there is no tomorrow in it.
        assertEquals(17, around.points.size)
        assertEquals(whole.current, around.current)
        assertEquals(PriceLevel.LOW, whole.level)
        assertNotNull(whole.stats)
        assertNotNull(whole.fraction)
    }

    @Test
    fun `price error status hides leftover prices`() {
        val data = day.data.copy(priceStatus = SourceStatus.Error("x"))
        val model = PriceModel(com.ideatic.sunwatt.core.data.SunWattEntry(day.date, data), PriceWindow.WHOLE_DAY)
        assertNull(model.series)
        assertTrue(model.points.isEmpty())
    }

    @Test
    fun `full state follows the chosen content`() {
        fun kind(scenario: Scenario, content: WidgetContent): StateKind? {
            val entry = SampleData.entry(scenario)
            return WidgetState.fullStateKind(SolarModel(entry), PriceModel(entry, PriceWindow.WHOLE_DAY), content)
        }
        assertNull(kind(Scenario.DAY, WidgetContent.BOTH))
        assertEquals(StateKind.UNAVAILABLE, kind(Scenario.ERROR, WidgetContent.BOTH))
        assertEquals(StateKind.SOLAR_ERROR, kind(Scenario.ERROR, WidgetContent.SOLAR_ONLY))
        assertEquals(StateKind.PRICE_ERROR, kind(Scenario.ERROR, WidgetContent.PRICE_ONLY))
        val notSet = SampleData.notConfiguredEntry()
        val solar = SolarModel(notSet)
        val price = PriceModel(notSet, PriceWindow.WHOLE_DAY)
        // Not set up and prices fine: the widget still shows prices; solar-only says it is not set up.
        assertNull(WidgetState.fullStateKind(solar, price, WidgetContent.BOTH))
        assertEquals(StateKind.NOT_CONFIGURED, WidgetState.fullStateKind(solar, price, WidgetContent.SOLAR_ONLY))
        // Not set up and prices down: the price message, not a solar one.
        val noPrices = com.ideatic.sunwatt.core.data.SunWattEntry(notSet.date, notSet.data.copy(prices = null, priceStatus = SourceStatus.Error("x")))
        assertEquals(
            StateKind.PRICE_ERROR,
            WidgetState.fullStateKind(SolarModel(noPrices), PriceModel(noPrices, PriceWindow.WHOLE_DAY), WidgetContent.BOTH),
        )
    }

    @Test
    fun `small widget falls back to the half that works`() {
        val notSet = SampleData.notConfiguredEntry()
        val solar = SolarModel(notSet)
        val price = PriceModel(notSet, PriceWindow.WHOLE_DAY)
        assertEquals(WidgetContent.PRICE_ONLY, WidgetState.smallContent(solar, price, WidgetContent.BOTH))
        assertEquals(WidgetContent.SOLAR_ONLY, WidgetState.smallContent(solar, price, WidgetContent.SOLAR_ONLY))
        assertEquals(WidgetContent.BOTH, WidgetState.smallContent(SolarModel(day), PriceModel(day, PriceWindow.WHOLE_DAY), WidgetContent.BOTH))
    }

    @Test
    fun `home caption reads the grid direction`() {
        val home = com.ideatic.sunwatt.core.models.HomeEnergy(consumptionKW = 1.2, gridKW = -0.8)
        val data = day.data.copy(solar = day.data.solar!!.copy(home = home))
        val model = SolarModel(com.ideatic.sunwatt.core.data.SunWattEntry(day.date, data))
        assertEquals(HomeCaption(1.2, HomeCaption.Grid.SELLING, 0.8), model.home)
        val buying = SolarModel(com.ideatic.sunwatt.core.data.SunWattEntry(day.date, data.copy(solar = data.solar!!.copy(home = home.copy(gridKW = 0.3)))))
        assertEquals(HomeCaption.Grid.BUYING, buying.home?.grid)
        val flat = SolarModel(com.ideatic.sunwatt.core.data.SunWattEntry(day.date, data.copy(solar = data.solar!!.copy(home = home.copy(gridKW = 0.01)))))
        assertEquals(HomeCaption.Grid.NONE, flat.home?.grid)
        assertNull(SolarModel(day).home)
    }

    @Test
    fun `rotating widgets show the plant whose turn it is and fixed ones their plant`() {
        val base = day.data
        val data = base.withPlants(
            listOf(
                PlantSolarData("a", "Roof", base.solar, SourceStatus.Fresh),
                PlantSolarData("b", "Garage", base.solar, SourceStatus.Fresh),
            ),
        )
        val now = day.date
        val turn = TimelineBuilder.turn(now, 2)
        assertEquals(listOf("a", "b")[turn], widgetEntry(data, now, WidgetPlantChoice.Rotate).data.plantID)
        assertEquals("b", widgetEntry(data, now, WidgetPlantChoice.Plant("b")).data.plantID)
        // A plant that no longer exists falls back to the first.
        assertEquals("a", widgetEntry(data, now, WidgetPlantChoice.Plant("gone")).data.plantID)
        assertEquals("Garage", SolarModel(widgetEntry(data, now, WidgetPlantChoice.Plant("b"))).plantName)
    }

    @Test
    fun `one plant shows no plant name whatever the choice`() {
        assertNull(SolarModel(widgetEntry(day.data, day.date, WidgetPlantChoice.Rotate)).plantName)
        assertNull(SolarModel(widgetEntry(day.data, day.date, WidgetPlantChoice.Plant("x"))).plantName)
    }

    @Test
    fun `config raw values are the Apple ones`() {
        assertEquals(WidgetPlantChoice.Rotate, WidgetConfig().plant)
        assertEquals(WidgetPlantChoice.Plant("p1"), WidgetConfig(plantRaw = "p1").plant)
        assertEquals("solarOnly", WidgetContent.SOLAR_ONLY.rawValue)
        assertEquals("around", PriceWindow.AROUND.rawValue)
    }
}
