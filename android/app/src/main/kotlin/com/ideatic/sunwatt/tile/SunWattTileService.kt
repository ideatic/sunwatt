package com.ideatic.sunwatt.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ideatic.sunwatt.AppGraph
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.intents.ControlContent
import com.ideatic.sunwatt.core.intents.IntentDataSource
import com.ideatic.sunwatt.shortcuts.ContextAnswerStrings
import com.ideatic.sunwatt.widget.refreshIntent
import com.ideatic.sunwatt.work.WidgetWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile (the Control Center control of the Apple app): label = value, subtitle = level or today's energy.
 * Reads the cache first and asks the network only when the cache cannot answer ([IntentDataSource]). A tap opens the
 * app with `sunwatt://refresh`, like a widget tap.
 */
abstract class SunWattTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    protected abstract val tileName: String

    protected abstract val need: IntentDataSource.Need

    protected abstract fun lines(graph: AppGraph, data: com.ideatic.sunwatt.core.data.SunWattData, texts: TileTexts): TileLines

    override fun onTileAdded() {
        TileRegistry.set(this, tileName, true)
        WidgetWork.sync(this, refreshNow = true)
    }

    override fun onTileRemoved() {
        TileRegistry.set(this, tileName, false)
        WidgetWork.sync(this)
    }

    override fun onStartListening() {
        // A tile that is shown exists: this also counts a tile added before the registry did.
        TileRegistry.set(this, tileName, true)
        WidgetWork.sync(this)
        val graph = (application as SunWattApplication).graph
        val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        job = tileScope.launch {
            val data = try {
                graph.intentData.data(need)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.loader.cached()
            }
            val shown = lines(graph, data, TileTexts(ContextAnswerStrings(applicationContext)))
            withContext(Dispatchers.Main) { show(shown) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        job = null
    }

    // The Intent form only runs below API 34; from 34 on the PendingIntent form is used.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = refreshIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun show(lines: TileLines) {
        val tile = qsTile ?: return
        tile.label = lines.title
        tile.subtitle = lines.subtitle.ifEmpty { null }
        tile.icon = Icon.createWithResource(
            this,
            when (lines.icon) {
                TileIcon.BOLT -> R.drawable.widget_ic_bolt
                TileIcon.SUN -> R.drawable.widget_ic_sun
                TileIcon.MOON -> R.drawable.widget_ic_moon
            },
        )
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}

class PriceTileService : SunWattTileService() {
    override val tileName = "price"
    override val need = IntentDataSource.Need.PRICE

    override fun lines(graph: AppGraph, data: com.ideatic.sunwatt.core.data.SunWattData, texts: TileTexts): TileLines =
        texts.price(ControlContent.price(data, graph.loader.now()))
}

class SolarTileService : SunWattTileService() {
    override val tileName = "solar"
    override val need = IntentDataSource.Need.SOLAR

    override fun lines(graph: AppGraph, data: com.ideatic.sunwatt.core.data.SunWattData, texts: TileTexts): TileLines =
        texts.solar(ControlContent.solar(data, graph.loader.now()))
}
