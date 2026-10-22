package com.ideatic.sunwatt.ui.model

import android.appwidget.AppWidgetManager
import android.content.Context

/** Number of SunWatt widgets on the home screens: every instance of every widget provider of this package. */
fun countInstalledWidgets(context: Context): Int {
    val manager = AppWidgetManager.getInstance(context)
    return manager.getInstalledProvidersForPackage(context.packageName, null).sumOf { manager.getAppWidgetIds(it.provider).size }
}
