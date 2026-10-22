package com.ideatic.sunwatt.core.loader

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.TEST_KIOSK_URL
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.intents.IntentDataSource
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.inverter.FakeSolar
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Counts fetches; answers with data stamped at [loaderNow]. */
class CountingSources {
    val solar = AtomicInteger()
    val prices = AtomicInteger()
    val solarCalls: Int get() = solar.get()
    val priceCalls: Int get() = prices.get()
}

private class CountingSolar(val counter: CountingSources, val fetchedAt: Instant = loaderNow) : SolarDataSource {
    override suspend fun fetch(): SolarSnapshot {
        counter.solar.incrementAndGet()
        return storeSampleSolar(fetchedAt, endpointID = null)
    }
}

private class CountingPrices(val counter: CountingSources, val region: PriceRegion, val fetchedAt: Instant = loaderNow) : PriceDataSource {
    override suspend fun fetch(day: Instant): PriceSeries {
        counter.prices.incrementAndGet()
        return storeSamplePrices(fetchedAt = fetchedAt).copy(region = region)
    }

    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries = fetch(Instant.now())
}

private fun loader(store: SunWattStore, counter: CountingSources, solar: Boolean = true, now: Instant = loaderNow) = SunWattLoader(
    store, makeSolar = { if (solar) CountingSolar(counter) else null }, makeLiveSolar = { null },
    makePrices = { region -> CountingPrices(counter, region) }, now = { now },
)

private val window = SunWattLoader.SHARED_CACHE_WINDOW

class CacheWindowTest {
    /** A store with solar and prices fetched [age] before [loaderNow]. */
    private fun seeded(age: Duration, endpointID: String? = testKioskID, region: PriceRegion = PriceRegion.SPAIN): SunWattStore {
        val store = tempStore()
        store.saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = true, priceRegion = region))
        val fetched = loaderNow.minusMillis(age.inWholeMilliseconds)
        store.saveSolar(storeSampleSolar(fetched, endpointID))
        store.savePrices(storeSamplePrices(fetchedAt = fetched).copy(region = region))
        return store
    }

    @Test
    fun recentCacheAnswersWithoutNetwork() = runTest {
        val counter = CountingSources()
        val store = seeded(60.seconds)
        val data = loader(store, counter).load(maxCacheAge = window)
        assertEquals(0, counter.solarCalls)
        assertEquals(0, counter.priceCalls)
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(store.loadSolar(), data.solar)
        assertEquals(store.loadPrices(), data.prices)
    }

    @Test
    fun oldCacheFetchesBothSources() = runTest {
        val counter = CountingSources()
        val data = loader(seeded(121.seconds), counter).load(maxCacheAge = window)
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
    }

    @Test
    fun defaultAlwaysFetches() = runTest {
        val counter = CountingSources()
        val store = seeded(1.seconds)
        loader(store, counter).load()
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
        loader(store, counter).load(maxCacheAge = Duration.ZERO)
        assertEquals(2, counter.solarCalls)
        assertEquals(2, counter.priceCalls)
    }

    @Test
    fun oneOldSourceFetchesBoth() = runTest {
        val counter = CountingSources()
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = true)) }
        store.saveSolar(storeSampleSolar(loaderNow.minusSeconds(10)))
        store.savePrices(storeSamplePrices(fetchedAt = loaderNow.minusSeconds(600)))
        loader(store, counter).load(maxCacheAge = window)
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
    }

    @Test
    fun solarIsIgnoredWhenNotConfigured() = runTest {
        val counter = CountingSources()
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = "", showTomorrowPrices = true)) }
        store.savePrices(storeSamplePrices(fetchedAt = loaderNow.minusSeconds(30)))
        val data = loader(store, counter, solar = false).load(maxCacheAge = window)
        assertEquals(0, counter.solarCalls)
        assertEquals(0, counter.priceCalls)
        assertEquals(SourceStatus.NotConfigured, data.solarStatus)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
    }

    @Test
    fun pricesOfAnotherRegionDoNotCount() = runTest {
        val counter = CountingSources()
        val store = seeded(10.seconds, region = PriceRegion.PORTUGAL)
        store.saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = true, priceRegion = PriceRegion.SPAIN))
        loader(store, counter).load(maxCacheAge = window)
        assertEquals(1, counter.priceCalls)
    }

    @Test
    fun pricesThatDoNotCoverNowDoNotCount() = runTest {
        val counter = CountingSources()
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = true)) }
        store.saveSolar(storeSampleSolar(loaderNow.minusSeconds(10)))
        // Yesterday's prices, fetched a moment ago: they exist but cover no hour of today.
        store.savePrices(storeSamplePrices(day = madrid(2026, 5, 31), fetchedAt = loaderNow.minusSeconds(10)))
        loader(store, counter).load(maxCacheAge = window)
        assertEquals(1, counter.priceCalls)
    }

    @Test
    fun solarFromAnotherKioskLinkDoesNotCount() = runTest {
        val counter = CountingSources()
        val store = seeded(10.seconds, endpointID = KioskEndpoint.parse("https://other.test/x?kk=OTHER")!!.cacheID)
        loader(store, counter).load(maxCacheAge = window)
        assertEquals(1, counter.solarCalls)
    }

    @Test
    fun solarWithoutIdentifierDoesNotCount() = runTest {
        val counter = CountingSources()
        loader(seeded(10.seconds, endpointID = null), counter).load(maxCacheAge = window)
        assertEquals(1, counter.solarCalls)
    }

    @Test
    fun solarFromYesterdayDoesNotCountEvenWithinTheWindow() = runTest {
        val counter = CountingSources()
        val now = madrid(2026, 6, 2).plusSeconds(30)
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = true)) }
        store.saveSolar(storeSampleSolar(now.minusSeconds(60)))
        store.savePrices(storeSamplePrices(day = madrid(2026, 6, 2), fetchedAt = now.minusSeconds(60)))
        loader(store, counter, now = now).load(maxCacheAge = window)
        assertEquals(1, counter.solarCalls)
    }

    @Test
    fun aFetchTimeInTheFutureDoesNotCount() = runTest {
        val counter = CountingSources()
        loader(seeded((-30).seconds), counter).load(maxCacheAge = window)
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
    }

    /** The case behind the 429s: N widgets reload at once after a refresh. One fetch, then everyone reads the store. */
    @Test
    fun manyReloadsAfterOneFetchMakeNoRequests() = runTest {
        val counter = CountingSources()
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = true)) }
        val first = loader(store, counter).load()
        assertEquals(SourceStatus.Fresh, first.solarStatus)
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
        val all = (1..12).map { async { loader(store, counter).load(maxCacheAge = window) } }.awaitAll()
        for (data in all) {
            assertEquals(SourceStatus.Fresh, data.solarStatus)
            assertEquals(SourceStatus.Fresh, data.priceStatus)
            assertNotNull(data.solar)
            assertNotNull(data.prices)
        }
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
    }

    @Test
    fun explicitRefreshIgnoresTheWindow() = runTest {
        val counter = CountingSources()
        loader(seeded(5.seconds), counter).load(deadline = 10.seconds, maxCacheAge = Duration.ZERO)
        assertEquals(1, counter.solarCalls)
        assertEquals(1, counter.priceCalls)
    }

    @Test
    fun controlsAndIntentsUseTheWindow() = runTest {
        val counter = CountingSources()
        val source = IntentDataSource(loader(seeded(100.seconds), counter))
        assertEquals(SunWattLoader.SHARED_CACHE_WINDOW, source.maxCacheAge)
        // Price for the current hour is cached: answered without a request.
        source.data(IntentDataSource.Need.PRICE)
        assertEquals(0, counter.priceCalls)
    }
}

