package com.ideatic.sunwatt.wear.sync

import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.store.SunWattStore

/**
 * What the phone sends to the watch over the Wearable Data Layer (port of the Swift `WatchSettingsSync`): one data item at
 * [PATH] with the encoded [SunWattSettings], secrets included (they travel over the encrypted channel between the paired
 * devices and are encrypted again with the watch's Keystore when saved). The phone app has the same constants in
 * `wearsync/WearSettingsSender.kt`; change both together.
 */
object WearSettingsSync {
    const val PATH = "/sunwatt/settings"
    const val SETTINGS_KEY = "sunwatt.settings"
    const val SENT_AT_KEY = "sunwatt.sentAt"

    /** The settings as the bytes of the data item. */
    fun encode(settings: SunWattSettings): ByteArray =
        SunWattJson.encodeToString(SunWattSettings.serializer(), settings).toByteArray(Charsets.UTF_8)

    /** Settings in received [bytes]; null when absent or not decodable. */
    fun decode(bytes: ByteArray?): SunWattSettings? {
        bytes ?: return null
        return try {
            SunWattJson.decodeFromString(SunWattSettings.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Watch side, free of Android types so JVM tests reach it: saves received settings into the store, runs [afterSave]
 * (complication reload) and tells the listener so it can refresh. A change that arrives while nobody listens yet (the
 * service gets the stored item before the app registers) is kept and delivered when the listener registers.
 */
class WearSettingsInbox(
    private val store: SunWattStore,
    private val afterSave: () -> Unit = {},
) {
    private val lock = Any()
    private var handler: (() -> Unit)? = null
    private var changePending = false

    /** Called (on an arbitrary thread) after new settings were saved. When a change is already pending, the handler runs once right away. */
    fun onSettingsChange(handler: () -> Unit) {
        val pending = synchronized(lock) {
            this.handler = handler
            changePending.also { changePending = false }
        }
        if (pending) handler()
    }

    /** Saves the settings in [bytes]. Returns true when the stored settings changed. */
    fun apply(bytes: ByteArray?): Boolean {
        val settings = WearSettingsSync.decode(bytes) ?: return false
        if (settings == store.loadSettings()) return false
        store.saveSettings(settings)
        afterSave()
        val callback = synchronized(lock) {
            handler.also { if (it == null) changePending = true }
        }
        callback?.invoke()
        return true
    }
}
