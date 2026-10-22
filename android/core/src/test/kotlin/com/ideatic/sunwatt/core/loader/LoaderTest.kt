package com.ideatic.sunwatt.core.loader

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.inverter.FakeSolar
import com.ideatic.sunwatt.core.services.EnergyChartsClient
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.PvpcClient
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private fun error(e: SunWattError) = SourceStatus.Error.of(e)

private val okPrices get() = FakePlain(Result.success(storeSamplePrices()))

class LoaderTest {
    @Test
    fun bothSucceedFreshAndSaved() = runTest {
        val store = tempStore().apply { configure() }
        val solar = storeSampleSolar()
        val prices = storeSamplePrices()
        val data = makeLoader(store, FakeSolar(Result.success(solar)), FakePlain(Result.success(prices))).load()
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(solar, data.solar)
        assertEquals(prices, data.prices)
        assertEquals(solar, store.loadSolar())
        assertEquals(prices, store.loadPrices())
    }

    @Test
    fun solarFailureWithCacheIsStale() = runTest {
        val store = tempStore().apply { configure() }
        val cached = storeSampleSolar(madrid(2026, 6, 1, 11, 0))
        store.saveSolar(cached)
        val data = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), okPrices).load()
        assertEquals(SourceStatus.Stale(cached.fetchedAt), data.solarStatus)
        assertEquals(cached, data.solar)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
    }

    @Test
    fun solarFailureWithoutCacheIsError() = runTest {
        val store = tempStore().apply { configure() }
        val data = makeLoader(store, FakeSolar(Result.failure(SunWattError.Http(500))), okPrices).load()
        assertEquals(error(SunWattError.Http(500)), data.solarStatus)
        assertEquals(SunWattError.Http(500), (data.solarStatus as SourceStatus.Error).error)
        assertNull(data.solar)
    }

    @Test
    fun emptyUrlIsNotConfigured() = runTest {
        val store = tempStore()
        assertEquals(SourceStatus.NotConfigured, makeLoader(store, null, okPrices).load().solarStatus)
    }

    @Test
    fun invalidUrlIsError() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = "not a kiosk url")) }
        assertEquals(error(SunWattError.InvalidKioskURL), makeLoader(store, null, okPrices).load().solarStatus)
    }

    @Test
    fun rangeSourceGetsIncludeTomorrowFlag() = runTest {
        for (flag in listOf(true, false)) {
            val store = tempStore().apply { configure(flag) }
            val fake = FakeRange(Result.success(storeSamplePrices()))
            val data = makeLoader(store, FakeSolar(Result.success(storeSampleSolar())), fake).load()
            assertEquals(listOf(flag), fake.flags)
            assertEquals(SourceStatus.Fresh, data.priceStatus)
        }
    }

    @Test
    fun plainSourceUsesDefaultRangeFetch() = runTest {
        val store = tempStore().apply { configure() }
        val fake = FakePlain(Result.success(storeSamplePrices()))
        val before = Instant.now()
        makeLoader(store, null, fake).load()
        assertEquals(1, fake.days.size)
        assertTrue(fake.days.first() >= before)
    }

    @Test
    fun yesterdaysSolarCacheIsStale() = runTest {
        val store = tempStore().apply { configure() }
        val old = storeSampleSolar(madrid(2026, 5, 31, 20, 0))
        store.saveSolar(old)
        val data = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), okPrices).load()
        assertEquals(SourceStatus.Stale(old.fetchedAt), data.solarStatus)
        assertEquals(old, data.solar)
    }

    @Test
    fun priceFailureCacheForTodayIsStale() = runTest {
        val store = tempStore().apply { configure() }
        val cached = storeSamplePrices(fetchedAt = madrid(2026, 6, 1, 8, 0))
        store.savePrices(cached)
        val data = makeLoader(store, null, FakePlain(Result.failure(SunWattError.Network("x")))).load()
        assertEquals(SourceStatus.Stale(cached.fetchedAt), data.priceStatus)
        assertEquals(cached, data.prices)
    }

    @Test
    fun priceFailureOldCacheKeepsSeriesWithError() = runTest {
        val store = tempStore().apply { configure() }
        val cached = storeSamplePrices(day = madrid(2026, 5, 30, 0, 0))
        store.savePrices(cached)
        val data = makeLoader(store, null, FakePlain(Result.failure(SunWattError.Unavailable))).load()
        assertEquals(error(SunWattError.Unavailable), data.priceStatus)
        assertEquals(cached, data.prices)
    }

    @Test
    fun priceFailureNoCacheIsError() = runTest {
        val store = tempStore().apply { configure() }
        val data = makeLoader(store, null, FakePlain(Result.failure(SunWattError.Http(502)))).load()
        assertEquals(error(SunWattError.Http(502)), data.priceStatus)
        assertNull(data.prices)
    }

    @Test
    fun cachedReturnsStoreData() {
        val store = tempStore().apply { configure() }
        val loader = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), FakePlain(Result.failure(SunWattError.Unavailable)))
        assertEquals(error(SunWattError.Unavailable), loader.cached().solarStatus)
        val solar = storeSampleSolar()
        val prices = storeSamplePrices()
        store.saveSolar(solar)
        store.savePrices(prices)
        val full = loader.cached()
        assertEquals(SourceStatus.Stale(solar.fetchedAt), full.solarStatus)
        assertEquals(SourceStatus.Stale(prices.fetchedAt), full.priceStatus)
        assertEquals(solar, full.solar)
        assertEquals(prices, full.prices)
        val none = makeLoader(store, null, FakePlain(Result.failure(SunWattError.Unavailable))).cached()
        assertEquals(error(SunWattError.InvalidKioskURL), none.solarStatus)
    }

    @Test
    fun storedIsFreshForRecentSourcesOnly() {
        // The widget never loads: data the refresh worker stored a moment ago must not carry the stale badge.
        val store = tempStore().apply { configure() }
        val prices = storeSamplePrices(fetchedAt = madrid(2026, 6, 1, 12, 0))
        val solar = storeSampleSolar(fetchedAt = madrid(2026, 6, 1, 11, 0))
        store.savePrices(prices)
        store.saveSolar(solar)
        val loader = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), FakePlain(Result.failure(SunWattError.Unavailable)), now = madrid(2026, 6, 1, 12, 10))
        val data = loader.stored(freshFor = 30.minutes)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(SourceStatus.Stale(solar.fetchedAt), data.solarStatus)
        assertEquals(solar, data.solar)
        assertEquals(prices, data.prices)
    }

    @Test
    fun latestKeepsTheOutcomeOfTheLastLoad() = runTest {
        // A failed refresh keeps the old cache: the widget must show it as stale at once, as the Apple widget does.
        val store = tempStore().apply { configure() }
        val prices = storeSamplePrices(fetchedAt = madrid(2026, 6, 1, 12, 0))
        store.savePrices(prices)
        val loader = makeLoader(store, null, FakePlain(Result.failure(SunWattError.Http(429))), now = madrid(2026, 6, 1, 12, 10))
        assertEquals(SourceStatus.Fresh, loader.latest().priceStatus)
        loader.load()
        assertEquals(SourceStatus.Stale(prices.fetchedAt), loader.latest().priceStatus)
        // Other settings: that load no longer applies.
        store.saveSettings(store.loadSettings().copy(showTomorrowPrices = false))
        assertEquals(SourceStatus.Fresh, loader.latest().priceStatus)
    }

    @Test
    fun errorStatusKeepsTheCaseForTheApp() = runTest {
        val store = tempStore().apply { configure() }
        val data = makeLoader(store, FakeSolar(Result.failure(SunWattError.Network("offline"))), FakePlain(Result.failure(SunWattError.Http(503)))).load()
        val solar = data.solarStatus as SourceStatus.Error
        assertEquals("network", solar.code)
        assertEquals(SunWattError.Network("offline"), solar.error)
        assertEquals(SunWattError.Http(503), (data.priceStatus as SourceStatus.Error).error)
        // A failure that is not a SunWattError keeps only its message.
        val odd = makeLoader(store, FakeSolar(Result.failure(IllegalStateException("odd"))), okPrices).load()
        assertEquals(SourceStatus.Error("odd"), odd.solarStatus)
        assertNull((odd.solarStatus as SourceStatus.Error).error)
    }
}