class SolarEndpointCacheTest {
    private fun settings(url: String = TEST_KIOSK_URL) = SunWattSettings(kioskURL = url, showTomorrowPrices = true)
    private val otherUrl = "https://other.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=OTHERKK"

    @Test
    fun fetchedSnapshotIsStoredWithTheEndpointIDNotTheToken() = runTest {
        val counter = CountingSources()
        val storage = com.ideatic.sunwatt.core.store.MemoryStorage()
        val store = SunWattStore(storage, com.ideatic.sunwatt.core.store.FakeCipher())
        store.saveSettings(settings())
        loader(store, counter).load()
        assertEquals(testKioskID, store.loadSolar()?.endpointID)
        val raw = storage.read("solar.json")!!.toString(Charsets.UTF_8)
        assertFalse("TESTKK" in raw)
        assertTrue(testKioskID in raw)
    }

    @Test
    fun cacheOfAnotherLinkIsIgnoredWhenTheFetchFails() = runTest {
        val store = tempStore().apply { saveSettings(settings(otherUrl)) }
        store.saveSolar(storeSampleSolar(endpointID = testKioskID))
        val data = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), FakePlain(Result.success(storeSamplePrices()))).load()
        assertNull(data.solar)
        assertEquals(SourceStatus.Error.of(SunWattError.Unavailable), data.solarStatus)
    }

    @Test
    fun cacheOfTheSameLinkIsStillUsedWhenTheFetchFails() = runTest {
        val store = tempStore().apply { saveSettings(settings()) }
        val cached = storeSampleSolar()
        store.saveSolar(cached)
        val data = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), FakePlain(Result.success(storeSamplePrices()))).load()
        assertEquals(cached, data.solar)
        assertEquals(SourceStatus.Stale(cached.fetchedAt), data.solarStatus)
    }

    @Test
    fun cacheWithoutIdentifierNeverMatches() = runTest {
        val store = tempStore().apply { saveSettings(settings()) }
        store.saveSolar(storeSampleSolar(endpointID = null))
        val loader = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), FakePlain(Result.failure(SunWattError.Unavailable)))
        assertNull(loader.cached().solar)
        assertNull(loader.load().solar)
    }

    @Test
    fun cachedIgnoresAnotherLinkAndKeepsTheRightOne() {
        val store = tempStore().apply { saveSettings(settings()) }
        val right = storeSampleSolar()
        store.saveSolar(right)
        val loader = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), FakePlain(Result.failure(SunWattError.Unavailable)))
        assertEquals(right, loader.cached().solar)
        store.saveSettings(settings(otherUrl))
        val other = loader.cached()
        assertNull(other.solar)
        assertEquals(SourceStatus.Error.of(SunWattError.Unavailable), other.solarStatus)
    }

    @Test
    fun changingTheLinkThenFetchingReplacesTheSnapshot() = runTest {
        val counter = CountingSources()
        val store = tempStore().apply { saveSettings(settings()) }
        loader(store, counter).load()
        store.saveSettings(settings(otherUrl))
        val data = loader(store, counter).load(maxCacheAge = window)
        assertEquals(2, counter.solarCalls)
        val expected = KioskEndpoint.parse(otherUrl)?.cacheID
        assertEquals(expected, data.solar?.endpointID)
        assertEquals(expected, store.loadSolar()?.endpointID)
    }

    @Test
    fun cacheIDDependsOnHostAndTokenAndHidesThem() {
        val a = KioskEndpoint("h.test", "abc").cacheID
        assertEquals(a, KioskEndpoint("h.test", "abc").cacheID)
        assertTrue(a != KioskEndpoint("h.test", "abd").cacheID)
        assertTrue(a != KioskEndpoint("i.test", "abc").cacheID)
        assertTrue(a.length == 64 && "abc" !in a && "h.test" !in a)
    }
}
