package com.ideatic.sunwatt.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.models.PriceWindow
import com.ideatic.sunwatt.core.models.WidgetContent
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.SunWattTheme
import com.ideatic.sunwatt.work.WidgetWork
import kotlinx.coroutines.launch

/** A plant the widget can be pinned to: [id] is what the widget stores, [name] what the user reads. */
private data class PlantOption(val id: String, val name: String)

/**
 * Settings of one widget (the Swift `SunWattIntent`): what it shows, the price window and, with several plants, which
 * plant. Opens when a widget is added and again from the launcher's "reconfigure" (the widget is `reconfigurable`).
 * Closing it with the back button keeps the widget with its settings (`configuration_optional`).
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // Back out without saving: the system removes a widget that was never configured, unless it is configuration-optional.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        val graph = (application as SunWattApplication).graph
        val store = WidgetConfigStore(this)
        val initial = store.load(appWidgetId)
        val plants = graph.store.loadSettings().let { settings ->
            settings.plants.map { PlantOption(it.id, it.displayName(graph.store.loadSolar(it.id)?.stationName)) }
        }
        setContent {
            SunWattTheme {
                AppBackground {
                    ConfigScreen(initial, plants) { config ->
                        store.save(appWidgetId, config)
                        lifecycleScope.launch {
                            WidgetRefresher.updateAll(applicationContext)
                            WidgetWork.sync(applicationContext, refreshNow = true)
                            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(initial: WidgetConfig, plants: List<PlantOption>, onDone: (WidgetConfig) -> Unit) {
    var content by remember { mutableStateOf(initial.content) }
    var window by remember { mutableStateOf(initial.window) }
    var plant by remember { mutableStateOf(initial.plantRaw) }
    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.widgetext_sunwatt), style = MaterialTheme.typography.headlineSmall)
        Section(stringResource(R.string.widgetext_content)) {
            Option(stringResource(R.string.widgetext_solar_and_price), content == WidgetContent.BOTH) { content = WidgetContent.BOTH }
            Option(stringResource(R.string.widgetext_solar_only), content == WidgetContent.SOLAR_ONLY) { content = WidgetContent.SOLAR_ONLY }
            Option(stringResource(R.string.widgetext_price_only), content == WidgetContent.PRICE_ONLY) { content = WidgetContent.PRICE_ONLY }
        }
        if (content != WidgetContent.SOLAR_ONLY) {
            Section(stringResource(R.string.widgetext_price_window)) {
                Option(stringResource(R.string.widgetext_whole_day), window == PriceWindow.WHOLE_DAY) { window = PriceWindow.WHOLE_DAY }
                Option(stringResource(R.string.widgetext_6_h_before_12_h_after), window == PriceWindow.AROUND) { window = PriceWindow.AROUND }
            }
        }
        // The plant only matters with several plants; with one, the choice would do nothing.
        if (plants.size > 1 && content != WidgetContent.PRICE_ONLY) {
            Section(stringResource(R.string.widgetext_solar_plant)) {
                Option(stringResource(R.string.widgetext_all_plants_in_turn), plant == WidgetConfig.ROTATE) { plant = WidgetConfig.ROTATE }
                plants.forEach { Option(it.name, plant == it.id) { plant = it.id } }
            }
        }
        Button(onClick = { onDone(WidgetConfig(content, window, plant)) }, Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.app_done))
        }
    }
}

@Composable
private fun Section(title: String, options: @Composable () -> Unit) {
    SunWattCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup().padding(top = 4.dp)) { options() }
    }
}

@Composable
private fun Option(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