/** Never answers and ignores cancellation (worst case for the deadline). */
private object HangingPrices : PriceDataSource {
    override suspend fun fetch(day: Instant): PriceSeries {
        withContext(NonCancellable) { awaitCancellation() }
    }

    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries = fetch(Instant.now())
}

private object HangingSolar : com.ideatic.sunwatt.core.services.SolarDataSource {
    override suspend fun fetch(): com.ideatic.sunwatt.core.models.SolarSnapshot {
        delay(60.seconds)
        throw CancellationException()
    }
}

class LoaderDeadlineTest {
    @Test
    fun hangingSourcesReturnWithinTheDeadline() = runTest {
        val store = tempStore().apply { configure() }
        val loader = makeLoader(store, HangingSolar, HangingPrices)
        val data = loader.load(deadline = 300.milliseconds)
        assertEquals(300, testScheduler.currentTime)
        assertIs<SourceStatus.Error>(data.solarStatus)
        assertIs<SourceStatus.Error>(data.priceStatus)
        assertEquals(SunWattError.Network("timed out"), (data.priceStatus as SourceStatus.Error).error)
    }

    @Test
    fun timeoutFallsBackToStaleCache() = runTest {
        val store = tempStore().apply { configure() }
        val solar = storeSampleSolar()
        val prices = storeSamplePrices()
        store.saveSolar(solar)
        store.savePrices(prices)
        val data = makeLoader(store, HangingSolar, HangingPrices).load(deadline = 200.milliseconds)
        assertEquals(SourceStatus.Stale(solar.fetchedAt), data.solarStatus)
        assertEquals(SourceStatus.Stale(prices.fetchedAt), data.priceStatus)
        assertEquals(solar, data.solar)
        assertEquals(prices, data.prices)
    }

