package com.ideatic.sunwatt.tile

import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.intents.ControlContent
import com.ideatic.sunwatt.shortcuts.CatalogStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class TileTextsTest {
    private val en = TileTexts(CatalogStrings(Locale.US))
    private val es = TileTexts(CatalogStrings(Locale.forLanguageTag("es-ES")))
    private val day = SampleData.entry(SampleData.Scenario.DAY)

    @Test
    fun `price tile shows the value and the level`() {
        val content = ControlContent.price(day.data, day.date)
        assertEquals(TileLines("0.018 €/kWh", "Cheap", TileIcon.BOLT), en.price(content))
        assertEquals(TileLines("0,018 €/kWh", "Barata", TileIcon.BOLT), es.price(content))
    }

    @Test
    fun `price tile without data asks to refresh`() {
        val content = ControlContent.price(SampleData.entry(SampleData.Scenario.ERROR).data, SampleData.now)
        assertEquals(TileLines("No data", "Tap to refresh", TileIcon.BOLT), en.price(content))
    }

    @Test
    fun `solar tile while producing`() {
        val lines = en.solar(ControlContent.solar(day.data, day.date))
        assertEquals(TileIcon.SUN, lines.icon)
        assertEquals("5.3 kW", lines.title)
        assertTrue(lines.subtitle.startsWith("Today ") && lines.subtitle.endsWith(" kWh"), lines.subtitle)
    }

    @Test
    fun `solar tile at night shows a moon`() {
        val night = SampleData.entry(SampleData.Scenario.NIGHT)
        val lines = en.solar(ControlContent.solar(night.data, night.date))
        assertEquals("No production", lines.title)
        assertEquals(TileIcon.MOON, lines.icon)
    }

    @Test
    fun `solar tile not set up and no data`() {
        assertEquals(
            TileLines("Not set up", "SunWatt", TileIcon.SUN),
            en.solar(ControlContent.solar(SampleData.notConfiguredEntry().data, SampleData.now)),
        )
        assertEquals(
            TileLines("No data", "Tap to refresh", TileIcon.SUN),
            en.solar(ControlContent.solar(SampleData.entry(SampleData.Scenario.ERROR).data, SampleData.now)),
        )
    }
}
