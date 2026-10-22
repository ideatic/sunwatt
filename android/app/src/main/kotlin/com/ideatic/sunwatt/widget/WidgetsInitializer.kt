package com.ideatic.sunwatt.widget

import android.content.Context
import androidx.startup.Initializer
import com.ideatic.sunwatt.work.WidgetWork

/**
 * Runs at process start through App Startup (a manifest entry), so the widgets piece needs no line in `SunWattApplication`:
 * connects `WidgetUpdates` to the refresher and makes sure the refresh work exists when widgets or tiles are in use.
 * Only enqueues work; nothing here touches the network.
 */
class WidgetsInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        WidgetRefresher.install()
        WidgetWork.sync(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(androidx.work.WorkManagerInitializer::class.java)
}
