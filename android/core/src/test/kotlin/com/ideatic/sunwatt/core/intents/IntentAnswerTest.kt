package com.ideatic.sunwatt.core.intents

import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SampleData.Scenario
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.loader.FakePlain
import com.ideatic.sunwatt.core.loader.configure
import com.ideatic.sunwatt.core.loader.makeLoader
import com.ideatic.sunwatt.core.loader.storeSamplePrices
import com.ideatic.sunwatt.core.loader.tempStore
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val now = SampleData.now
private val dayData = SampleData.entry(Scenario.DAY).data

class PriceAnswerTest {
    @Test
    fun cheapHourAtMidday() {
        val answer = PriceAnswer.make(dayData, now)
        assertEquals(PriceLevel.LOW, answer.level)
        assertTrue(abs(answer.eurPerKWh!! - 0.01781) < 1e-9)
        assertTrue(answer.isAvailable)
    }

    @Test
    fun expensiveHour() {
        val evening = SampleData.nightNow.minusSeconds(2 * 3600) // 20:10, 198.48 EUR/MWh
        assertEquals(PriceLevel.HIGH, PriceAnswer.make(dayData, evening).level)
    }

    @Test
    fun noPricesGivesNoAnswer() {
        val answer = PriceAnswer.make(SampleData.entry(Scenario.ERROR).data, now)
        assertNull(answer.eurPerKWh)
        assertNull(answer.level)
        assertFalse(answer.isAvailable)
    }

    @Test
    fun seriesWithoutCurrentHourIsUnavailable() {
        assertNull(PriceAnswer.make(dayData, now.plusSeconds(3 * 86_400)).eurPerKWh)
    }

    @Test
    fun staleCachedPriceStillAnswers() {
        val data = dayData.copy(priceStatus = SourceStatus.Stale(now.minusSeconds(3600)))
        assertNotNull(PriceAnswer.make(data, now).eurPerKWh)
    }
}

class SolarAnswerTest {
    @Test
    fun producing() {
        val answer = SolarAnswer.make(dayData, now)
        assertEquals(SolarAnswer.Outcome.PRODUCING, answer.outcome)
        assertEquals(dayData.solar?.currentPowerKW, answer.currentKW)
        assertEquals(dayData.solar?.dailyEnergyKWh, answer.todayKWh)
        assertFalse(answer.isTotal)
        assertTrue(answer.plants.isEmpty())
        assertNull(answer.lastReading)
    }

    @Test
    fun idleAtNight() {
        val answer = SolarAnswer.make(SampleData.entry(Scenario.NIGHT).data, SampleData.nightNow)
        assertEquals(SolarAnswer.Outcome.IDLE, answer.outcome)
        assertNotNull(answer.todayKWh)
    }

    @Test
    fun notConfigured() {
        val answer = SolarAnswer.make(SampleData.notConfiguredEntry().data, now)
        assertEquals(SolarAnswer.Outcome.NOT_CONFIGURED, answer.outcome)
        assertNull(answer.currentKW)
    }

    @Test
    fun errorWithoutData() {
        val answer = SolarAnswer.make(SampleData.entry(Scenario.ERROR).data, now)
        assertEquals(SolarAnswer.Outcome.UNAVAILABLE, answer.outcome)
        assertFalse(answer.linkExpired)
    }

    @Test
    fun oldReadingCarriesItsTime() {
        val data = SampleData.entry(Scenario.STALE).data
        val answer = SolarAnswer.make(data, now)
        assertEquals(data.solar?.dataAsOf, answer.lastReading)
    }

    @Test
    fun freshReadingHasNoStaleNote() {
        assertNull(SolarAnswer.make(dayData, now).lastReading)
    }

    @Test
    fun anExpiredKioskLinkIsUnavailableAndSaysWhy() {
        val data = SunWattData(
            solar = null, prices = null, solarStatus = SourceStatus.Error("x"), priceStatus = SourceStatus.Fresh, solarLinkExpired = true,
        )
        val answer = SolarAnswer.make(data, now)
        assertEquals(SolarAnswer.Outcome.UNAVAILABLE, answer.outcome)
        assertTrue(answer.linkExpired)
    }

    @Test
    fun severalPlantsGiveTheTotalThenEachPlant() {
        val home = dayData.solar!!.copy(stationName = "Home", currentPowerKW = 3.0, dailyEnergyKWh = 10.0)
        val roof = home.copy(stationName = "Roof", currentPowerKW = 1.5, dailyEnergyKWh = 4.0)
        val data = dayData.withPlants(
            listOf(
                com.ideatic.sunwatt.core.data.PlantSolarData(SolarPlant.PRIMARY_ID, "Home", home, SourceStatus.Fresh),
                com.ideatic.sunwatt.core.data.PlantSolarData("roof", "Roof", roof, SourceStatus.Fresh),
                com.ideatic.sunwatt.core.data.PlantSolarData("barn", "Barn", null, SourceStatus.Error("x")),
            ),
        )
        val answer = SolarAnswer.make(data, now)
        assertTrue(answer.isTotal)
        assertEquals(SolarAnswer.Outcome.PRODUCING, answer.outcome)
        assertEquals(4.5, answer.currentKW)
        assertEquals(14.0, answer.todayKWh)
        assertEquals(
            listOf(
                SolarAnswer.PlantLine("Home", SolarAnswer.PlantLine.State.PRODUCING, 3.0),
                SolarAnswer.PlantLine("Roof", SolarAnswer.PlantLine.State.PRODUCING, 1.5),
                SolarAnswer.PlantLine("Barn", SolarAnswer.PlantLine.State.NO_DATA, null),
            ),
            answer.plants,
        )
        val control = ControlContent.solar(data, now)
        assertEquals(ControlContent.Solar.State.PRODUCING, control.state)
        assertEquals(4.5, control.powerKW)
    }

