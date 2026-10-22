package com.ideatic.sunwatt.widget

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.ui.text.TextRes
import com.ideatic.sunwatt.ui.theme.PriceColors
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

// Building blocks of the widget layouts (port of `WidgetParts.swift`, `FigureText.swift`, `StatusViews.swift`).
// Glance has no rounded font, no tabular digits, no minimum scale factor: sizes are worked out from the width instead.

internal object WidgetColors {
    val ink = ColorProvider(day = Color(0xFF111111), night = Color.White)
    val secondary = ColorProvider(day = Color(0x99111111), night = Color(0x99FFFFFF))
    val badgeFill = ColorProvider(day = Color(0x24000000), night = Color(0x2EFFFFFF))
    val hairline = ColorProvider(day = Color(0x1F000000), night = Color(0x26FFFFFF))
    val orange = ColorProvider(Color(0xFFFF853D))
}

@Composable
internal fun text(res: TextRes): String = res.resolve(LocalContext.current)

@Composable
internal fun WText(
    text: String,
    size: TextUnit,
    modifier: GlanceModifier = GlanceModifier,
    weight: FontWeight = FontWeight.Medium,
    color: ColorProvider = WidgetColors.secondary,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
    )
}

/** Largest font size (sp) at which [value] and [unit] fit in [width], at most [max]. Character widths are estimates for the system font. */
internal fun fitSize(value: String, unit: String?, width: Dp, max: Float, min: Float = 14f): Float {
    val perSize = 0.58f * value.length + (unit?.let { 0.42f * 0.55f * it.length + 0.1f } ?: 0f)
    return (width.value / perSize).coerceIn(min, max)
}

/** Big figure with a small unit next to it. */
@Composable
internal fun Figure(value: String, unit: String?, size: Float, color: ColorProvider = WidgetColors.ink) {
    Row(verticalAlignment = Alignment.Bottom) {
        WText(value, size.sp, weight = FontWeight.Bold, color = color)
        if (unit != null) {
            Spacer(GlanceModifier.width(2.dp))
            WText(unit, max(10f, size * 0.42f).sp, color = WidgetColors.secondary)
        }
    }
}

@Composable
internal fun SymbolImage(resId: Int, size: Dp, tint: ColorProvider? = null, modifier: GlanceModifier = GlanceModifier) {
    Image(
        provider = ImageProvider(resId),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = tint?.let { ColorFilter.tint(it) },
    )
}

/** Sun (amber gradient) while producing, dimmed moon when idle. */
@Composable
internal fun SolarIcon(idle: Boolean, size: Dp = 16.dp) {
    if (idle) SymbolImage(R.drawable.widget_ic_moon, size, WidgetColors.secondary) else SymbolImage(R.drawable.widget_ic_sun, size)
}

/** Bolt tinted by the current price level. */
@Composable
internal fun PriceIcon(fraction: Double?, size: Dp = 16.dp) {
    SymbolImage(R.drawable.widget_ic_bolt, size, fraction?.let { ColorProvider(PriceColors.color(it)) } ?: WidgetColors.ink)
}

/** "5,3 kW", or "No production" when idle. */
@Composable
internal fun SolarFigure(solar: SolarModel, width: Dp, maxSize: Float) {
    if (solar.isIdle) {
        val label = text(TextRes(R.string.widgets_no_production))
        WText(label, (maxSize * 0.5f).sp, weight = FontWeight.Bold, color = WidgetColors.secondary, maxLines = 2)
    } else {
        val value = Format.kW(solar.powerKW)
        Figure(value, "kW", fitSize(value, "kW", width, maxSize))
    }
}

/** "23,4 kWh today". */
@Composable
internal fun SolarDailyCaption(solar: SolarModel) {
    WText(text(TextRes(R.string.widgets_kwh_today, listOf(Format.kWh(solar.dailyKWh)))), 10.sp)
}

/** Current price as a big figure, or a dash when the series ran out. */
@Composable
internal fun PriceFigure(price: PriceModel, width: Dp, maxSize: Float, showsUnit: Boolean = true) {
    val value = Format.eurPerKWh(price.current?.eurPerKWh)
    val unit = if (showsUnit) "€/kWh" else null
    Figure(value, unit, fitSize(value, unit, width, maxSize))
}

/** Coloured dot and the level word ("Cheap"), or "No current price". */
@Composable
internal fun LevelLabel(price: PriceModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val level = price.level
        if (level != null) {
            SymbolImage(R.drawable.widget_dot, 7.dp, ColorProvider(PriceColors.color(level)))
            Spacer(GlanceModifier.width(5.dp))
            WText(text(level.widgetLabel()), 12.sp)
        } else {
            WText(text(TextRes(R.string.widgets_no_current_price)), 12.sp)
        }
    }
}

/** Clock plus the time, in a quiet capsule: the data is older than expected. */
@Composable
internal fun StaleBadge(since: Instant, zone: ZoneId) {
    Row(
        modifier = GlanceModifier.capsule(WidgetColors.badgeFill, 10.dp).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SymbolImage(R.drawable.widget_ic_clock, 10.dp, WidgetColors.secondary)
        Spacer(GlanceModifier.width(3.dp))
        WText(Format.time(since, zone), 10.sp, weight = FontWeight.Bold)
    }
}

