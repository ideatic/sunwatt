package com.ideatic.sunwatt.shortcuts

import com.ideatic.sunwatt.core.data.PlantSolarData
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.intents.CheapestHoursAnswer
import com.ideatic.sunwatt.core.intents.PriceAnswer
import com.ideatic.sunwatt.core.intents.SolarAnswer
import com.ideatic.sunwatt.core.models.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class AnswerTextsTest {
    private val en = AnswerTexts(CatalogStrings(Locale.US))
    private val es = AnswerTexts(CatalogStrings(Locale.forLanguageTag("es-ES")))
    private val day = SampleData.entry(SampleData.Scenario.DAY)

    @Test
    fun `price answer says price and level`() {
        val answer = PriceAnswer.make(day.data, day.date)
        // 13:40: 0.018 EUR/kWh, the cheapest part of the day.
        assertEquals("The electricity price now is 0.018 €/kWh. It is cheap.", en.price(answer))
        assertTrue(es.price(answer).contains("0,018"))
    }

    @Test
    fun `price answer without prices explains`() {
        val none = PriceAnswer.make(SampleData.entry(SampleData.Scenario.ERROR).data, SampleData.now)
        assertEquals("I cannot get the electricity price right now. Check your connection and try again.", en.price(none))
    }

    @Test
    fun `solar answer while producing`() {
        val text = en.solar(SolarAnswer.make(day.data, day.date))
        assertTrue(text.startsWith("Solar production now is 5."), text)
        assertTrue(text.contains(" kW. Today: "), text)
        assertTrue(text.endsWith(" kWh."), text)
    }

    @Test
    fun `solar answer at night`() {
        val night = SampleData.entry(SampleData.Scenario.NIGHT)
        val text = en.solar(SolarAnswer.make(night.data, night.date))
        assertTrue(text.startsWith("The plant is not producing now. Today: "), text)
    }

    @Test
    fun `solar answer not set up and unavailable`() {
        val notSet = SolarAnswer.make(SampleData.notConfiguredEntry().data, SampleData.now)
        assertEquals("Solar production is not set up. Connect your solar system in the SunWatt settings.", en.solar(notSet))
        val error = SolarAnswer.make(SampleData.entry(SampleData.Scenario.ERROR).data, SampleData.now)
        assertEquals("I cannot get the solar production right now. Check your connection and try again.", en.solar(error))
        val expired = SolarAnswer.make(day.data.copy(solarLinkExpired = true), day.date)
        assertEquals("Your FusionSolar kiosk link has expired. Create a new one and paste it in the SunWatt settings.", en.solar(expired))
    }

    @Test
    fun `stale reading adds its time`() {
        val stale = SampleData.entry(SampleData.Scenario.STALE)
        val text = en.solar(SolarAnswer.make(stale.data, stale.date))
        assertTrue(text.endsWith("The last reading is from 11:35 AM."), text)
    }

    @Test
    fun `several plants give the total and each plant`() {
        val base = day.data
        val plantA = PlantSolarData("a", "Roof", base.solar, SourceStatus.Fresh)
        val plantB = PlantSolarData("b", "Garage", null, SourceStatus.Error("x"))
        val data = base.withPlants(listOf(plantA, plantB))
        val text = en.solar(SolarAnswer.make(data, day.date))
        assertTrue(text.startsWith("Your plants are producing 5."), text)
        assertTrue(text.contains("By plant: Roof: 5."), text)
        assertTrue(text.contains("Garage: no data"), text)
    }

    @Test
    fun `cheapest hours name the hours and tomorrow`() {
        val answer = CheapestHoursAnswer.make(day.data, day.date)
        val text = en.cheapestHours(answer, Constants.madrid)
        assertTrue(text.startsWith("The cheapest hours coming up are "), text)
        assertTrue(text.endsWith(" €/kWh."), text)
        val none = CheapestHoursAnswer.make(SunWattData(solarStatus = SourceStatus.Fresh, priceStatus = SourceStatus.Fresh), day.date)
        assertEquals("I cannot find upcoming electricity prices right now. Check your connection and try again.", en.cheapestHours(none, Constants.madrid))
    }

    @Test
    fun `every language has every answer text`() {
        for (tag in listOf("en", "es", "pt", "fr", "de", "it", "nl")) {
            val texts = AnswerTexts(CatalogStrings(Locale.forLanguageTag(tag)))
            val answer = PriceAnswer.make(day.data, day.date)
            assertTrue(texts.price(answer).isNotBlank(), tag)
            assertTrue(texts.solar(SolarAnswer.make(day.data, day.date)).isNotBlank(), tag)
            assertTrue(texts.cheapestHours(CheapestHoursAnswer.make(day.data, day.date), Constants.madrid).isNotBlank(), tag)
        }
    }
}
