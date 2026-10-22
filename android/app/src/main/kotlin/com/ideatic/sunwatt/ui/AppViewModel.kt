package com.ideatic.sunwatt.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ideatic.sunwatt.AppGraph
import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.inverter.InverterFinder
import com.ideatic.sunwatt.core.inverter.LocalInverterSolarClient
import com.ideatic.sunwatt.core.inverter.ModbusTransportFactory
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattLink
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.focused
import com.ideatic.sunwatt.core.models.hasSolarInput
import com.ideatic.sunwatt.core.models.plant
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.core.models.primaryPlant
import com.ideatic.sunwatt.core.models.updatingPlant
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.NorthboundCredentials
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.data.SettingsRepository
import com.ideatic.sunwatt.ui.model.AndroidLocalNetworks
import com.ideatic.sunwatt.ui.model.AppUiState
import com.ideatic.sunwatt.ui.model.DemoSetup
import com.ideatic.sunwatt.ui.model.toUiState
import com.ideatic.sunwatt.ui.model.WidgetUpdates
import com.ideatic.sunwatt.ui.model.countInstalledWidgets
import com.ideatic.sunwatt.ui.model.localInverterUnitID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The app's view model, available to every screen through `LocalAppViewModel.current`. */
val LocalAppViewModel = staticCompositionLocalOf<AppViewModel> { error("AppViewModel not provided") }

/**
 * Everything the app screens read and change (port of the Swift `AppModel`). One instance per activity. State is the
 * immutable [AppUiState]; every change publishes a new one. Network and disk work runs on [io]; functions that need no
 * waiting return at once.
 */
