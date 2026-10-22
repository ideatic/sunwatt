package com.ideatic.sunwatt.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.ui.components.RoundedText
import com.ideatic.sunwatt.ui.text.displayNameRes
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette

/** The regions of the country list, in the order of the picker. */
val priceRegionChoices: List<PriceRegion> get() = PriceRegion.entries

/** "Regulated PVPC price" or "Wholesale day-ahead price". */
@Composable
fun tariffNote(region: PriceRegion): String =
    stringResource(if (region.isRetailTariff) R.string.app_regulated_pvpc_price else R.string.app_wholesale_day_ahead_price)

/** "Prices: Red Eléctrica" or "Prices: Energy-Charts (Fraunhofer ISE), CC BY 4.0": the licence attribution must stay visible (AGENTS.md rule 7). */
@Composable
fun priceAttribution(region: PriceRegion): String =
    if (region.isRetailTariff) stringResource(R.string.app_prices, region.sourceName)
    else stringResource(R.string.app_prices_cc_by_4_0, region.sourceName)

/** Country list used by settings and onboarding: flag, name, check mark. [showsNotes]: the tariff note under each name. */
@Composable
fun RegionList(selection: PriceRegion, onSelect: (PriceRegion) -> Unit, modifier: Modifier = Modifier, showsNotes: Boolean = false) {
    val palette = appPalette
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (region in priceRegionChoices) {
            val selected = region == selection
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) SolarColors.amber.copy(alpha = if (palette.isDark) 0.16f else 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(region) })
                    .padding(horizontal = Spacing.m, vertical = if (showsNotes) 8.dp else 7.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Text(region.flag, fontSize = 26.sp)
                Column(Modifier.weight(1f)) {
                    ShrinkText(stringResource(region.displayNameRes()), 16f, weight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    if (showsNotes) RoundedText(tariffNote(region), 12, color = palette.textSecondary, maxLines = 1)
                }
                if (selected) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(SolarColors.gradient), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = palette.onAccent)
                    }
                } else {
                    Box(Modifier.size(22.dp).border(1.5.dp, palette.hairline, CircleShape))
                }
            }
        }
    }
}
