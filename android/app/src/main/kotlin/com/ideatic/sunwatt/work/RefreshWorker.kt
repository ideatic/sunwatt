package com.ideatic.sunwatt.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.widget.WidgetRefresher
import kotlinx.coroutines.CancellationException

/**
 * Loads fresh data (cache window of [SunWattLoader.SHARED_CACHE_WINDOW], 15 s per source) and redraws widgets and
 * tiles. The loader keeps what it fetched in the store; a failure leaves the cached data, which the widgets show as stale.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as SunWattApplication).graph
        try {
            graph.loader.load(maxCacheAge = SunWattLoader.SHARED_CACHE_WINDOW)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The loader reports failures inside its data; anything else is unexpected, and the widgets still redraw.
        }
        WidgetRefresher.updateAll(applicationContext)
        // New data moves the time it turns stale: replace the pending redraw.
        if (WidgetWork.inUse(applicationContext)) WidgetWork.scheduleRedraw(applicationContext, replace = true)
        return Result.success()
    }
}

/** Redraws widgets and tiles from the store at an entry time (new hour, next plant). No network. */
class RedrawWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefresher.updateAll(applicationContext)
        if (WidgetWork.inUse(applicationContext)) WidgetWork.scheduleRedraw(applicationContext, replace = true)
        return Result.success()
    }
}