    @Test
    fun severalPlantsNotConfigured() {
        val data = SampleData.notConfiguredEntry().data.withPlants(
            listOf(
                com.ideatic.sunwatt.core.data.PlantSolarData(SolarPlant.PRIMARY_ID, "A", null, SourceStatus.NotConfigured),
                com.ideatic.sunwatt.core.data.PlantSolarData("b", "B", null, SourceStatus.NotConfigured),
            ),
        )
        assertEquals(SolarAnswer.Outcome.NOT_CONFIGURED, SolarAnswer.make(data, now).outcome)
    }
}

class CheapestHoursAnswerTest {
    @Test
    fun listsNextThreeHours() {
        val answer = CheapestHoursAnswer.make(dayData, now)
        assertEquals(3, answer.hours.size)
        assertEquals(listOf(13, 14, 15), answer.hours.map { it.point.start.atZone(java.time.ZoneId.of("Europe/Madrid")).hour })
        assertTrue(abs(answer.lowestEurPerKWh!! - 0.01781) < 1e-9)
        assertTrue(answer.hours.all { it.isToday })
    }

    @Test
    fun lastHourOfDayIsASingleHour() {
        val answer = CheapestHoursAnswer.make(dayData, SampleData.date(23, 30))
        assertEquals(1, answer.hours.size)
    }

    @Test
    fun noPricesGivesAnEmptyAnswer() {
        val answer = CheapestHoursAnswer.make(SampleData.entry(Scenario.ERROR).data, now)
        assertTrue(answer.hours.isEmpty())
        assertNull(answer.lowestEurPerKWh)
    }

    // Siri says which hours are tomorrow.
    @Test
    fun marksTomorrowsHours() {
        val day = madrid(2026, 6, 1)
        val points = (0 until 48).map {
            com.ideatic.sunwatt.core.models.PricePoint(day.plusSeconds(it * 3600L), 3600, if (it < 24) 100.0 + it else 10.0 + it)
        }
        val series = com.ideatic.sunwatt.core.models.PriceSeries(
            points, com.ideatic.sunwatt.core.models.PriceGranularity.HOURLY, fetchedAt = day, sourceUpdatedAt = day,
        )
        val data = SunWattData(null, series, SourceStatus.NotConfigured, SourceStatus.Fresh)
        val answer = CheapestHoursAnswer.make(data, madrid(2026, 6, 1, 20))
        assertTrue(answer.hours.any { !it.isToday })
    }
}

class ControlContentTest {
    @Test
    fun priceControl() {
        val price = ControlContent.price(dayData, now)
        assertEquals(PriceLevel.LOW, price.level)
        assertTrue(abs(price.eurPerKWh!! - 0.01781) < 1e-9)
        val none = ControlContent.price(SampleData.entry(Scenario.ERROR).data, now)
        assertNull(none.eurPerKWh)
        assertNull(none.level)
    }

    @Test
    fun solarControl() {
        val producing = ControlContent.solar(dayData, now)
        assertEquals(ControlContent.Solar.State.PRODUCING, producing.state)
        assertNotNull(producing.powerKW)
        assertNotNull(producing.todayKWh)
        assertFalse(producing.isIdle)
        assertEquals(ControlContent.Solar.State.NOT_SET_UP, ControlContent.solar(SampleData.notConfiguredEntry().data, now).state)
        val night = ControlContent.solar(SampleData.entry(Scenario.NIGHT).data, SampleData.nightNow)
        assertEquals(ControlContent.Solar.State.NO_PRODUCTION, night.state)
        assertTrue(night.isIdle, "the control shows a moon: in the small Control Center size the icon is all there is")
        assertEquals(ControlContent.Solar.State.NO_DATA, ControlContent.solar(SampleData.entry(Scenario.ERROR).data, now).state)
    }
}

class IntentDataSourceTest {
    @Test
    fun priceCacheNeedsCurrentHour() {
        assertTrue(IntentDataSource.cacheAnswers(dayData, IntentDataSource.Need.PRICE, now))
        assertFalse(IntentDataSource.cacheAnswers(dayData, IntentDataSource.Need.PRICE, now.plusSeconds(3 * 86_400)))
        assertFalse(IntentDataSource.cacheAnswers(SampleData.entry(Scenario.ERROR).data, IntentDataSource.Need.PRICE, now))
    }

    @Test
    fun solarCacheNeedsRecentReadingOrNoSetup() {
        assertTrue(IntentDataSource.cacheAnswers(dayData, IntentDataSource.Need.SOLAR, now))
        assertFalse(IntentDataSource.cacheAnswers(SampleData.entry(Scenario.STALE).data, IntentDataSource.Need.SOLAR, now))
        assertTrue(IntentDataSource.cacheAnswers(SampleData.notConfiguredEntry().data, IntentDataSource.Need.SOLAR, now))
        assertFalse(IntentDataSource.cacheAnswers(SampleData.entry(Scenario.ERROR).data, IntentDataSource.Need.SOLAR, now))
    }

    @Test
    fun emptyCacheLoadsFromTheNetwork() = runTest {
        val store = tempStore().apply { configure() }
        val prices = storeSamplePrices()
        val loader = makeLoader(store, null, FakePlain(Result.success(prices)))
        val data = IntentDataSource(loader, deadline = 2.seconds).data(IntentDataSource.Need.PRICE)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(prices, store.loadPrices())
    }
}
