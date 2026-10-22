package com.ideatic.sunwatt.core.loader

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.TEST_KIOSK_URL
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.models.PriceGranularity
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val edgeSettings = SunWattSettings(
    showTomorrowPrices = false, solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567", solarAPIKey = "ABCDEFGH12345678",
)

/** Fixes from the Swift full code review that go through the loader (findings #2, #5, #10). */
class ReviewFixLoaderTest {
    private val now = madrid(2026, 6, 1, 12, 30)

    // #2: SolarEdge solar younger than its fetch interval counts for the shared cache window.
    @Test
    fun sharedWindowAcceptsSolarEdgeReuse() = runTest {
        val store = tempStore().apply { saveSettings(edgeSettings) }
        val id = SolarAccount.fromSettings(edgeSettings)!!.cacheID
        store.saveSolar(storeSampleSolar(now.minusSeconds(8 * 60), id))
        store.savePrices(storeSamplePrices(fetchedAt = now.minusSeconds(30)))
        val solar = CallCountingSolar(Result.failure(SunWattError.Unavailable))
        val fakePrices = FakePlain(Result.success(storeSamplePrices(fetchedAt = now)))
        val data = SunWattLoader(store, makeSolar = { solar }, prices = fakePrices, now = { now })
            .load(maxCacheAge = SunWattLoader.SHARED_CACHE_WINDOW)
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SourceStatus.Fresh, data.priceStatus)
        assertEquals(0, solar.count)
        assertTrue(fakePrices.days.isEmpty())
    }

    // #10: a SolarEdge failure is reused for the fetch interval instead of asking again.
    @Test
    fun solarEdgeFailureIsNotRetriedAtOnce() = runTest {
        val store = tempStore().apply { saveSettings(edgeSettings) }
        val solar = CallCountingSolar(Result.failure(SunWattError.Http(429)))
        fun loader(date: Instant) = SunWattLoader(store, makeSolar = { solar }, prices = FakePlain(Result.success(storeSamplePrices())), now = { date })
        val first = loader(now).load()
        assertEquals(1, solar.count)
        val second = loader(now.plusSeconds(5 * 60)).load()
        assertEquals(1, solar.count)
        assertEquals(first.solarStatus, second.solarStatus)
        assertEquals(SourceStatus.Error.of(SunWattError.Http(429)), second.solarStatus)
        // The stored failure keeps the case, so the app can still localize it.
        assertEquals(SunWattError.Http(429), (second.solarStatus as SourceStatus.Error).error)
        loader(now.plusSeconds(21 * 60)).load()
        assertEquals(2, solar.count)
    }

    @Test
    fun otherProvidersRetryEveryTime() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = false)) }
        val solar = CallCountingSolar(Result.failure(SunWattError.Http(500)))
        for (minutes in listOf(0L, 1, 2)) {
            SunWattLoader(store, makeSolar = { solar }, prices = FakePlain(Result.success(storeSamplePrices())), now = { now.plusSeconds(minutes * 60) }).load()
        }
        assertEquals(3, solar.count)
    }

    // #5: a cheaper next day does not push the rest of today out of today's ranking.
    @Test
    fun cheapestUpcomingStopsAtTheDayEnd() {
        val day = madrid(2026, 6, 1)
        val points = (0 until 48).map { i -> PricePoint(day.plusSeconds(i * 3600L), 3600, if (i < 24) 100.0 + i else 10.0 + i) }
        val series = PriceSeries(points, PriceGranularity.HOURLY, fetchedAt = day, sourceUpdatedAt = day)
        val from = madrid(2026, 6, 1, 15)
        assertTrue(series.cheapestUpcoming(from, 3).all { it.start >= madrid(2026, 6, 2) })
        val today = series.cheapestUpcoming(from, 3, until = madrid(2026, 6, 2))
        assertEquals(listOf(madrid(2026, 6, 1, 15), madrid(2026, 6, 1, 16), madrid(2026, 6, 1, 17)), today.map { it.start })
    }
}

class KioskExpiryLoaderTest {
    private val now = madrid(2026, 6, 1, 12, 30)

    private fun loader(store: com.ideatic.sunwatt.core.store.SunWattStore, solar: Result<com.ideatic.sunwatt.core.models.SolarSnapshot>, at: Instant) =
        SunWattLoader(store, makeSolar = { com.ideatic.sunwatt.core.inverter.FakeSolar(solar) }, prices = FakePlain(Result.success(storeSamplePrices())), now = { at })

    private fun kioskStore() = tempStore().also {
        it.saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = false))
        it.saveSolar(storeSampleSolar(madrid(2026, 6, 1, 11, 0)))
    }

    private val rejected get() = Result.failure<com.ideatic.sunwatt.core.models.SolarSnapshot>(SunWattError.KioskLinkRejected)

    @Test
    fun firstRejectionsKeepTheCachedData() = runTest {
        val store = kioskStore()
        val data = loader(store, rejected, now).load()
        assertTrue(!data.solarLinkExpired)
        assertTrue(data.solar != null)
        assertEquals(SourceStatus.Stale(madrid(2026, 6, 1, 11, 0)), data.solarStatus)
        // 20 minutes later: still within the grace time.
        assertTrue(!loader(store, rejected, now.plusSeconds(20 * 60)).load().solarLinkExpired)
    }

    @Test
    fun rejectedForThirtyMinutesCountsAsExpired() = runTest {
        val store = kioskStore()
        loader(store, rejected, now).load()
        val data = loader(store, rejected, now.plusSeconds(31 * 60)).load()
        assertTrue(data.solarLinkExpired)
        assertEquals(null, data.solar)
        assertEquals(SourceStatus.Error.of(SunWattError.KioskLinkRejected), data.solarStatus)
        // Placeholders and snapshots (no network) agree.
        val cached = loader(store, rejected, now.plusSeconds(32 * 60)).cached()
        assertTrue(cached.solarLinkExpired && cached.solar == null)
        // Prices are unaffected.
        assertEquals(SourceStatus.Fresh, data.priceStatus)
    }

    @Test
    fun otherErrorsDoNotCount() = runTest {
        val store = kioskStore()
        val offline = Result.failure<com.ideatic.sunwatt.core.models.SolarSnapshot>(SunWattError.Network("offline"))
        loader(store, offline, now).load()
        val data = loader(store, offline, now.plusSeconds(60 * 60)).load()
        assertTrue(!data.solarLinkExpired)
        assertTrue(data.solar != null)
    }

    @Test
    fun aSuccessEndsTheRejection() = runTest {
        val store = kioskStore()
        loader(store, rejected, now).load()
        val later = now.plusSeconds(10 * 60)
        loader(store, Result.success(storeSampleSolar(later, endpointID = null)), later).load()
        assertTrue(!loader(store, rejected, now.plusSeconds(45 * 60)).load().solarLinkExpired)
    }

    @Test
    fun aNewLinkStartsClean() = runTest {
        val store = kioskStore()
        loader(store, rejected, now).load()
        store.saveSettings(store.loadSettings().copy(kioskURL = "https://example.test/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=NEWLINK"))
        val data = loader(store, Result.failure(SunWattError.Network("x")), now.plusSeconds(40 * 60)).load()
        assertTrue(!data.solarLinkExpired)
    }
}
