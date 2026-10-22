package com.ideatic.sunwatt.debug

import android.content.Context
import android.content.Intent
import com.ideatic.sunwatt.AppGraph
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.focused
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.ui.model.DemoSetup
import com.ideatic.sunwatt.ui.model.WidgetUpdates
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * Demo launch options (debug builds only; `android/app/DEMO.md`):
 *   --ez demo true                        show a scenario with sample data and no network
 *   --es scenario <name>                  see [DemoScenarios.scenarios] (default day)
 *   --es screen <dashboard|settings|onboarding|widgetGuide>
 *   --ei widgets <n>                      number of widgets "added"
 *   --ez seed true                        also write the scenario into the real store (widgets, tiles, watch)
 */
object DemoLaunch {
    fun from(intent: Intent?): DemoSetup? {
        if (intent == null || !intent.getBooleanExtra("demo", false)) return null
        val widgets = if (intent.hasExtra("widgets")) intent.getIntExtra("widgets", 0) else null
        return DemoScenarios.setup(intent.getStringExtra("scenario") ?: "day", intent.getStringExtra("screen"), widgets)
    }

    /** `--ez seed true`: replaces the stored settings, solar data and prices with the scenario, as if fetched just now. */
    fun seed(context: Context, graph: AppGraph, intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra("seed", false)) return
        val now = Instant.now()
        val seeded = DemoScenarios.seeded(intent.getStringExtra("scenario") ?: "day", now)
        runBlocking { graph.settings.update { seeded.settings } }
        val store = graph.store
        for (plant in seeded.settings.plants) {
            val snapshot = seeded.solar[plant.id]
            val account = SolarAccount.fromSettings(seeded.settings.focused(plant))
            if (snapshot != null && account != null) store.saveSolar(snapshot.copy(endpointID = account.cacheID), plant.id)
            else store.deleteSolar(plant.id)
        }
        seeded.prices?.let(store::savePrices)
        WidgetUpdates.requestUpdate(context)
    }
}