    @Test
    fun oneSlowSourceDoesNotBlockTheOther() = runTest {
        val store = tempStore().apply { configure() }
        val good = storeSamplePrices()
        val data = makeLoader(store, HangingSolar, FakePlain(Result.success(good))).load(deadline = 1500.milliseconds)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(good, data.prices)
        assertIs<SourceStatus.Error>(data.solarStatus)
    }

    @Test
    fun cancellationThrowsAndSavesNothing() = runTest {
        val store = tempStore().apply { configure() }
        val loader = makeLoader(store, FakeSolar(Result.success(storeSampleSolar())), HangingPrices)
        val job = async { loader.load(deadline = 30.seconds) }
        advanceTimeBy(100)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertNull(store.loadSolar())
        assertNull(store.loadPrices())
        assertEquals(error(SunWattError.Unavailable), loader.cached().priceStatus)
    }

    @Test
    fun alreadyCancelledTaskThrowsBeforeTouchingTheStore() = runTest {
        val store = tempStore().apply { configure() }
        val cachedPrices = storeSamplePrices()
        store.savePrices(cachedPrices)
        val loader = makeLoader(store, null, FakePlain(Result.success(storeSamplePrices(fetchedAt = madrid(2026, 6, 1, 12)))))
        val job = launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> { loader.load() }
        }
        job.join()
        assertEquals(cachedPrices, store.loadPrices())
    }

    @Test
    fun cancelledSourceErrorIsNotReportedAsStatus() = runTest {
        val store = tempStore().apply { configure() }
        val cancelling = object : PriceDataSource {
            override suspend fun fetch(day: Instant): PriceSeries = throw CancellationException()
            override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries = fetch(Instant.now())
        }
        store.savePrices(storeSamplePrices())
        assertFailsWith<CancellationException> { makeLoader(store, null, cancelling).load() }
    }
}

private fun series(days: Int, from: Instant, region: PriceRegion = PriceRegion.SPAIN, fetchedAt: Instant = madrid(2026, 6, 1, 8)) = PriceSeries(
    points = (0 until 24 * days).map { PricePoint(from.plusSeconds(it * 3600L), 3600, 100.0 + it) },
    granularity = PriceGranularity.HOURLY, fetchedAt = fetchedAt, region = region,
)

class LoaderPriceCacheTest {
    @Test
    fun transientTomorrowFailureKeepsCachedTomorrow() = runTest {
        val store = tempStore().apply { configure() }
        val day = madrid(2026, 6, 1)
        val withTomorrow = series(2, day)
        store.savePrices(withTomorrow)
        val todayOnly = series(1, day, fetchedAt = madrid(2026, 6, 1, 12))
        val data = makeLoader(store, null, FakePlain(Result.success(todayOnly))).load()
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(48, data.prices?.points?.size)
        assertEquals(todayOnly.fetchedAt, data.prices?.fetchedAt)
        assertEquals(todayOnly.points.first(), data.prices?.points?.first())
        assertEquals(48, store.loadPrices()?.points?.size)
        // The new today values win; tomorrow comes from the old cache.
        assertEquals(withTomorrow.points[30], data.prices?.points?.get(30))
    }

    @Test
    fun pastCachedPointsAreNotKept() = runTest {
        val store = tempStore().apply { configure() }
        // Cache holds 30 May + 31 May; the new series is 1 Jun. Nothing of the cache starts after it.
        store.savePrices(series(2, madrid(2026, 5, 30)))
        val fresh = series(1, madrid(2026, 6, 1), fetchedAt = madrid(2026, 6, 1, 12))
        assertEquals(fresh, makeLoader(store, null, FakePlain(Result.success(fresh))).load().prices)
    }

