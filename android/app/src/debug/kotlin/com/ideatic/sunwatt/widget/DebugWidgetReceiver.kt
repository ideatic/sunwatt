package com.ideatic.sunwatt.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Debug builds only: pins a widget (`--es size small|medium|large`), redraws all of them (`--ez redraw true`) or
 * returns the size options each launcher sent (`--ez sizes true`, shown in the broadcast result) from adb.
 */
@OptIn(DelicateCoroutinesApi::class)
class DebugWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("size")) {
            "small" -> WidgetPinning.pin(context, WidgetSize.SMALL)
            "medium" -> WidgetPinning.pin(context, WidgetSize.MEDIUM)
            "large" -> WidgetPinning.pin(context, WidgetSize.LARGE)
        }
        if (intent.getBooleanExtra("sizes", false)) {
            val manager = AppWidgetManager.getInstance(context)
            resultData = listOf(SunWattSmallWidgetReceiver::class.java, SunWattMediumWidgetReceiver::class.java, SunWattLargeWidgetReceiver::class.java)
                .flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
                .joinToString("; ") { id ->
                    val o = manager.getAppWidgetOptions(id)
                    val keys = listOf(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                    "$id: " + keys.joinToString(",") { o.getInt(it, -1).toString() }
                }
        }
        if (intent.getBooleanExtra("redraw", false)) {
            val pending = goAsync()
            kotlinx.coroutines.GlobalScope.launch {
                try {
                    WidgetRefresher.updateAll(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