class AppViewModel(
    private val store: SunWattStore,
    private val loader: SunWattLoader,
    private val settingsRepository: SettingsRepository,
    private val makeClient: (SolarAccount) -> SolarDataSource,
    private val makeInverterFinder: (LocalInverterKind) -> InverterFinder,
    /** Asks the widgets to redraw. */
    private val reloadWidgets: () -> Unit = {},
    /** Number of placed widgets. A failure means "unknown", not zero. */
    private val countWidgets: suspend () -> Int = { 0 },
    private val clock: () -> Instant = Instant::now,
    /** Transport for inverter reads (tests); null: each reader's own. */
    private val makeModbusTransport: ModbusTransportFactory? = null,
    private val http: HttpClient? = null,
    /** A fixed state with no network and no store (debug builds only). */
    val demo: DemoSetup? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    val isDemo: Boolean get() = demo != null

    private val _state = MutableStateFlow(demo?.toUiState() ?: AppUiState(now = clock()))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** The current settings (same as `state.value.settings`). */
    val settings: SunWattSettings get() = _state.value.settings

    /** The load in flight. A new refresh, or a change of region or account, cancels it. */
    private var loadJob: Job? = null

    /** Counts started loads; a finished load applies its result only if no newer load (or invalidation) happened. */
    private var loadGeneration = 0
    private var pendingRefresh: Job? = null
    private var saveJob: Job? = null
    private var lastRefresh: Instant? = null

    init {
        if (demo == null) viewModelScope.launch { loadInitial() }
    }

    /** Reads the saved settings and the cached data, so the dashboard shows something at once. */
    private suspend fun loadInitial() {
        withContext(io) { settingsRepository.load() }
        // Links saved before the date was recorded: count from now (the expiry notice then comes a bit late at worst,
        // never early).
        val loaded = settingsRepository.state.value ?: SunWattSettings()
        if (loaded.kioskLinkSavedAt == null && KioskEndpoint.parse(loaded.kioskURL) != null) {
            settingsRepository.update { it.copy(kioskLinkSavedAt = clock()) }
        }
        val saved = settingsRepository.state.value ?: loaded
        val cached = withContext(io) { loader.cached() }
        _state.update {
            it.copy(
                isReady = true, settings = saved, now = clock(), showsOnboarding = !saved.onboardingCompleted,
                data = cached.takeIf { c -> c.solar != null || c.prices != null },
            )
        }
    }

    /** Suspends until the saved settings are read. */
    suspend fun awaitReady() {
        _state.first { it.isReady }
    }

    // region Data

    /**
     * Loads both sources (saving to the store), then asks the widgets to reload. A refresh that starts while another
     * load runs cancels that load and takes over, so the result always belongs to the latest settings. [maxCacheAge] is
     * passed to `SunWattLoader.load`: zero (default) always fetches.
     */
    suspend fun refresh(maxCacheAge: Duration = Duration.ZERO) {
        if (isDemo) return
        awaitReady()
        // The loader reads the settings from the store: let a pending save finish first.
        saveJob?.join()
        loadJob?.cancel()
        loadGeneration += 1
        val generation = loadGeneration
        _state.update { it.copy(isLoading = true) }
        val job = viewModelScope.async(io) { loader.load(maxCacheAge = maxCacheAge) }
        loadJob = job
        val loaded = try {
            job.await()
        } catch (e: CancellationException) {
            // The caller was cancelled: stop the load too. Otherwise a newer refresh cancelled this load.
            if (!currentCoroutineContext().isActive) {
                job.cancel()
                throw e
            }
            null
        }
        // Superseded by a newer refresh or a settings change: that one owns `isLoading` and the result.
        if (generation != loadGeneration) return
        loadJob = null
        _state.update { it.copy(isLoading = false) }
        if (loaded == null) return
        val date = clock()
        _state.update { it.copy(data = loaded, now = date, hasLoaded = true) }
        lastRefresh = date
        reloadWidgets()
        refreshWidgetCount()
    }

    /** Drops the load in flight (its result would belong to old settings). */
    private fun invalidateLoad() {
        loadJob?.cancel()
        loadJob = null
        loadGeneration += 1
        _state.update { it.copy(isLoading = false) }
    }

    /** First load: cached data shows at once, then the refresh runs. Data the widgets stored a moment ago is reused. */
    suspend fun start() {
        awaitReady()
        refreshWidgetCount()
        if (!isDemo && !_state.value.showsOnboarding) refresh(SunWattLoader.SHARED_CACHE_WINDOW)
    }

    /**
     * Called when the app comes to the front: re-checks the widgets, refreshes data older than 5 minutes. A load already
     * running (a widget tap, [open]) is left alone: it fetches newer data than this one would.
     */
    suspend fun onResume() {
        awaitReady()
        refreshWidgetCount()
        if (isDemo || _state.value.showsOnboarding || _state.value.isLoading) return
        val last = lastRefresh
        if (last != null && clock().epochSecond - last.epochSecond < RESUME_REFRESH_AFTER_SECONDS) return
        refresh(SunWattLoader.SHARED_CACHE_WINDOW)
    }

    /**
     * Handles a link the app was opened with. A widget tap (`sunwatt://refresh`) is an explicit refresh: it always
     * fetches (no cache window). Rate limits still apply in the loader (`SolarProvider.minimumFetchInterval`).
     */
    suspend fun open(link: SunWattLink) {
        when (link) {
            SunWattLink.REFRESH -> {
                awaitReady()
                if (_state.value.showsOnboarding) return
                refresh()
            }
        }
    }

    /**
     * Keeps the dashboard on the current time while it is on screen: at each quarter hour (the shortest price slot) it
     * moves `now` forward, so the current price changes with the hour, and it reloads data older than the widget
     * refresh interval (new solar points, the next day's prices after midnight). Runs until cancelled.
     */
    suspend fun runClock() {
        if (isDemo) return
        while (true) {
            val date = clock()
            val next = nextQuarterHour(date)
            delay((next.toEpochMilli() - date.toEpochMilli()).milliseconds + 1.seconds)
            tick(clock())
        }
    }

    /** One clock step. Internal for tests. */
    internal suspend fun tick(date: Instant) {
        if (isDemo) return
        _state.update { it.copy(now = date) }
        if (_state.value.showsOnboarding) return
        val last = lastRefresh
        if (last != null && date.epochSecond - last.epochSecond < Constants.refreshInterval.inWholeSeconds) return
        refresh(SunWattLoader.SHARED_CACHE_WINDOW)
    }

    /** Reads the widgets the user has placed. A failure means "unknown", not zero. */
    suspend fun refreshWidgetCount() {
        if (isDemo) return
        val count = try {
            withContext(io) { countWidgets() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _state.update { it.copy(widgetCount = count) }
    }

    // endregion

    // region Settings

    /**
     * Applies [change], saves it (the settings repository publishes it), asks the widgets to redraw and, after a pause,
     * loads the data again when the change needs it. Does nothing when nothing changed.
     */
    fun updateSettings(change: (SunWattSettings) -> SunWattSettings) {
        val old = _state.value.settings
        var new = change(old)
        // A new kiosk link (any plant): its year starts now, and a closed expiry notice applies to the old link only.
        for (plant in new.plants) {
            val before = old.plant(plant.id)
            if (plant.kioskURL == (before?.kioskURL ?: "")) continue
            val savedAt = if (KioskEndpoint.parse(plant.kioskURL) != null) clock() else null
            new = new.updatingPlant(plant.copy(kioskLinkSavedAt = savedAt, kioskExpiryNoticeDismissed = false))
        }
        if (new == old) return
        _state.update { it.copy(settings = new) }
        if (demo != null) return
        val regionChanged = new.priceRegion != old.priceRegion
        // Another provider, another valid account, or input appearing or disappearing (the solar status changes
        // between "not set up" and "not valid"). Edits that stay invalid fetch nothing.
        val solarChanged = solarFetchKeys(new) != solarFetchKeys(old)
        val tomorrowChanged = new.showTomorrowPrices != old.showTomorrowPrices
        val needsFetch = regionChanged || solarChanged || tomorrowChanged
        if (regionChanged || solarChanged) invalidateLoad()
        val previousSave = saveJob
        saveJob = viewModelScope.launch {
            previousSave?.join()
            withContext(io) { settingsRepository.update { new } }
            // When data must be fetched, the widgets reload once the new data is stored (end of `refresh`): reloading
            // them now would make every widget fetch the same data at the same time.
            if (!needsFetch) reloadWidgets()
            if (regionChanged) {
                // Cached prices of another region do not apply: show placeholders until the new region loads.
                val cached = withContext(io) { loader.cached() }
                _state.update {
                    it.copy(data = cached.takeIf { c -> c.solar != null || c.prices != null }, hasLoaded = false)
                }
            }
        }
        if (needsFetch) scheduleRefresh()
    }

    /**
     * What decides whether a settings change needs a solar fetch, per plant: its id, provider, valid account and whether
     * anything was typed. Names and edits that stay invalid change nothing.
     */
    private fun solarFetchKeys(settings: SunWattSettings): List<String> = settings.plants.map { plant ->
        val account = SolarAccount.fromPlant(plant)?.let { "${it.cacheID}|${it.localInverter}" } ?: "-"
        "${plant.id}|${plant.provider.rawValue}|$account|${settings.focused(plant).hasSolarInput}"
    }

    /** Waits for the user to stop changing settings (Energy-Charts rate-limits quick repeated requests). */
    private fun scheduleRefresh() {
        pendingRefresh?.cancel()
        pendingRefresh = viewModelScope.launch {
            delay(REFRESH_AFTER_EDIT)
            refresh()
        }
    }

    fun completeOnboarding() {
        updateSettings { it.copy(onboardingCompleted = true) }
        _state.update { it.copy(showsOnboarding = false) }
        if (!isDemo) viewModelScope.launch { refresh() }
    }

    /** The user closed the "your kiosk link may stop working" notice. */
    fun dismissKioskExpiryNotice() {
        updateSettings { it.copy(kioskExpiryNoticeDismissed = true) }
    }

    // endregion

    // region Plants

    /**
     * The settings with plant [id] in the place of the first one (`focused`): the solar editors read these, so one
     * editor serves every plant. The whole settings when there is no such plant.
     */
    fun settingsFocusedOn(id: String): SunWattSettings = settings.plant(id)?.let { settings.focused(it) } ?: settings

    /** Changes plant [id] through its focused settings and saves it in its place. */
    fun updatePlant(id: String, change: (SunWattSettings) -> SunWattSettings) {
        val plant = settings.plant(id) ?: return
        val changed = change(settings.focused(plant))
        val edited = changed.primaryPlant.copy(id = id)
        updateSettings { it.updatingPlant(edited) }
    }

    /** Adds an empty plant (FusionSolar until the user picks another provider) and returns its id. */
    fun addPlant(): String {
        val plant = SolarPlant()
        updateSettings { it.copy(additionalPlants = it.additionalPlants + plant) }
        return plant.id
    }

    /** Removes a further plant (the first one cannot be removed, only changed). */
    fun removePlant(id: String) {
        updateSettings { s -> s.copy(additionalPlants = s.additionalPlants.filterNot { it.id == id }) }
    }

    // endregion

    // region Connection tests

    /** Fetches the kiosk once with the typed URL (not necessarily saved) and returns the parsed snapshot. */
    suspend fun testKiosk(text: String): Result<SolarSnapshot> {
        val endpoint = KioskEndpoint.parse(text) ?: return Result.failure(SunWattError.InvalidKioskURL)
        if (isDemo) return demoSolar()
        return fetchOnce(SolarAccount.FusionSolar(endpoint))
    }

    /** Fetches SolarEdge or PVOutput once with the typed ID and key (not necessarily saved). */
    suspend fun testSolarAPI(provider: SolarProvider, siteID: String, apiKey: String): Result<SolarSnapshot> {
        val credentials = SolarAPICredentials.parse(siteID, apiKey)
        if (!provider.usesAPIKey || credentials == null) return Result.failure(SunWattError.InvalidSolarCredentials)
        if (isDemo) return demoSolar()
        val account = SolarAccount.api(provider, credentials) ?: return Result.failure(SunWattError.InvalidSolarCredentials)
        return test(account)
    }

    /** Logs in to a FusionSolar API account and fetches once with the typed values (not necessarily saved). */
    suspend fun testNorthbound(host: String, userName: String, systemCode: String): Result<SolarSnapshot> {
        val credentials = NorthboundCredentials.create(host, userName, systemCode)
            ?: return Result.failure(SunWattError.InvalidNorthboundAccount)
        if (isDemo) return demoSolar()
        return test(SolarAccount.FusionSolarAPI(credentials))
    }

    private suspend fun demoSolar(): Result<SolarSnapshot> {
        delay(400.milliseconds)
        val solar = SampleData.entry(SampleData.Scenario.DAY).data.solar
        return if (solar != null) Result.success(solar) else Result.failure(SunWattError.Unavailable)
    }

    private suspend fun fetchOnce(account: SolarAccount): Result<SolarSnapshot> = try {
        Result.success(makeClient(account).fetch())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * One fetch of [account]. Rate-limited providers: the test is the fetch. Its result is kept for the saved account,
     * then the dashboard refreshes without asking the provider again (`SolarProvider.minimumFetchInterval`).
     */
    private suspend fun test(account: SolarAccount): Result<SolarSnapshot> {
        pendingRefresh?.cancel()
        val result = fetchOnce(account)
        // Kept for the plant with this account (the cache ID leaves out the inverter address).
        val plant = settings.plants.firstOrNull { SolarAccount.fromPlant(it)?.cacheID == account.cacheID }
        if (plant != null) {
            withContext(io) {
                result.onSuccess { snapshot ->
                    store.saveSolar(snapshot.copy(endpointID = account.cacheID), plant.id)
                    store.clearSolarFailure(account.cacheID)
                }.onFailure { error ->
                    if (account.provider.minimumFetchInterval > Duration.ZERO) {
                        val detail = (error as? SunWattError)
                        store.noteSolarFailure(account.cacheID, clock(), error.message.orEmpty(), detail?.code, detail?.detail)
                    }
                }
            }
            refresh(SunWattLoader.SHARED_CACHE_WINDOW)
        }
        return result
    }

    // endregion

    // region Inverter on the home network (Huawei dongle, SunSpec or Fronius Solar API)

    /** What the selected provider of plant [plantId] reads on the home network. */
    fun localInverterKind(plantId: String = SolarPlant.PRIMARY_ID): LocalInverterKind =
        LocalInverterKind.of(settingsFocusedOn(plantId).solarProvider)

    /**
     * Reads the inverter at [address] once: the saved unit ID first, then the kind's usual ones; for SunSpec without a
     * port, 502 and then 1502 (SolarEdge); Fronius on port 80. On success the address to save (with the port that
     * answered) and the unit ID.
     */
    suspend fun testInverter(address: String, plantId: String = SolarPlant.PRIMARY_ID): Result<InverterFinder.Match> {
        val kind = localInverterKind(plantId)
        val endpoint = ModbusEndpoint.parse(address, defaultPort = kind.defaultPort)
            ?: return Result.failure(SunWattError.InvalidInverterAddress)
        if (isDemo) {
            delay(400.milliseconds)
            return Result.success(demoInverter(kind))
        }
        val ports = if (ModbusEndpoint.hasPort(address)) listOf(endpoint.port) else kind.ports
        val saved = settingsFocusedOn(plantId).localInverterUnitID(kind)
        val units = listOfNotNull(saved) + kind.unitIDs.filter { it != saved }
        return InverterFinder.identify(kind, endpoint.host, ports, units, makeModbusTransport, http)
    }

    /** "Find inverter": inverters of the selected provider's kind that answer on the home network. */
    suspend fun findInverters(plantId: String = SolarPlant.PRIMARY_ID): List<InverterFinder.Match> {
        val kind = localInverterKind(plantId)
        if (isDemo) {
            delay(800.milliseconds)
            return listOf(demoInverter(kind))
        }
        return makeInverterFinder(kind).find()
    }

    private fun demoInverter(kind: LocalInverterKind): InverterFinder.Match = when (kind) {
        LocalInverterKind.HUAWEI -> InverterFinder.Match(
            "192.168.1.50", 1, "SUN2000-6KTL-M1", LiveInverterReading(2.9, dailyEnergyKWh = 5.1, totalEnergyKWh = 44150.0),
        )
        LocalInverterKind.SUN_SPEC -> InverterFinder.Match(
            "192.168.1.40:1502", 1, "SolarEdge SE5000H", LiveInverterReading(2.9, totalEnergyKWh = 18420.0),
        )
        LocalInverterKind.FRONIUS -> InverterFinder.Match(
            "192.168.1.30", 1, "Fronius", LiveInverterReading(2.9, dailyEnergyKWh = 5.1, totalEnergyKWh = 21730.0),
        )
    }

    /**
     * While the dashboard is on screen and the inverter answers, re-reads it every [LIVE_INTERVAL] and updates the
     * figures and the curve (nothing is stored; widgets keep their own refresh). Runs until cancelled.
     */
    suspend fun runLive() {
        if (isDemo) return
        while (true) {
            delay(LIVE_INTERVAL)
            liveTick()
        }
    }

    /** One live read. Internal for tests. */
    internal suspend fun liveTick() {
        val before = _state.value
        if (!before.isReady || before.showsOnboarding || before.isLoading || before.data?.solarLinkExpired == true) return
        val account = SolarAccount.fromSettings(before.settings) ?: return
        val reader = account.localReader(makeModbusTransport, http) ?: return
        val reading = SunWattLoader.race(LIVE_DEADLINE) { reader.readLive() }.getOrNull() ?: return
        // The settings changed while the inverter was read: the reading may belong to another account.
        if (SolarAccount.fromSettings(_state.value.settings) != account) return
        val date = clock()
        _state.update { current ->
            val data = current.data
            val snapshot = LocalInverterSolarClient.merge(reading, data?.solar, date, account.provider.profile.keepsCloudHome)
                .copy(endpointID = data?.solar?.endpointID)
            val base = data ?: SunWattData(null, null, SourceStatus.Fresh, SourceStatus.Stale(date))
            current.copy(data = base.copy(solar = snapshot, solarStatus = SourceStatus.Fresh), now = date)
        }
    }

    // endregion

    companion object {
        /** A resume refreshes only when the last refresh is older than this. */
        const val RESUME_REFRESH_AFTER_SECONDS = 300L

        /** Pause after the last settings edit before the data is loaded again. */
        val REFRESH_AFTER_EDIT: Duration = 1200.milliseconds

        val LIVE_INTERVAL: Duration = 10.seconds
        private val LIVE_DEADLINE: Duration = 5.seconds

        /** The next :00, :15, :30 or :45 after [date]. Quarter hours are the same instant in every price time zone. */
        fun nextQuarterHour(date: Instant): Instant {
            val quarter = 15 * 60L
            return Instant.ofEpochSecond(Math.floorDiv(date.epochSecond, quarter) * quarter + quarter)
        }

        /** Factory for the app: the shared objects of [graph], the widget update hook, the device's networks. */
        fun factory(graph: AppGraph, context: Context, demo: DemoSetup? = null): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(
                    store = graph.store,
                    loader = graph.loader,
                    settingsRepository = graph.settings,
                    makeClient = { it.client(graph.http, graph.northboundSessions) },
                    makeInverterFinder = { InverterFinder(it, AndroidLocalNetworks(app), makeTransport = null, http = graph.http) },
                    reloadWidgets = { WidgetUpdates.requestUpdate(app) },
                    countWidgets = { countInstalledWidgets(app) },
                    http = graph.http,
                    demo = demo,
                ) as T
            }
        }
    }
}
