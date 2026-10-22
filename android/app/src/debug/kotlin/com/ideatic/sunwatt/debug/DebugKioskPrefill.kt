package com.ideatic.sunwatt.debug

import android.content.Context
import androidx.startup.Initializer
import com.ideatic.sunwatt.SunWattApplication
import java.io.FileNotFoundException

/**
 * Debug builds only (DECISIONS D7, the Apple `DebugKioskPrefill`): when the kiosk link setting is empty, fills it from
 * the `kiosk.local` asset, which the debug build copies from the git-ignored `private/kiosk.local` (see
 * `app/build.gradle.kts`). Release builds contain neither the file nor this code. The value is never logged.
 * Runs at process start, before any screen or widget reads the settings.
 */
class DebugKioskPrefill : Initializer<Unit> {
    override fun create(context: Context) {
        val text = try {
            context.assets.open(ASSET).use { it.readBytes().decodeToString() }.trim()
        } catch (e: FileNotFoundException) {
            return
        }
        if (text.isEmpty()) return
        val store = (context.applicationContext as SunWattApplication).graph.store
        val settings = store.loadSettings()
        if (settings.kioskURL.isNotBlank()) return
        store.saveSettings(settings.copy(kioskURL = text))
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        const val ASSET = "kiosk.local"
    }
}
