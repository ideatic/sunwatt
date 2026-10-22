package com.ideatic.sunwatt.wear.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.wear.WearApplication
import kotlinx.coroutines.CancellationException
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * Refreshes the watch data in the background so the complications are current (the Apple watch does it with the widget
 * timeline). The periodic work runs every [Constants.refreshInterval] (the WorkManager minimum); it reuses data stored
 * moments ago. A settings change from the phone runs [refreshNow], which always fetches.
 */
class WearRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as WearApplication).graph
        val force = inputData.getBoolean(KEY_FORCE, false)
        try {
            graph.refresh(if (force) Duration.ZERO else SunWattLoader.SHARED_CACHE_WINDOW)
        } catch (e: CancellationException) {
            throw e
        }
        return Result.success()
    }

    companion object {
        private const val KEY_FORCE = "force"
        private const val PERIODIC = "sunwatt-wear-refresh"
        private const val NOW = "sunwatt-wear-refresh-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Keeps one periodic refresh queued (a second call changes nothing). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WearRefreshWorker>(Constants.refreshInterval.toJavaDuration())
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** One refresh that fetches (no cache window), e.g. after the phone sent new settings. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WearRefreshWorker>()
                .setConstraints(network)
                .setInputData(workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
