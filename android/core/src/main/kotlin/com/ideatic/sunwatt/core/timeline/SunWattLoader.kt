package com.ideatic.sunwatt.core.timeline

import com.ideatic.sunwatt.core.data.PlantSolarData
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.inverter.LocalInverterSolarClient
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceSeriesBuilding
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.focused
import com.ideatic.sunwatt.core.models.hasSolarInput
import com.ideatic.sunwatt.core.models.invalidSolarInputError
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.NorthboundSessionStore
import com.ideatic.sunwatt.core.services.OkHttpSunWattClient
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.store.SolarFailure
import com.ideatic.sunwatt.core.store.SunWattStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.YearMonth
import java.time.Year
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/**
 * Fetches both sources concurrently, saves good data to the store, falls back to cached data on failure (port of the
 * Swift `SunWattLoader`).
 *
 * Widgets, tiles, workers and the app share ONE process on Android, so a process-wide lock ([networkLock]) serializes
 * the network part of every load: callers that start together reuse what the first one stored when they pass
 * `maxCacheAge` ([SHARED_CACHE_WINDOW]). That is what keeps Energy-Charts (HTTP 429 for parallel requests) calm.
 */
class SunWattLoader(
    val store: SunWattStore,
    /** Builds the solar source from settings; null when the selected solar provider is not configured or invalid. */
    val makeSolar: (SunWattSettings) -> SolarDataSource?,
    /** The inverter on the home network alone (no cloud request), read while the cloud source is rate-limited. */
    val makeLiveSolar: (SunWattSettings) -> SolarDataSource?,
    /** Builds the price source for the region chosen in settings. */
    val makePrices: (PriceRegion) -> PriceDataSource,
    val now: () -> Instant = Instant::now,
) {
    constructor(
        store: SunWattStore,
        http: HttpClient = OkHttpSunWattClient(),
        northboundSessions: NorthboundSessionStore? = null,
        now: () -> Instant = Instant::now,
    ) : this(
        store,
        makeSolar = { settings -> SolarAccount.fromSettings(settings)?.let { it.client(http, northboundSessions ?: sharedNorthboundSessions, now) } },
        makeLiveSolar = { settings -> SolarAccount.fromSettings(settings)?.liveOnlyClient(http, now) },
        makePrices = { region -> defaultPrices(region, http) },
        now = now,
    )

    /** Uses one fixed price source whatever the region setting is (tests). */
    constructor(
        store: SunWattStore,
        makeSolar: (SunWattSettings) -> SolarDataSource?,
        prices: PriceDataSource,
        now: () -> Instant = Instant::now,
        makeLiveSolar: (SunWattSettings) -> SolarDataSource? = { null },
    ) : this(store, makeSolar, makeLiveSolar, { prices }, now)

    /** Price source for the region currently stored in settings. */
    val prices: PriceDataSource get() = makePrices(store.loadSettings().priceRegion)

    /**
     * Never throws for a failed source: errors become [SourceStatus] values. Each fetch must finish within [deadline] (a
     * widget refresh has a small budget); a source that is too slow counts as failed, so cached data is shown as
     * stale. When the caller is cancelled, throws `CancellationException` and saves nothing (the Swift loader returns
     * [cached] there; a coroutine that is cancelled must not carry on).
     *
     * [maxCacheAge]: when greater than zero and the store holds data fetched less than this long ago for every source
     * in use, that data is returned as fresh without any network request. Solar counts only if the solar account is
     * configured, belongs to the stored snapshot and was fetched on the current Madrid day; prices count only for the
     * current region and when a point covers now. Zero (default) always fetches. Callers that start together wait for
     * the first one and then read what it stored.
     */
    suspend fun load(deadline: Duration = DEADLINE, maxCacheAge: Duration = Duration.ZERO): SunWattData {
        currentCoroutineContext().ensureActive()
        if (maxCacheAge > Duration.ZERO) {
            val settings = store.loadSettings()
            recentCache(settings, maxCacheAge, now())?.let { return remember(settings, it) }
        }
        return networkLock.withLock {
            val settings = store.loadSettings()
            val date = now()
            if (maxCacheAge > Duration.ZERO) recentCache(settings, maxCacheAge, date)?.let { return@withLock remember(settings, it) }
            remember(settings, loadFromNetwork(settings, date, deadline))
        }
    }

    /** The last [load] result and the settings it was for, so [latest] can show it like an Apple widget shows its load. */
    @Volatile
    private var lastLoad: Pair<SunWattSettings, SunWattData>? = null

    private fun remember(settings: SunWattSettings, data: SunWattData): SunWattData = data.also { lastLoad = settings to it }

    /**
     * What a view that never loads should show (Android widgets: the refresh worker loads, DECISIONS D11): the last
     * [load] of this process, with its fresh, stale and error states, while the settings are the same. After a
     * process restart, [stored] with the [Constants.staleAfter] age limit.
     */
    fun latest(): SunWattData {
        val settings = store.loadSettings()
        return lastLoad?.takeIf { it.first == settings }?.second ?: stored(freshFor = Constants.staleAfter)
    }

    private suspend fun loadFromNetwork(settings: SunWattSettings, date: Instant, deadline: Duration): SunWattData = coroutineScope {
        val priceSource = makePrices(settings.priceRegion)
        val priceResult = async { race(deadline) { priceSource.fetchTodayAndTomorrow(settings.showTomorrowPrices) } }
        // Every plant at once, each with its own cache, limits and failures.
        val loads = settings.plants.map { plant -> async { loadPlant(plant, settings, deadline, date) } }.awaitAll()
        val priceOutcome = priceResult.await()
        // A cancelled load writes nothing, so the plants' writes wait until here.
        ensureActive()
        loads.forEach { it.save() }

        var data = SunWattData(solar = null, prices = null, solarStatus = SourceStatus.NotConfigured, priceStatus = SourceStatus.Fresh)
            .withPlants(loads.map { it.data })

        val cachedPrices = store.loadPrices()?.takeIf { it.region == settings.priceRegion }
        val fetched = priceOutcome.getOrNull()
        if (fetched != null) {
            val series = if (settings.showTomorrowPrices) keepingFutureHours(fetched, cachedPrices, date) else fetched
            store.savePrices(series)
            data = data.copy(prices = series, priceStatus = SourceStatus.Fresh)
        } else {
            val error = priceOutcome.exceptionOrNull() ?: SunWattError.Unavailable
            if (cachedPrices != null) {
                val zone = cachedPrices.region.timeZone
                val today = date.atZone(zone).toLocalDate()
                val hasToday = cachedPrices.points.any { it.start.atZone(zone).toLocalDate() == today }
                data = data.copy(
                    prices = cachedPrices,
                    priceStatus = if (hasToday) SourceStatus.Stale(cachedPrices.fetchedAt) else SourceStatus.Error.of(error),
                )
            } else {
                data = data.copy(priceStatus = SourceStatus.Error.of(error))
            }
        }
        data
    }

    /**
     * One plant's part of a load: fetch (or reuse, under the provider's limit), store, fall back to its cache.
     * [settings] are the whole settings; the plant is put in the place of the first one, so every helper written for
     * "the" plant works on it. Store writes are returned in [PlantLoad], not done: the caller runs them only when the
     * whole load was not cancelled.
     */
    private suspend fun loadPlant(plant: SolarPlant, all: SunWattSettings, deadline: Duration, date: Instant): PlantLoad {
        val settings = all.focused(plant)
        val plantID = plant.id
        val solarSource = makeSolar(settings)
            ?: return PlantLoad(PlantSolarData(plantID, plant.displayName(null), null, unconfiguredStatus(settings)))
        val reusedSolar = recentSolar(settings, plantID, date)
        // A rate-limited provider that failed moments ago is not asked again yet: its failure is reused.
        val recentFailure = if (reusedSolar != null) null else recentSolarFailure(settings, date)
        // While the cloud is not asked, the inverter on the home network still is (live data at home).
        val cloudFetched = reusedSolar == null && recentFailure == null
        val liveSource = if (cloudFetched) null else makeLiveSolar(settings)
        val outcome = fetchSolar(solarSource, reusedSolar, recentFailure, liveSource, deadline)
        currentCoroutineContext().ensureActive()

        val account = SolarAccount.fromSettings(settings)
        val writes = mutableListOf<() -> Unit>()
        var solar: SolarSnapshot? = null
        val status: SourceStatus
        var linkExpired = false
        val snapshot = outcome.getOrNull()
        if (snapshot != null) {
            account?.kioskEndpoint?.cacheID?.let { link -> writes += { store.clearKioskRejection(link) } }
            if (cloudFetched && account != null) {
                if (snapshot.isLive == true && snapshot.cloudFetchedAt == null && account.provider.minimumFetchInterval > Duration.ZERO) {
                    // Only the inverter answered: the cloud failed and is not asked again for its interval.
                    writes += { noteFailure(account, date, SunWattError.Unavailable) }
                } else {
                    writes += { store.clearSolarFailure(account.cacheID) }
                }
            }
            var result: SolarSnapshot = snapshot
            if (result.isLive == true) {
                cachedSolar(settings, plantID)?.let { result = fillingIn(result, it) }
            }
            result = result.copy(endpointID = endpointID(settings))
            val saved = result
            writes += { store.saveSolar(saved, plantID) }
            solar = result
            status = SourceStatus.Fresh
        } else {
            val error = outcome.exceptionOrNull() ?: SunWattError.Unavailable
            if (recentFailure == null && account != null && account.provider.minimumFetchInterval > Duration.ZERO) {
                writes += { noteFailure(account, date, error) }
            }
            if (error is SunWattError.KioskLinkRejected) {
                account?.kioskEndpoint?.cacheID?.let { link -> writes += { store.noteKioskRejected(link, date) } }
            }
            val cached = cachedSolar(settings, plantID)
            if (isKioskLinkExpired(settings, date)) {
                // Rejected for a while: the link most likely expired. Ask for a new one instead of showing old data.
                status = SourceStatus.Error.of(SunWattError.KioskLinkRejected)
                linkExpired = true
            } else if (cached != null) {
                // Also covers a cache from a previous Madrid day: its curve belongs to another day.
                solar = cached
                status = SourceStatus.Stale(cached.fetchedAt)
            } else {
                status = errorStatus(error)
            }
        }
        val data = PlantSolarData(plantID, plant.displayName(solar?.stationName), solar, status, linkExpired)
        return PlantLoad(data, writes)
    }

    /** One plant's result and the store writes that go with it, run by [save] once the load is known to be kept. */
    private class PlantLoad(val data: PlantSolarData, val writes: List<() -> Unit> = emptyList()) {
        fun save() = writes.forEach { it() }
    }

    private fun noteFailure(account: SolarAccount, at: Instant, error: Throwable) {
        store.noteSolarFailure(account.cacheID, at, error.message.orEmpty(), (error as? SunWattError)?.code, (error as? SunWattError)?.detail)
    }

    /** The status for a failure; a stored failure keeps its original message. */
    private fun errorStatus(error: Throwable): SourceStatus.Error = when (error) {
        is StoredFailure -> SourceStatus.Error(error.failure.message, error.failure.code, error.failure.detail)
        else -> SourceStatus.Error.of(error)
    }

    /**
     * Cached data only (no network), for placeholders and snapshots. Prices cached for a different region than the
     * current setting count as absent.
     */
    fun cached(): SunWattData {
        val settings = store.loadSettings()
        val unavailable = SourceStatus.Error.of(SunWattError.Unavailable)
        var data = SunWattData(solar = null, prices = null, solarStatus = unavailable, priceStatus = unavailable)
            .withPlants(settings.plants.map { cachedPlant(it, settings) })
        val prices = store.loadPrices()
        if (prices != null && prices.region == settings.priceRegion) {
            data = data.copy(prices = prices, priceStatus = SourceStatus.Stale(prices.fetchedAt))
        }
        return data
    }

    /**
     * Stored data for a view that never loads (Android widgets: the refresh worker loads and stores, DECISIONS D11).
     * A source fetched less than [freshFor] ago counts as fresh (whether the last load failed is not known here);
     * older ones stay stale. On Apple the widget gets the load result itself, so this has no Swift twin.
     */
    fun stored(freshFor: Duration): SunWattData {
        val now = now()
        fun fresh(status: SourceStatus): SourceStatus {
            val since = (status as? SourceStatus.Stale)?.since ?: return status
            val age = java.time.Duration.between(since, now).toKotlinDuration()
            return if (age >= Duration.ZERO && age < freshFor) SourceStatus.Fresh else status
        }
        val data = cached()
        return data.copy(priceStatus = fresh(data.priceStatus)).withPlants(data.allPlants.map { it.copy(status = fresh(it.status)) })
    }

    private fun cachedPlant(plant: SolarPlant, all: SunWattSettings): PlantSolarData {
        val settings = all.focused(plant)
        var solar: SolarSnapshot? = null
        var status: SourceStatus = SourceStatus.Error.of(SunWattError.Unavailable)
        var linkExpired = false
        val stored = cachedSolar(settings, plant.id)
        if (makeSolar(settings) == null) {
            status = unconfiguredStatus(settings)
        } else if (stored != null) {
            solar = stored
            status = SourceStatus.Stale(stored.fetchedAt)
        }
        if (isKioskLinkExpired(settings, now())) {
            solar = null
            status = SourceStatus.Error.of(SunWattError.KioskLinkRejected)
            linkExpired = true
        }
        return PlantSolarData(plant.id, plant.displayName(solar?.stationName), solar, status, linkExpired)
    }

    /** True when the FusionSolar link in [settings] has been rejected for at least [KIOSK_EXPIRED_AFTER]. */
    internal fun isKioskLinkExpired(settings: SunWattSettings, now: Instant): Boolean {
        val endpoint = SolarAccount.fromSettings(settings)?.kioskEndpoint ?: return false
        val since = store.kioskRejectedSince(endpoint.cacheID) ?: return false
        return java.time.Duration.between(since, now).toKotlinDuration() >= KIOSK_EXPIRED_AFTER
    }

    /** The stored solar snapshot of the plant, only when it was fetched from the solar account now in [settings]. */
    private fun cachedSolar(settings: SunWattSettings, plantID: String): SolarSnapshot? {
        val id = endpointID(settings) ?: return null
        val solar = store.loadSolar(plantID) ?: return null
        return solar.takeIf { it.endpointID == id }
    }

    /**
     * The stored snapshot when the provider limits how often it may be fetched ([SolarProvider.minimumFetchInterval])
     * and it is younger than that limit, from the current Madrid day.
     */
    private fun recentSolar(settings: SunWattSettings, plantID: String, now: Instant): SolarSnapshot? {
        val provider = SolarAccount.fromSettings(settings)?.provider ?: return null
        if (provider.minimumFetchInterval <= Duration.ZERO) return null
        val solar = cachedSolar(settings, plantID) ?: return null
        // A live snapshot is new at every inverter read; the limit counts from the last cloud fetch.
        val cloudTime = (if (solar.isLive == true) solar.cloudFetchedAt else solar.fetchedAt) ?: return null
        val age = java.time.Duration.between(cloudTime, now).toKotlinDuration()
        if (age < Duration.ZERO || age >= provider.minimumFetchInterval || !sameMadridDay(cloudTime, now)) return null
        return solar
    }

    /**
     * The last failure of a rate-limited provider (SolarEdge) when it is younger than its fetch interval: refreshes in
     * between show it (with cached data as stale) instead of spending more of the daily requests.
     */
    private fun recentSolarFailure(settings: SunWattSettings, now: Instant): Throwable? {
        val account = SolarAccount.fromSettings(settings) ?: return null
        if (account.provider.minimumFetchInterval <= Duration.ZERO) return null
        val failure = store.solarFailure(account.cacheID) ?: return null
        val age = java.time.Duration.between(failure.at, now).toKotlinDuration()
        if (age < Duration.ZERO || age >= account.provider.minimumFetchInterval) return null
        return StoredFailure(failure)
    }

    /**
     * Stored data of every source in use (every plant and the prices), fetched less than [maxAge] ago; null when any
     * part is missing or older.
     */
    private fun recentCache(settings: SunWattSettings, maxAge: Duration, now: Instant): SunWattData? {
        fun age(fetchedAt: Instant) = java.time.Duration.between(fetchedAt, now).toKotlinDuration()
        val plants = mutableListOf<PlantSolarData>()
        for (plant in settings.plants) {
            val focused = settings.focused(plant)
            if (makeSolar(focused) == null) {
                plants += PlantSolarData(plant.id, plant.displayName(null), null, unconfiguredStatus(focused))
                continue
            }
            // Solar the provider would not fetch again yet (SolarEdge: 20 min) counts as recent too; otherwise every
            // widget of a SolarEdge user would miss the shared window and fetch prices at the same time (HTTP 429).
            // A live snapshot (at home) only counts for the window itself, so widgets keep reading the inverter.
            val interval = SolarAccount.fromSettings(focused)?.provider?.minimumFetchInterval ?: Duration.ZERO
            val solar = cachedSolar(focused, plant.id) ?: return null
            val solarMaxAge = if (solar.isLive == true) maxAge else maxOf(maxAge, interval)
            val solarAge = age(solar.fetchedAt)
            if (solarAge < Duration.ZERO || solarAge >= solarMaxAge || !sameMadridDay(solar.fetchedAt, now)) return null
            plants += PlantSolarData(plant.id, plant.displayName(solar.stationName), solar, SourceStatus.Fresh)
        }
        val prices = store.loadPrices() ?: return null
        val priceAge = age(prices.fetchedAt)
        if (prices.region != settings.priceRegion || priceAge < Duration.ZERO || priceAge >= maxAge ||
            prices.points.none { now in it }
        ) {
            return null
        }
        return SunWattData(solar = null, prices = prices, solarStatus = SourceStatus.NotConfigured, priceStatus = SourceStatus.Fresh)
            .withPlants(plants)
    }

    private fun unconfiguredStatus(settings: SunWattSettings): SourceStatus =
        if (settings.hasSolarInput) SourceStatus.Error.of(settings.invalidSolarInputError) else SourceStatus.NotConfigured

    /** A failure recorded earlier, shown again with its original message. */
    internal class StoredFailure(val failure: SolarFailure) : Exception(failure.message)

    companion object {
        /** Each source gets this long (a widget refresh has about 30 s in all). */
        val DEADLINE: Duration = 15.seconds

        /**
         * Cache window for callers that fire together (widget updates, tiles, intents, automatic app refreshes). After a
         * refresh or a settings change every widget reloads at once; with this window only the first one fetches and the
         * others read what it stored (Energy-Charts answers HTTP 429 to parallel requests). Explicit refreshes pass zero.
         */
        val SHARED_CACHE_WINDOW: Duration = 120.seconds

        /**
         * How long Huawei must keep rejecting the kiosk link before SunWatt calls it expired. One rejection can be a
         * passing problem on Huawei's side; an expired link is rejected on every refresh.
         */
        val KIOSK_EXPIRED_AFTER: Duration = 30.minutes

        /** One network part of a load at a time in this process (see the class comment). */
        private val networkLock = Mutex()

        private val sharedNorthboundSessions: NorthboundSessionStore by lazy {
            com.ideatic.sunwatt.core.services.InMemoryNorthboundSessionStore()
        }

        fun defaultPrices(region: PriceRegion, http: HttpClient = OkHttpSunWattClient()): PriceDataSource = region.client(http)

        /** Identifier of the solar account in [settings]; null when it is not set up or not valid. */
        fun endpointID(settings: SunWattSettings): String? = SolarAccount.fromSettings(settings)?.cacheID

        /**
         * A live inverter reading without the kiosk part (kiosk unreachable): today's earlier curve, the plant name and
         * month/year totals come from the stored snapshot.
         */
        fun fillingIn(live: SolarSnapshot, cached: SolarSnapshot): SolarSnapshot {
            var result = live
            val firstLive = live.curve.firstOrNull()?.time ?: live.fetchedAt
            val earlier = cached.curve.filter { sameMadridDay(it.time, live.fetchedAt) && it.time < firstLive && it.kW != null }
            if (live.curve.count { it.kW != null } <= 1 && earlier.isNotEmpty()) {
                result = result.copy(curve = earlier + live.curve)
            }
            // SunSpec has no daily counter: the previous figure plus what the lifetime counter grew (never less than the
            // cloud's, which lags).
            val derived = LocalInverterSolarClient.dailyEnergy(live.cumulativeEnergyKWh, live.fetchedAt, cached)
            result = result.copy(
                stationName = result.stationName ?: cached.stationName,
                dailyEnergyKWh = listOfNotNull(live.dailyEnergyKWh, derived).maxOrNull(),
                cloudFetchedAt = live.cloudFetchedAt ?: (if (cached.isLive == true) cached.cloudFetchedAt else cached.fetchedAt),
            )
            if (result.home == null && cached.home != null && live.cloudFetchedAt == null) {
                // Inverter alone (cloud not asked): keep the cloud's consumption figures until the next cloud fetch.
                result = result.copy(home = cached.home)
            }
            val cachedDay = cached.fetchedAt.atZone(Constants.madrid)
            val liveDay = live.fetchedAt.atZone(Constants.madrid)
            if (YearMonth.from(cachedDay) == YearMonth.from(liveDay)) {
                result = result.copy(monthEnergyKWh = result.monthEnergyKWh ?: cached.monthEnergyKWh)
            }
            if (Year.from(cachedDay) == Year.from(liveDay)) {
                result = result.copy(yearEnergyKWh = result.yearEnergyKWh ?: cached.yearEnergyKWh)
            }
            return result
        }

        /**
         * A transient failure of the tomorrow request must not erase tomorrow prices fetched earlier: keeps points of
         * [old] (same region) that start after the new series and have not ended yet.
         */
        fun keepingFutureHours(new: PriceSeries, old: PriceSeries?, now: Instant): PriceSeries {
            if (old == null || old.region != new.region) return new
            val lastStart = new.points.lastOrNull()?.start ?: return new
            val extra = old.points.filter { it.start > lastStart && it.end > now }
            if (extra.isEmpty()) return new
            val extension = PriceSeries(points = extra, granularity = old.granularity, fetchedAt = new.fetchedAt, region = new.region)
            return PriceSeriesBuilding.merged(listOf(new, extension), new.fetchedAt).copy(sourceUpdatedAt = new.sourceUpdatedAt)
        }

        private fun sameMadridDay(a: Instant, b: Instant): Boolean =
            a.atZone(Constants.madrid).toLocalDate() == b.atZone(Constants.madrid).toLocalDate()

        /**
         * [reusing]: a recent snapshot returned instead of fetching; [failure]: a recent failure returned instead of
         * fetching. [live]: the inverter alone, tried first when one of them is used instead of the cloud.
         */
        private suspend fun fetchSolar(
            source: SolarDataSource?,
            reusing: SolarSnapshot?,
            failure: Throwable?,
            live: SolarDataSource?,
            deadline: Duration,
        ): Result<SolarSnapshot> {
            if (live != null && (reusing != null || failure != null)) {
                race(deadline) { live.fetch() }.onSuccess { return Result.success(it) }
            }
            if (reusing != null) return Result.success(reusing)
            if (failure != null) return Result.failure(failure)
            if (source == null) return Result.failure(SunWattError.NotConfigured)
            return race(deadline) { source.fetch() }
        }

        /**
         * The one deadline helper of the core. Runs [work]; gives up after [deadline] with [SunWattError.Network] and
         * cancels it. Returns as soon as either side finishes, even if [work] ignores cancellation. Every failure of
         * [work] is returned, not thrown, except cancellation: when the caller is cancelled, or [work] throws
         * `CancellationException` (a timeout inside it reads as [SunWattError.Network]), that exception is thrown.
         */
        suspend fun <T> race(deadline: Duration, work: suspend () -> T): Result<T> {
            val gate = CompletableDeferred<Result<T>>()
            // Same dispatcher as the caller, but not its job: [work] can be abandoned without waiting for it.
            val scope = CoroutineScope(currentCoroutineContext().minusKey(Job))
            val job = scope.launch {
                try {
                    gate.complete(Result.success(work()))
                } catch (e: TimeoutCancellationException) {
                    gate.complete(Result.failure(SunWattError.Network("timed out")))
                } catch (e: CancellationException) {
                    gate.complete(Result.failure(e))
                } catch (e: Exception) {
                    gate.complete(Result.failure(e))
                }
            }
            val timer = scope.launch {
                delay(deadline)
                gate.complete(Result.failure(SunWattError.Network("timed out")))
            }
            try {
                val result = gate.await()
                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                return result
            } finally {
                job.cancel()
                timer.cancel()
            }
        }
    }
}
