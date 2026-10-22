package com.ideatic.sunwatt.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.wear.MainActivity
import com.ideatic.sunwatt.wear.WearApplication
import com.ideatic.sunwatt.wear.demo.DemoClock
import kotlinx.coroutines.CancellationException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Shared pieces of the two complications. */
object WearComplications {
    private val services = listOf(PriceComplicationService::class.java, SolarComplicationService::class.java)

    /** Asks the watch face to ask both complications for new data (after a refresh or new settings). */
    fun requestUpdate(context: Context) {
        for (service in services) {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, service)).requestUpdateAll()
        }
    }

    /**
     * The entry a complication shows: the stored data when it is recent, else a short fetch (complications have no room
     * for plant names, so several plants are added up). A failed load falls back to the stored data.
     */
    suspend fun loadEntry(application: WearApplication): SunWattEntry {
        val graph = application.graph
        val data = DemoClock.data(application) ?: try {
            graph.loader.load(deadline = LOAD_DEADLINE, maxCacheAge = SunWattLoader.SHARED_CACHE_WINDOW)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.loader.cached()
        }
        return graph.entry(data, Instant.now()).combinedPlants
    }

    /** Tapping a complication opens the watch app. */
    fun tapAction(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun text(value: String): PlainComplicationText = PlainComplicationText.Builder(value).build()

    private val LOAD_DEADLINE = 8.seconds
}
