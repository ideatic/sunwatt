package com.ideatic.sunwatt.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.plant
import com.ideatic.sunwatt.core.models.updatingPlant
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette

/** One tab of [PlantTabs]. */
data class PlantTab(val id: String, val name: String)

/** Several plants: one chip per plant, the selected one tinted. Scrolls sideways when the names do not fit. */
@Composable
fun PlantTabs(plants: List<PlantTab>, selection: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = appPalette
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for (plant in plants) {
            val selected = plant.id == selection
            RoundedText(
                plant.name, 14, weight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
                color = if (selected) palette.accentText else palette.textSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) palette.accentText.copy(alpha = 0.14f) else palette.hairline.copy(alpha = palette.hairline.alpha * 0.5f))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(plant.id) })
                    .padding(horizontal = Spacing.m, vertical = 7.dp),
            )
        }
    }
}

/** The name of a plant (several plants). Saved when the user submits, leaves the field or the screen goes away. */
@Composable
fun PlantNameEditor(plantID: String, modifier: Modifier = Modifier) {
    val vm = LocalAppViewModel.current
    var name by remember(plantID) { mutableStateOf(vm.settings.plant(plantID)?.name ?: "") }
    val commit = {
        val plant = vm.settings.plant(plantID)
        val value = name.trim()
        if (plant != null && value != plant.name) vm.updateSettings { it.updatingPlant(plant.copy(name = value)) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FieldLabel(stringResource(R.string.app_plant_name))
        AppTextField(name, { name = it }, stringResource(R.string.app_for_example_home), onCommit = commit)
    }
    CommitOnLeave(commit)
}
