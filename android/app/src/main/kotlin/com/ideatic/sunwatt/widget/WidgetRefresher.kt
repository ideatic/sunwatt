package com.ideatic.sunwatt.widget

import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import com.ideatic.sunwatt.tile.PriceTileService
import com.ideatic.sunwatt.tile.SolarTileService
import com.ideatic.sunwatt.ui.model.WidgetUpdater
import com.ideatic.sunwatt.ui.model.WidgetUpdates
import com.ideatic.sunwatt.work.WidgetWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Redraws every widget and asks every tile to read again. Draws from the store only: no network. */
object WidgetRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        // One failing widget must not stop the others.
        for (widget in listOf(SunWattSmallWidget(), SunWattMediumWidget(), SunWattLargeWidget())) {
            try {
                widget.updateAll(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to show to the user: the next refresh draws it again.
            }
        }
        TileService.requestListeningState(app, android.content.ComponentName(app, PriceTileService::class.java))
        TileService.requestListeningState(app, android.content.ComponentName(app, SolarTileService::class.java))
    }

    /** Hooks the app's `WidgetUpdates.requestUpdate` to this refresher (called by [WidgetsInitializer]). */
    fun install() {
        WidgetUpdates.updater = WidgetUpdater { context ->
            val app = context.applicationContext
            scope.launch {
                updateAll(app)
                // Settings may have added or removed the last widget or plant: the work and the next redraw follow.
                WidgetWork.sync(app)
            }
        }
    }
}
