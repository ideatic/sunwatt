package com.ideatic.sunwatt.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.ui.theme.PriceColors
import com.ideatic.sunwatt.ui.theme.SolarColors
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.wear.R
import com.ideatic.sunwatt.wear.model.StateKind
import java.time.Instant
import java.time.ZoneId

/**
 * [Format.time] with a plain space before "PM": the narrow no-break space that Java 20+ puts there (U+202F) is drawn wider
 * than it is measured with the watch font, and the end of the text is cut off.
 */
fun watchTime(time: Instant, zone: ZoneId = Constants.madrid): String = Format.time(time, zone).replace('\u202f', ' ')

internal val secondaryColor = Color.White.copy(alpha = 0.6f)

/** A tabular-figure number with a smaller unit, e.g. "4,8 kW" (Swift `FigureText`). */
@Composable
fun FigureText(value: String, unit: String?, sizeSp: Float, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontSize = sizeSp.sp, fontWeight = FontWeight.Bold, color = color, fontFeatureSettings = "tnum")) { append(value) }
            if (unit != null) {
                withStyle(SpanStyle(fontSize = maxOf(10f, sizeSp * 0.42f).sp, fontWeight = FontWeight.Medium, color = secondaryColor)) {
                    append(" $unit")
                }
            }
        },
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

@Composable
fun CaptionText(text: String, modifier: Modifier = Modifier, color: Color = secondaryColor, bold: Boolean = false) {
    Text(
        text, modifier = modifier, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, fontFeatureSettings = "tnum"),
    )
}

/** "Data from 11:35" marker: a history symbol and the time when stale, a quiet clock and the time when fresh. */
@Composable
fun DataTimeMarker(time: Instant?, stale: Boolean, zone: ZoneId = Constants.madrid) {
    if (time == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (stale) Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp) else Modifier,
    ) {
        if (stale) {
            Glyph(GlyphShape.HISTORY, secondaryColor, 10)
            Box(Modifier.size(3.dp))
        }
        CaptionText(watchTime(time, zone), bold = stale)
    }
}

/** Coloured dot and level word ("Cheap"), or "No current price". */
@Composable
fun LevelLabel(level: PriceLevel?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (level != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(PriceColors.color(level)))
            CaptionText(
                stringResource(
                    when (level) {
                        PriceLevel.LOW -> R.string.widgets_cheap
                        PriceLevel.MEDIUM -> R.string.widgets_average
                        PriceLevel.HIGH -> R.string.widgets_expensive
                    },
                ),
            )
        } else {
            CaptionText(stringResource(R.string.widgets_no_current_price))
        }
    }
}

/** Full-area message for a source without data (Swift `SourceStateView`, compact). */
@Composable
fun StateView(kind: StateKind, lastUpdate: Instant?, zone: ZoneId = Constants.madrid) {
    val title = when (kind) {
        StateKind.NOT_CONFIGURED -> R.string.widgets_solar_not_set_up
        StateKind.SOLAR_ERROR -> R.string.widgets_solar_data_unavailable
        StateKind.LINK_EXPIRED -> R.string.widgets_kiosk_link_expired
        StateKind.PRICE_ERROR -> R.string.widgets_prices_unavailable
    }
    val hint = when (kind) {
        StateKind.NOT_CONFIGURED -> R.string.widgets_set_up_solar_in_sunwatt
        StateKind.LINK_EXPIRED -> R.string.widgets_open_sunwatt_to_replace_it
        else -> R.string.widgets_open_sunwatt_to_check_the_connection
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(
            if (kind == StateKind.NOT_CONFIGURED) GlyphShape.SETTINGS else GlyphShape.WARNING,
            if (kind == StateKind.NOT_CONFIGURED) secondaryColor else SolarColors.orange, 20,
        )
        Box(Modifier.size(3.dp))
        Text(stringResource(title), textAlign = TextAlign.Center, maxLines = 2, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        Text(stringResource(hint), textAlign = TextAlign.Center, maxLines = 3, color = secondaryColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
        if (kind == StateKind.NOT_CONFIGURED) {
            Text(
                stringResource(R.string.watch_prices_show_spain_until_then), textAlign = TextAlign.Center, maxLines = 2,
                color = secondaryColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (lastUpdate != null) {
            CaptionText(stringResource(R.string.widgets_last_update, watchTime(lastUpdate, zone)), Modifier.padding(top = 2.dp))
        }
    }
}

/** The page container: leaves room for the round screen's corners and for the time at the top. */
@Composable
fun PageFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 42.dp, bottom = 24.dp)) { content() }
}
