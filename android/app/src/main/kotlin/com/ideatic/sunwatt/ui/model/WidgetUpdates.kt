package com.ideatic.sunwatt.ui.model

import android.content.Context

/** Asks the home screen widgets (and tiles) to redraw from the stored data. The widgets piece implements it. */
fun interface WidgetUpdater {
    fun requestUpdate(context: Context)
}

/**
 * The one call the app makes after data or settings changed. The default does nothing; the widgets piece sets
 * [updater] once at start-up (for example in `SunWattApplication.onCreate`).
 */
object WidgetUpdates {
    @Volatile
    var updater: WidgetUpdater = WidgetUpdater { }

    fun requestUpdate(context: Context) = updater.requestUpdate(context)
}
