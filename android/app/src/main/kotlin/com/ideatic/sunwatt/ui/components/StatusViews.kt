package com.ideatic.sunwatt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.ui.text.labelRes
import com.ideatic.sunwatt.ui.theme.BigFigure
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.RoundedFamily
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.SunWattCard
import com.ideatic.sunwatt.ui.theme.appPalette
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Rounded-font text with the app's sizes. [size] in sp; colour defaults to the page text colour. */
@Composable
fun RoundedText(
    text: String,
    sizeSp: Int,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
    tabularDigits: Boolean = false,
) {
    Text(
        text = text, modifier = modifier, color = color,
        style = TextStyle(fontFamily = RoundedFamily, fontWeight = weight, fontSize = sizeSp.sp, fontFeatureSettings = if (tabularDigits) "tnum" else null),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign,
    )
}

/** Section title of a card: symbol, bold title, optional quiet subtitle, optional trailing content. */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    symbol: @Composable () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { symbol() }
        Column(Modifier.weight(1f)) {
            RoundedText(title, 15, weight = FontWeight.Bold, maxLines = 1)
            if (subtitle != null) RoundedText(subtitle, 12, weight = FontWeight.Medium, color = appPalette.textSecondary, maxLines = 1)
        }
        trailing?.invoke(this)
    }
}

/** Segmented control drawn by the app: the selected option is an amber capsule. */
@Composable
fun <T> AppSegmented(options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val palette = appPalette
    Row(
        modifier.fillMaxWidth().clip(CircleShape).background(palette.fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, label) in options) {
            val selected = value == selection
            Box(
                Modifier.weight(1f).clip(CircleShape)
                    .then(if (selected) Modifier.background(Brush.verticalGradient(listOf(Color(1f, 0.78f, 0.30f), Color(1f, 0.62f, 0.24f)))) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                RoundedText(label, 13, weight = FontWeight.SemiBold, color = if (selected) palette.onAccent else palette.textSecondary, maxLines = 1)
            }
        }
    }
}

/** "● Cheap" capsule tinted by the price level. */
@Composable
fun LevelPill(level: PriceLevel, modifier: Modifier = Modifier) {
    val tint = PriceColors.color(level)
    Row(
        modifier.clip(CircleShape).background(tint.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        RoundedText(stringResource(level.labelRes()), 13, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A figure with a small caption below: "412,6 kWh" / "This month". */
@Composable
fun MiniStat(value: String, unit: String, caption: String, modifier: Modifier = Modifier, end: Boolean = false) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        BigFigure(value, unit, figureSizeSp = 17, unitSizeSp = 11)
        RoundedText(caption, 12, weight = FontWeight.Medium, color = appPalette.textSecondary, maxLines = 1)
    }
}

/** Small "data is older than expected" marker: clock symbol plus the time of the data. Meant to be quiet. */
@Composable
fun StaleBadge(since: Instant, modifier: Modifier = Modifier, zone: ZoneId = Constants.madrid, locale: Locale = Locale.getDefault()) {
    val palette = appPalette
    val time = Format.time(since, zone, locale)
    val description = stringResource(R.string.widgets_data_from, time)
    Row(
        modifier.clip(CircleShape).background(palette.textSecondary.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(11.dp)) {
            val stroke = Stroke(size.minDimension * 0.11f, cap = StrokeCap.Round)
            drawArc(palette.textSecondary, 40f, 290f, false, topLeft = Offset(size.width * 0.1f, size.height * 0.1f), size = Size(size.width * 0.8f, size.height * 0.8f), style = stroke)
            val c = Offset(size.width / 2, size.height / 2)
            drawLine(palette.textSecondary, c, c + Offset(0f, -size.height * 0.22f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(palette.textSecondary, c, c + Offset(size.width * 0.16f, size.height * 0.08f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
        RoundedText(time, 11, weight = FontWeight.SemiBold, color = palette.textSecondary, maxLines = 1, tabularDigits = true)
    }
}

/** One action of a [StateCard]. */
data class StateAction(val label: String, val prominent: Boolean = false, val onClick: () -> Unit)

/** Friendly inline card for a source that failed or is not set up. */
@Composable
fun StateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: List<StateAction> = emptyList(),
    symbol: @Composable () -> Unit,
) {
    SunWattCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(28.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) { symbol() }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                RoundedText(title, 17, weight = FontWeight.SemiBold)
                RoundedText(message, 15, color = appPalette.textSecondary)
            }
        }
        if (actions.isNotEmpty()) {
            Row(Modifier.padding(top = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                for (action in actions) {
                    if (action.prominent) PrimaryButton(action.label, action.onClick, compact = true)
                    else SoftButton(action.label, action.onClick, compact = true)
                }
            }
        }
    }
}

/** The SunWatt icon: a sun with a bolt on a warm gradient, a rounded square. */
@Composable
fun AppIconBadge(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.2237f))
            .background(Brush.verticalGradient(listOf(Color(1f, 0.80f, 0.30f), Color(1f, 0.55f, 0.22f), Color(0.93f, 0.36f, 0.20f)))),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension
            val c = Offset(s / 2, s / 2)
            for (i in 0 until 12) {
                val long = i % 2 == 0
                rotate(i * 30f, c) {
                    val h = s * (if (long) 0.13f else 0.09f)
                    drawLine(Color.White.copy(alpha = if (long) 0.95f else 0.7f), Offset(s / 2, s / 2 - s * 0.355f - h / 2), Offset(s / 2, s / 2 - s * 0.355f + h / 2), strokeWidth = s * 0.062f, cap = StrokeCap.Round)
                }
            }
            drawCircle(Brush.verticalGradient(listOf(Color.White, Color(1f, 0.95f, 0.80f))), radius = s * 0.25f, center = c)
        }
        AppSymbol(SymbolKind.BOLT, Brush.verticalGradient(listOf(Color(1f, 0.60f, 0.15f), Color(0.92f, 0.30f, 0.16f))), size = size * 0.34f)
    }
}
