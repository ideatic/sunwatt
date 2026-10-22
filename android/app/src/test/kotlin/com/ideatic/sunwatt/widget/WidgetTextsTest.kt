package com.ideatic.sunwatt.widget

import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.shortcuts.CatalogStrings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class WidgetTextsTest {
    private val en = CatalogStrings(Locale.US)

    @Test
    fun `every state has a title and a hint`() {
        for (kind in StateKind.entries) {
            assertNotEquals(0, kind.title().id)
            assertNotEquals(0, kind.hint().id)
        }
    }

    @Test
    fun `state messages read as on Apple`() {
        assertEquals("Solar not set up", en.text(StateKind.NOT_CONFIGURED.title().id))
        assertEquals("Set up solar in SunWatt", en.text(StateKind.NOT_CONFIGURED.hint().id))
        assertEquals("Kiosk link expired", en.text(StateKind.LINK_EXPIRED.title().id))
        assertEquals("Open SunWatt to replace it", en.text(StateKind.LINK_EXPIRED.hint().id))
        assertEquals("Prices unavailable", en.text(StateKind.PRICE_ERROR.title().id))
        assertEquals("Open SunWatt to check the connection", en.text(StateKind.UNAVAILABLE.hint().id))
    }

    @Test
    fun `level names are the widget ones`() {
        assertEquals(listOf("Cheap", "Average", "Expensive"), PriceLevel.entries.map { en.text(it.widgetLabel().id) })
    }

    @Test
    fun `source labels`() {
        assertEquals("PVPC", en.text(PriceRegion.SPAIN.sourceLabel().id))
        assertEquals("Day-ahead", en.text(PriceRegion.FRANCE.sourceLabel().id))
    }

    @Test
    fun `home caption texts`() {
        val kw: (Double) -> String = { Locale.US.let { _ -> "%.1f".format(Locale.US, it) } }
        assertEquals("Home 1.2 kW", en.text(HomeCaption(1.2, HomeCaption.Grid.NONE, null).text(kw).let { it.id }, *HomeCaption(1.2, HomeCaption.Grid.NONE, null).text(kw).args.toTypedArray()))
        val selling = HomeCaption(1.2, HomeCaption.Grid.SELLING, 0.8).text(kw)
        assertEquals("Home 1.2 kW · selling 0.8 kW", en.text(selling.id, *selling.args.toTypedArray()))
    }
}
