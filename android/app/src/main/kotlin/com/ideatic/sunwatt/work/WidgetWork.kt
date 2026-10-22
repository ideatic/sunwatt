package com.ideatic.sunwatt.work

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.tile.TileRegistry
import com.ideatic.sunwatt.widget.SunWattLargeWidgetReceiver
import com.ideatic.sunwatt.widget.SunWattMediumWidgetReceiver
import com.ideatic.sunwatt.widget.SunWattSmallWidgetReceiver
import java.util.concurrent.TimeUnit
import kotlin.time.toJavaDuration

/**
 * The background work of widgets and tiles:
 *
 * - [REFRESH]: periodic, every [Constants.refreshInterval] (15 minutes, the WorkManager minimum), needs a network;
 *   loads data and redraws everything. The system may delay it (Doze); the widgets then show the stale state.
 * - [REDRAW]: one-time, at the next hour (or quarter hour with several plants), or earlier when the solar data turns
 *   stale; redraws from the store, no network.
 * - [REFRESH_NOW]: one-time refresh when the first widget or tile appears, so data shows quickly.
 *
 * All of it exists only while a widget or a tile is in use.
 */
object WidgetWork {
    const val REFRESH = "sunwatt-refresh"
    const val REDRAW = "sunwatt-redraw"
    const val REFRESH_NOW = "sunwatt-refresh-now"

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Widgets on a home screen, every size. */
    fun widgetCount(context: Context): Int {
        val manager = AppWidgetManager.getInstance(context)
        return listOf(SunWattSmallWidgetReceiver::class.java, SunWattMediumWidgetReceiver::class.java, SunWattLargeWidgetReceiver::class.java)
            .sumOf { manager.getAppWidgetIds(ComponentName(context, it)).size }
    }

    fun inUse(context: Context): Boolean = widgetCount(context) > 0 || TileRegistry.count(context) > 0

    /** Starts the work when a widget or tile is in use, stops it when none is. Cheap; safe to call at any time. */
    fun sync(context: Context, refreshNow: Boolean = false) {
        val app = context.applicationContext
        val manager = WorkManager.getInstance(app)
        if (!inUse(app)) {
            manager.cancelUniqueWork(REFRESH)
            manager.cancelUniqueWork(REDRAW)
            manager.cancelUniqueWork(REFRESH_NOW)
            return
        }
        manager.enqueueUniquePeriodicWork(
            REFRESH, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(Constants.refreshInterval.inWholeMinutes, TimeUnit.MINUTES)
                .setConstraints(needsNetwork).build(),
        )
        scheduleRedraw(app, replace = false)
        if (refreshNow) {
            manager.enqueueUniqueWork(REFRESH_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(needsNetwork).build())
        }
    }

    /** Schedules the next redraw. [replace]: the worker itself, after it ran, asks for the following one. */
    fun scheduleRedraw(context: Context, replace: Boolean) {
        val app = context.applicationContext
        val graph = (app as SunWattApplication).graph
        val now = graph.loader.now()
        val rotating = graph.store.loadSettings().plants.size > 1
        val staleAt = graph.loader.latest().allPlants
            .mapNotNull { plant -> plant.solar?.fetchedAt?.plus(Constants.staleAfter.toJavaDuration()) }
            .filter { it.isAfter(now) }.minOrNull()
        val delay = RedrawSchedule.next(now, rotating, staleAt).toEpochMilli() - now.toEpochMilli()
        WorkManager.getInstance(app).enqueueUniqueWork(
            REDRAW,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RedrawWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}
