package com.ideatic.sunwatt.wearsync

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.models.SunWattSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Sends the settings to the Wear OS app (port of the Swift `WatchSettingsSync`, phone side). One data item at [PATH] holds
 * the encoded [SunWattSettings], kiosk link and API keys included: the Wearable Data Layer carries it encrypted between
 * the paired devices, and the watch encrypts it again with its own Keystore key when it saves it. The watch app reads the
 * item in `WearSettingsSync` (module `:wear`); the two sets of constants must stay equal.
 *
 * It sends when the settings change and when a watch with the app appears (the Data Layer keeps the last item for a watch
 * that pairs later). Nothing is sent, and no secret is read, while no watch with the app is paired.
 */
object WearSettingsSender {
    const val PATH = "/sunwatt/settings"
    const val SETTINGS_KEY = "sunwatt.settings"
    const val SENT_AT_KEY = "sunwatt.sentAt"

    /** The capability the watch app declares (`wear_capabilities.xml`). */
    const val WATCH_CAPABILITY = "sunwatt_watch"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** Starts the sync once per process, off the main thread. Safe to call from `Application.onCreate`. */
    fun start(application: SunWattApplication) {
        if (started) return
        started = true
        scope.launch {
            try {
                val settings = application.graph.settings
                settings.load()
                val client = Wearable.getDataClient(application)
                settingsToSend(settings.state.filterNotNull(), watchPresent(application)).collect { current ->
                    val request = PutDataMapRequest.create(PATH).apply {
                        dataMap.putByteArray(SETTINGS_KEY, encode(current))
                        // The Data Layer ignores an item equal to the stored one; the time makes a re-send reach a new watch.
                        dataMap.putLong(SENT_AT_KEY, System.currentTimeMillis())
                    }.asPutDataRequest().setUrgent()
                    client.putDataItem(request).await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No Google Play services (or no Wearable API): there is no watch to tell.
            }
        }
    }

    /** The settings as the bytes of the data item. */
    fun encode(settings: SunWattSettings): ByteArray =
        SunWattJson.encodeToString(SunWattSettings.serializer(), settings).toByteArray(Charsets.UTF_8)

    /**
     * The settings to send: each change of [settings] while a watch is present, and the current ones again when a watch
     * appears. Nothing while no watch is.
     */
    fun settingsToSend(settings: Flow<SunWattSettings>, watchPresent: Flow<Boolean>): Flow<SunWattSettings> =
        combine(settings, watchPresent.distinctUntilChanged()) { current, present -> if (present) current else null }
            .filterNotNull()

    /** True while at least one paired watch has the SunWatt app. */
    private fun watchPresent(context: Context): Flow<Boolean> = callbackFlow {
        val client = Wearable.getCapabilityClient(context)
        val listener = CapabilityClient.OnCapabilityChangedListener { info: CapabilityInfo -> trySend(info.nodes.isNotEmpty()) }
        client.addListener(listener, WATCH_CAPABILITY)
        trySend(client.getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_ALL).await().nodes.isNotEmpty())
        awaitClose { client.removeListener(listener) }
    }
}