    @Test
    fun tomorrowIsNotKeptWhenSettingIsOff() = runTest {
        val store = tempStore().apply { configure(tomorrow = false) }
        store.savePrices(series(2, madrid(2026, 6, 1)))
        val today = series(1, madrid(2026, 6, 1), fetchedAt = madrid(2026, 6, 1, 12))
        assertEquals(today, makeLoader(store, null, FakePlain(Result.success(today))).load().prices)
    }

    @Test
    fun cachedPricesOfAnotherRegionAreIgnored() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(showTomorrowPrices = true, priceRegion = PriceRegion.SPAIN)) }
        val portugal = series(1, madrid(2026, 6, 1), region = PriceRegion.PORTUGAL)
        store.savePrices(portugal)
        val loader = makeLoader(store, null, FakePlain(Result.failure(SunWattError.Network("x"))))
        val data = loader.load()
        assertNull(data.prices)
        assertEquals(error(SunWattError.Network("x")), data.priceStatus)
        assertNull(loader.cached().prices)

        store.saveSettings(SunWattSettings(showTomorrowPrices = true, priceRegion = PriceRegion.PORTUGAL))
        assertEquals(portugal, loader.cached().prices)
        assertEquals(portugal, loader.load().prices)
    }

    @Test
    fun staleCachedTomorrowOfAnotherRegionIsNotMergedIn() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(showTomorrowPrices = true, priceRegion = PriceRegion.SPAIN)) }
        store.savePrices(series(2, madrid(2026, 6, 1), region = PriceRegion.FRANCE))
        val today = series(1, madrid(2026, 6, 1), fetchedAt = madrid(2026, 6, 1, 12))
        assertEquals(today, makeLoader(store, null, FakePlain(Result.success(today))).load().prices)
    }

    @Test
    fun priceSourceIsBuiltFromTheRegionSetting() = runTest {
        val seen = java.util.concurrent.CopyOnWriteArrayList<PriceRegion>()
        val store = tempStore().apply { saveSettings(SunWattSettings(showTomorrowPrices = true, priceRegion = PriceRegion.AUSTRIA)) }
        val loader = SunWattLoader(store, makeSolar = { null }, makeLiveSolar = { null }, makePrices = { region ->
            seen.add(region)
            FakePlain(Result.success(series(1, madrid(2026, 6, 1), region = region)))
        }, now = { loaderNow })
        val data = loader.load()
        assertTrue(PriceRegion.AUSTRIA in seen)
        assertEquals(PriceRegion.AUSTRIA, data.prices?.region)
        assertEquals(PriceRegion.AUSTRIA, store.loadPrices()?.region)
    }

    @Test
    fun defaultPriceSourcesFollowTheRegion() {
        assertIs<PvpcClient>(SunWattLoader.defaultPrices(PriceRegion.SPAIN))
        assertIs<EnergyChartsClient>(SunWattLoader.defaultPrices(PriceRegion.NETHERLANDS))
    }

    @Test
    fun dstDayCacheCountsAsToday() = runTest {
        for (now in listOf(madrid(2026, 3, 29, 12), madrid(2026, 10, 25, 12))) {
            val store = tempStore().apply { configure() }
            val day = now.atZone(Constants.madrid).toLocalDate()
            val start = day.atStartOfDay(Constants.madrid).toInstant()
            val end = day.plusDays(1).atStartOfDay(Constants.madrid).toInstant()
            val hours = ((end.epochSecond - start.epochSecond) / 3600).toInt()
            assertTrue(hours == 23 || hours == 25)
            val points = (0 until hours).map { PricePoint(start.plusSeconds(it * 3600L), 3600, 50.0) }
            val cached = PriceSeries(points, PriceGranularity.HOURLY, fetchedAt = start.plusSeconds(3600))
            store.savePrices(cached)
            val loader = SunWattLoader(store, makeSolar = { null }, prices = FakePlain(Result.failure(SunWattError.Unavailable)), now = { now })
            assertEquals(SourceStatus.Stale(cached.fetchedAt), loader.load().priceStatus)
            // Every hour of the day is covered, and the first hour after it is not.
            for (p in points) {
                val data = SunWattData(null, cached, SourceStatus.NotConfigured, SourceStatus.Fresh)
                assertFalse(SunWattEntry(p.start.plusSeconds(1800), data).isPriceStale)
            }
            assertTrue(SunWattEntry(end.plusSeconds(60), SunWattData(null, cached, SourceStatus.NotConfigured, SourceStatus.Fresh)).isPriceStale)
        }
    }

    @Test
    fun keepingFutureHoursNeedsTheSameRegion() {
        val new = series(1, madrid(2026, 6, 1))
        assertEquals(new, SunWattLoader.keepingFutureHours(new, null, loaderNow))
        assertEquals(new, SunWattLoader.keepingFutureHours(new, series(2, madrid(2026, 6, 1), region = PriceRegion.FRANCE), loaderNow))
    }
}

