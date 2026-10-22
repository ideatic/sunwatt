package com.ideatic.sunwatt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import com.ideatic.sunwatt.R

/** "Add widget" for the widget guide: the launcher asks the user to place a widget of one size. */
object WidgetPinning {
    /** False on launchers that cannot pin widgets from an app (the guide then explains how to add one by hand). */
    fun canPin(context: Context): Boolean = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /** Asks the launcher to pin a [size] widget. True when the request was sent; the user still confirms placement. */
    fun pin(context: Context, size: WidgetSize): Boolean {
        val receiver = when (size) {
            WidgetSize.SMALL -> SunWattSmallWidgetReceiver::class.java
            WidgetSize.MEDIUM -> SunWattMediumWidgetReceiver::class.java
            WidgetSize.LARGE -> SunWattLargeWidgetReceiver::class.java
        }
        // Mutable: the launcher adds the new widget id to the intent.
        val pinned = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WidgetPinnedReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        confirmation.requested(SystemClock.elapsedRealtime())
        return AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, receiver), null, pinned)
    }

    private val confirmation = PinConfirmation()

    /**
     * Shows "Widget added" once for the last pin request. The launcher's success callback calls it; the MIUI launcher
     * never sends that callback, only the new widget's first update, so the widget receiver calls it too.
     */
    fun confirmPinned(context: Context) {
        if (!confirmation.takeConfirmation(SystemClock.elapsedRealtime())) return
        Toast.makeText(context, R.string.android_widget_added_to_your_home_screen, Toast.LENGTH_LONG).show()
    }
}

/** One confirmation per pin request, and only shortly after it (an update at boot or after an app update is not a pin). */
class PinConfirmation {
    @Volatile
    private var requestedAt: Long? = null

    fun requested(at: Long) {
        requestedAt = at
    }

    /** True once after [requested], when called within [WINDOW_MS]. Times are milliseconds of one monotonic clock. */
    @Synchronized
    fun takeConfirmation(at: Long): Boolean {
        val requested = requestedAt ?: return false
        requestedAt = null
        return at - requested <= WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 60_000L
    }
}
