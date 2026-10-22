package com.ideatic.sunwatt.core.loader

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.data.TEST_KIOSK_URL
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.inverter.FakeSolar
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several solar plants through the loader: each plant on its own cache, limits and failures. */
class MultiPlantLoaderTest {
    private val now = madrid(2026, 6, 1, 12, 30)
    private val roof = SolarPlant(
        id = "B8E3C1F2-0000-4000-8000-000000000001", name = "Roof", provider = SolarProvider.SOLAR_EDGE,
        siteID = "1234567", apiKey = "ABCDEFGH12345678",
    )

    private fun twoPlants() = SunWattSettings(
        kioskURL = TEST_KIOSK_URL, showTomorrowPrices = false, primaryPlantName = "Home", additionalPlants = listOf(roof),
    )

    private fun snapshot(station: String, kW: Double, kWh: Double) =
        storeSampleSolar(now).copy(stationName = station, currentPowerKW = kW, dailyEnergyKWh = kWh)

    /** A solar source per plant, told apart by provider (the loader puts the plant in the place of the first one). */
    private fun loader(store: SunWattStore, home: Result<SolarSnapshot>, roof: Result<SolarSnapshot>, at: Instant = now) = SunWattLoader(
        store,
        makeSolar = { settings ->
            if (SolarAccount.fromSettings(settings) == null) null
            else FakeSolar(if (settings.solarProvider == SolarProvider.SOLAR_EDGE) roof else home)
        },
        prices = FakePlain(Result.success(storeSamplePrices(fetchedAt = at))), now = { at },
    )

    @Test
    fun eachPlantIsLoadedAndCachedOnItsOwn() = runTest {
        val store = tempStore().apply { saveSettings(twoPlants()) }
        val data = loader(store, Result.success(snapshot("Calle", 3.0, 10.0)), Result.success(snapshot("Roof site", 1.5, 4.0))).load()
        assertEquals(SolarPlant.PRIMARY_ID, data.plantID)
        assertEquals("Home", data.plantName)
        assertEquals(3.0, data.solar?.currentPowerKW)
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(listOf("Roof"), data.otherPlants.map { it.name })
        assertEquals(1.5, data.otherPlants.first().solar?.currentPowerKW)
        val total = SunWattEntry(now, data).combinedSolarForDisplay
        assertEquals(4.5, total?.currentPowerKW)
        assertEquals(14.0, total?.dailyEnergyKWh)
        // Separate caches: the first plant keeps its old file.
        assertEquals(3.0, store.loadSolar()?.currentPowerKW)
        assertEquals(1.5, store.loadSolar(roof.id)?.currentPowerKW)
        // Cached data (placeholders) has both too.
        val cached = loader(store, Result.failure(SunWattError.Unavailable), Result.failure(SunWattError.Unavailable)).cached()
        assertEquals(1.5, cached.otherPlants.first().solar?.currentPowerKW)
    }

    @Test
    fun aFailingPlantDoesNotAffectTheOther() = runTest {
        val store = tempStore().apply { saveSettings(twoPlants()) }
        val data = loader(store, Result.success(snapshot("Calle", 3.0, 10.0)), Result.failure(SunWattError.SolarCredentialsRejected)).load()
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SourceStatus.Error.of(SunWattError.SolarCredentialsRejected), data.otherPlants.first().status)
        // Without a user name the station name, else the provider, is shown.
        assertEquals("Roof", data.otherPlants.first().name)
        store.saveSettings(twoPlants().copy(primaryPlantName = ""))
        val unnamed = loader(store, Result.success(snapshot("Calle", 3.0, 10.0)), Result.failure(SunWattError.Unavailable)).load()
        assertEquals("Calle", unnamed.plantName)
    }

    @Test
    fun onePlantReadsAsBefore() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = false, primaryPlantName = "Home")) }
        val data = loader(store, Result.success(snapshot("Calle", 3.0, 10.0)), Result.failure(SunWattError.Unavailable)).load()
        assertNull(data.plantName)
        assertTrue(data.otherPlants.isEmpty() && !data.hasSeveralPlants)
        assertEquals(data, data.showingPlant(1))
    }

    @Test
    fun sharedWindowNeedsEveryPlant() = runTest {
        val store = tempStore().apply { saveSettings(twoPlants()) }
        loader(store, Result.success(snapshot("Calle", 3.0, 10.0)), Result.success(snapshot("Roof site", 1.5, 4.0))).load()
        // Within the window both plants come from the store, without fetching.
        val cachedOnly = loader(store, Result.failure(SunWattError.Unavailable), Result.failure(SunWattError.Unavailable), now.plusSeconds(30))
        val data = cachedOnly.load(maxCacheAge = SunWattLoader.SHARED_CACHE_WINDOW)
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SourceStatus.Fresh, data.otherPlants.first().status)
    }

    @Test
    fun aPlantWithoutCacheBreaksTheWindowForAllPlants() = runTest {
        val store = tempStore().apply { saveSettings(twoPlants()) }
        loader(store, Result.success(snapshot("Calle", 3.0, 10.0)), Result.success(snapshot("Roof site", 1.5, 4.0))).load()
        store.deleteSolar(roof.id)
        val counting = CallCountingSolar(Result.success(snapshot("Roof site", 2.0, 5.0)))
        val data = SunWattLoader(
            store, makeSolar = { counting }, prices = FakePlain(Result.success(storeSamplePrices(fetchedAt = now))), now = { now.plusSeconds(30) },
        ).load(maxCacheAge = SunWattLoader.SHARED_CACHE_WINDOW)
        assertEquals(2, counting.count)
        assertEquals(SourceStatus.Fresh, data.otherPlants.first().status)
    }
}
