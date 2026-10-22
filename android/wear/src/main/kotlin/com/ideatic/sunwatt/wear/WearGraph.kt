package com.ideatic.sunwatt.wear

import android.content.Context
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.InMemoryNorthboundSessionStore
import com.ideatic.sunwatt.core.services.OkHttpSunWattClient
import com.ideatic.sunwatt.core.store.DirectoryFileStorage
import com.ideatic.sunwatt.core.store.SecretCipher
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.wear.complication.WearComplications
import com.ideatic.sunwatt.wear.demo.DemoClock
import com.ideatic.sunwatt.security.KeystoreSecretCipher
import com.ideatic.sunwatt.wear.sync.WearSettingsInbox
import com.ideatic.sunwatt.wear.work.WearRefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration

/**
 * The watch's shared objects, one per process (the app, the complications and the refresh worker share them). The watch
 * has its own store, its own keystore key and its own FusionSolar API session (DECISIONS D36); it fetches over its own
 * network, with the settings the phone sent.
 */
class WearGraph(context: Context) {
    private companion object {
        /** Keystore alias of the watch's own key; the stored secrets stay readable only with this exact name. */
        const val WATCH_KEY_ALIAS = "sunwatt.wear.secrets"
    }


    private val appContext = context.applicationContext

    val http: HttpClient by lazy { OkHttpSunWattClient() }

    val cipher: SecretCipher by lazy { KeystoreSecretCipher(alias = WATCH_KEY_ALIAS) }

    val store: SunWattStore by lazy { SunWattStore(DirectoryFileStorage(appContext.filesDir), cipher) }

    /**
     * The FusionSolar API session lives in this process only: after a restart of the process the watch logs in again.
     * That costs one login per process start, under the 10-minute fetch interval of the provider (the loader keeps it).
     */
    private val northboundSessions = InMemoryNorthboundSessionStore()

    /**
     * The inverter on the home network is not read on the watch (Apple does not either, DECISIONS D30): the Modbus
     * addresses are dropped before the account is built, and a Fronius plant (home network only) fails as unreachable
     * because the watch app allows no plain HTTP.
     */
    val loader: SunWattLoader by lazy {
        SunWattLoader(
            store,
            makeSolar = { settings ->
                SolarAccount.fromSettings(settings.withoutModbusHosts())?.client(http, northboundSessions)
            },
            makeLiveSolar = { null },
            makePrices = { region -> SunWattLoader.defaultPrices(region, http) },
        )
    }

    /** Settings from the phone. A change refreshes the data at once (explicit fetch, no cache window). */
    val inbox: WearSettingsInbox by lazy {
        WearSettingsInbox(store, afterSave = { WearComplications.requestUpdate(appContext) }).also {
            it.onSettingsChange { WearRefreshWorker.refreshNow(appContext) }
        }
    }

    private val _data = MutableStateFlow<SunWattData?>(null)

    /** The data the pages show; null until the stored data has been read. */
    val data: StateFlow<SunWattData?> = _data.asStateFlow()

    /** Reads the stored data (no network) into [data] when nothing is there yet. */
    suspend fun loadStored() {
        if (_data.value != null) return
        DemoClock.data(appContext)?.let { _data.value = it; return }
        val stored = withContext(Dispatchers.IO) { loader.cached() }
        if (_data.value == null) _data.value = stored
    }

    /**
     * Fetches (or reuses data stored less than [maxCacheAge] ago, see [SunWattLoader.load]) and shows the result. Never
     * throws for a failed source; cancellation passes through.
     */
    suspend fun refresh(maxCacheAge: Duration = SunWattLoader.SHARED_CACHE_WINDOW): SunWattData {
        DemoClock.data(appContext)?.let { _data.value = it; return it }
        val fresh = withContext(Dispatchers.IO) { loader.load(maxCacheAge = maxCacheAge) }
        _data.value = fresh
        WearComplications.requestUpdate(appContext)
        return fresh
    }

    /** The entry the pages and complications show at [now]; a debug build can pin the clock to the sample day. */
    fun entry(data: SunWattData, now: Instant): SunWattEntry = SunWattEntry(DemoClock.now(appContext) ?: now, data)

    private fun SunWattSettings.withoutModbusHosts(): SunWattSettings = copy(huaweiLocalHost = "", sunSpecHost = "")
}