class RaceTest {
    @Test
    fun resultOfTheWork() = runTest {
        assertEquals(7, SunWattLoader.race(1.seconds) { 7 }.getOrThrow())
    }

    @Test
    fun failureOfTheWorkIsReturned() = runTest {
        val result = SunWattLoader.race(1.seconds) { throw SunWattError.Unavailable }
        assertEquals(SunWattError.Unavailable, result.exceptionOrNull())
    }

    @Test
    fun timeoutIsANetworkError() = runTest {
        val result = SunWattLoader.race(500.milliseconds) { awaitCancellation() }
        assertEquals(SunWattError.Network("timed out"), result.exceptionOrNull())
        assertEquals(500, testScheduler.currentTime)
    }

    @Test
    fun aTimeoutInsideTheWorkReadsAsNetworkTimeout() = runTest {
        val result = SunWattLoader.race(10.seconds) { withTimeout(100.milliseconds) { awaitCancellation() } }
        assertEquals(SunWattError.Network("timed out"), result.exceptionOrNull())
    }

    @Test
    fun returnsAtTheDeadlineEvenWhenTheWorkIgnoresCancellation() = runTest {
        val result = SunWattLoader.race(200.milliseconds) { withContext(NonCancellable) { awaitCancellation() } }
        assertEquals(SunWattError.Network("timed out"), result.exceptionOrNull())
    }

    @Test
    fun cancellingTheCallerCancelsTheWork() = runTest {
        val started = CompletableDeferred<Unit>()
        val workCancelled = CompletableDeferred<Boolean>()
        val job = async {
            SunWattLoader.race(30.seconds) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    workCancelled.complete(true)
                    throw e
                }
            }
        }
        started.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(workCancelled.await())
    }

    @Test
    fun cancellationThrownByTheWorkPassesThrough() = runTest {
        assertFailsWith<CancellationException> { SunWattLoader.race(1.seconds) { throw CancellationException("x") } }
    }
}

class LoaderParallelTest {
    @Test
    fun fiveParallelLoadsInTheWindowMakeOnePriceRequest() = runBlocking(Dispatchers.Default) {
        val store = tempStore().apply { configure() }
        val priceCalls = AtomicInteger()
        val solarCalls = AtomicInteger()
        val prices = object : PriceDataSource {
            override suspend fun fetch(day: Instant): PriceSeries = fetchTodayAndTomorrow(true)
            override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries {
                priceCalls.incrementAndGet()
                delay(100)
                return storeSamplePrices(fetchedAt = loaderNow)
            }
        }
        val solar = object : com.ideatic.sunwatt.core.services.SolarDataSource {
            override suspend fun fetch(): com.ideatic.sunwatt.core.models.SolarSnapshot {
                solarCalls.incrementAndGet()
                delay(100)
                return storeSampleSolar(fetchedAt = loaderNow, endpointID = null)
            }
        }
        val results = (1..5).map { async { makeLoader(store, solar, prices).load(maxCacheAge = SunWattLoader.SHARED_CACHE_WINDOW) } }.awaitAll()
        assertEquals(1, priceCalls.get())
        assertEquals(1, solarCalls.get())
        assertTrue(results.all { it.priceStatus == SourceStatus.Fresh && it.solarStatus == SourceStatus.Fresh })
        assertTrue(results.all { it.prices != null && it.solar != null })
    }

    @Test
    fun explicitRefreshesStillRunOneAfterTheOther() = runBlocking(Dispatchers.Default) {
        val store = tempStore().apply { configure() }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val prices = object : PriceDataSource {
            override suspend fun fetch(day: Instant): PriceSeries = fetchTodayAndTomorrow(true)
            override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries {
                peak.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                delay(50)
                active.decrementAndGet()
                return storeSamplePrices()
            }
        }
        (1..3).map { async { makeLoader(store, null, prices).load() } }.awaitAll()
        assertEquals(1, peak.get())
    }
}
