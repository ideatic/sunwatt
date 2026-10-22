package com.ideatic.sunwatt.core.loader

import com.ideatic.sunwatt.core.data.TEST_KIOSK_URL
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.data.samplePrices
import com.ideatic.sunwatt.core.data.sampleSolar
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.store.FakeCipher
import com.ideatic.sunwatt.core.store.MemoryStorage
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// Helpers of the loader tests (the Swift `LoaderTests`, `CacheWindowTests` and `StoreTests` helpers).

val loaderNow: Instant = madrid(2026, 6, 1, 12, 30)

/** What the loader stores in `SolarSnapshot.endpointID` for [TEST_KIOSK_URL]. */
val testKioskID: String = KioskEndpoint.parse(TEST_KIOSK_URL)!!.cacheID

fun tempStore(): SunWattStore = SunWattStore(MemoryStorage(), FakeCipher())

fun storeSampleSolar(fetchedAt: Instant = madrid(2026, 6, 1, 12, 0), endpointID: String? = testKioskID): SolarSnapshot =
    sampleSolar(fetchedAt, endpointID)

fun storeSamplePrices(day: Instant = madrid(2026, 6, 1), fetchedAt: Instant = madrid(2026, 6, 1, 12)): PriceSeries =
    samplePrices(day, fetchedAt)

fun SunWattStore.configure(tomorrow: Boolean = true) {
    saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, showTomorrowPrices = tomorrow))
}

fun makeLoader(store: SunWattStore, solar: SolarDataSource?, prices: PriceDataSource, now: Instant = loaderNow): SunWattLoader =
    SunWattLoader(store, makeSolar = { solar }, prices = prices, now = { now })

/** A price source that answers `fetch(day)` only; "today and tomorrow" is the current day (the Swift default). */
class FakePlain(private val result: Result<PriceSeries>) : PriceDataSource {
    val days = CopyOnWriteArrayList<Instant>()

    override suspend fun fetch(day: Instant): PriceSeries {
        days.add(day)
        return result.getOrThrow()
    }

    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries = fetch(Instant.now())
}

/** A price source that logs the `includeTomorrow` flag. */
class FakeRange(private val result: Result<PriceSeries>) : PriceDataSource {
    val flags = CopyOnWriteArrayList<Boolean>()

    override suspend fun fetch(day: Instant): PriceSeries = result.getOrThrow()

    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries {
        flags.add(includeTomorrow)
        return result.getOrThrow()
    }
}

/** A solar source that counts its fetches. */
class CallCountingSolar(private val result: Result<SolarSnapshot>) : SolarDataSource {
    private val calls = AtomicInteger()
    val count: Int get() = calls.get()

    override suspend fun fetch(): SolarSnapshot {
        calls.incrementAndGet()
        return result.getOrThrow()
    }
}