/** Clock plus the time of fresh data, no capsule. */
@Composable
internal fun DataTimeLabel(time: Instant, zone: ZoneId) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SymbolImage(R.drawable.widget_ic_clock, 10.dp, WidgetColors.secondary)
        Spacer(GlanceModifier.width(3.dp))
        WText(Format.time(time, zone), 11.sp)
    }
}

/** A [StaleBadge] when stale, else the quiet time label. */
@Composable
internal fun DataTimeMarker(time: Instant?, stale: Boolean, zone: ZoneId = Constants.madrid) {
    if (time == null) return
    if (stale) StaleBadge(time, zone) else DataTimeLabel(time, zone)
}

/** Name of the plant a widget shows, when there are several. */
@Composable
internal fun PlantNameLabel(name: String, modifier: GlanceModifier = GlanceModifier) {
    WText(name, 11.sp, modifier, weight = FontWeight.Bold)
}

/** Friendly full-area message: symbol, title, one-line hint and, when a cached time exists, "Last update 11:35". */
@Composable
internal fun SourceState(kind: StateKind, compact: Boolean = false, lastUpdate: Instant? = null, zone: ZoneId = Constants.madrid, modifier: GlanceModifier = GlanceModifier) {
    val icon = when (kind) {
        StateKind.NOT_CONFIGURED -> R.drawable.widget_ic_gear
        StateKind.LINK_EXPIRED -> R.drawable.widget_ic_link
        StateKind.UNAVAILABLE -> R.drawable.widget_ic_offline
        StateKind.SOLAR_ERROR, StateKind.PRICE_ERROR -> R.drawable.widget_ic_warning
    }
    val tint = if (kind == StateKind.NOT_CONFIGURED) WidgetColors.secondary else WidgetColors.orange
    Column(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SymbolImage(icon, if (compact) 18.dp else 28.dp, tint)
        Spacer(GlanceModifier.height(if (compact) 4.dp else 6.dp))
        WText(text(kind.title()), (if (compact) 12 else 14).sp, weight = FontWeight.Bold, color = WidgetColors.ink, maxLines = 2, align = TextAlign.Center)
        WText(text(kind.hint()), 11.sp, maxLines = if (compact) 3 else 2, align = TextAlign.Center)
        if (lastUpdate != null) {
            Spacer(GlanceModifier.height(1.dp))
            WText(text(TextRes(R.string.widgets_last_update, listOf(Format.time(lastUpdate, zone)))), 11.sp, maxLines = 1)
        }
    }
}

/** Rounded fill. Before Android 12 Glance `cornerRadius` does nothing, so a tinted capsule drawable draws the shape. */
internal fun GlanceModifier.capsule(fill: ColorProvider, radius: Dp): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        background(fill).cornerRadius(radius)
    } else {
        background(ImageProvider(R.drawable.widget_capsule), ContentScale.FillBounds, ColorFilter.tint(fill))
    }

/** Capsule with the current price, tinted by level. */
@Composable
internal fun PricePill(eurPerKWh: Double?, fraction: Double?) {
    val fill = fraction?.let { ColorProvider(PriceColors.color(it).copy(alpha = 0.20f)) } ?: WidgetColors.badgeFill
    Row(
        modifier = GlanceModifier.capsule(fill, 16.dp).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriceIcon(fraction, 12.dp)
        Spacer(GlanceModifier.width(5.dp))
        Figure(Format.eurPerKWh(eurPerKWh), "€/kWh", 15f)
    }
}

/** A chart bitmap at exactly its slot size, or an empty slot of that size. */
@Composable
internal fun ChartImage(bitmap: Bitmap?, width: Dp, height: Dp, description: String? = null) {
    if (bitmap == null) {
        Spacer(GlanceModifier.width(width).height(height))
    } else {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = description,
            modifier = GlanceModifier.width(width).height(height),
            contentScale = ContentScale.FillBounds,
        )
    }
}

/** Small section title above a chart: small caps on the left, a unit on the right. */
@Composable
internal fun ChartCaption(title: String, trailing: String?, width: Dp) {
    Row(modifier = GlanceModifier.width(width), verticalAlignment = Alignment.CenterVertically) {
        WText(title.uppercase(), 10.sp, weight = FontWeight.Bold)
        Spacer(GlanceModifier.defaultWeight())
        if (trailing != null) WText(trailing, 10.sp, weight = FontWeight.Bold)
    }
}

/** Vertical rule between the solar and the price column. */
@Composable
internal fun Divider(height: Dp) {
    Box(modifier = GlanceModifier.width(1.dp).height(height).background(WidgetColors.hairline)) {}
}

/** Min / average / max prices of the day with level dots. */
@Composable
internal fun PriceStatsRow(stats: com.ideatic.sunwatt.core.models.PriceStats) {
    Row(verticalAlignment = Alignment.Top) {
        StatItem(R.string.widgets_min, stats.min, PriceColors.green)
        Spacer(GlanceModifier.width(12.dp))
        StatItem(R.string.widgets_avg, stats.average, PriceColors.yellow)
        Spacer(GlanceModifier.width(12.dp))
        StatItem(R.string.widgets_max, stats.max, PriceColors.red)
    }
}

@Composable
private fun StatItem(label: Int, value: Double, color: Color) {
    Column {
        WText(text(TextRes(label)), 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymbolImage(R.drawable.widget_dot, 6.dp, ColorProvider(color))
            Spacer(GlanceModifier.width(4.dp))
            WText(Format.eurPerKWh(value), 12.sp, weight = FontWeight.Bold, color = WidgetColors.ink)
        }
    }
}

internal fun Context.dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()
