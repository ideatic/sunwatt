package com.ideatic.sunwatt.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.SunWattLink
import com.ideatic.sunwatt.core.models.WidgetContent
import com.ideatic.sunwatt.ui.charts.ChartVariant
import com.ideatic.sunwatt.ui.text.TextRes
import kotlin.math.max
import kotlin.math.min

// The three widget layouts (port of `SmallWidgetView`, `MediumWidgetView`, `LargeWidgetView`, `SunWattWidgetView`).
// Sizes come from the widget's real size, so a resized widget redraws its charts at the new size.

private val PAD = 14.dp

/** A tap anywhere opens the app with `sunwatt://refresh` (DECISIONS D25). */
internal fun refreshIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(SunWattLink.REFRESH_URL))
        .setComponent(ComponentName(context, "com.ideatic.sunwatt.MainActivity"))

@Composable
fun SunWattWidgetContent(entry: SunWattEntry, config: WidgetConfig, family: WidgetSize, estimatedSize: DpSize? = null) {
    val context = LocalContext.current
    val size = estimatedSize ?: LocalSize.current
    val solar = SolarModel(entry)
    val price = PriceModel(entry, config.window)
    val kind = WidgetState.fullStateKind(solar, price, config.content)
    val inner = DpSize(size.width - PAD * 2, size.height - PAD * 2)
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_background))
            .clickable(actionStartActivity(refreshIntent(context)))
            .padding(PAD),
    ) {
        when {
            kind != null && family == WidgetSize.LARGE -> SourceState(kind, lastUpdate = entry.data.lastUpdated)
            kind != null -> SourceState(kind, compact = family == WidgetSize.SMALL, lastUpdate = entry.data.lastUpdated)
            else -> when (family) {
                WidgetSize.SMALL -> SmallLayout(solar, price, config.content, entry, inner)
                WidgetSize.MEDIUM -> MediumLayout(solar, price, config.content, entry, inner)
                WidgetSize.LARGE -> LargeLayout(solar, price, config.content, entry, inner)
            }
        }
    }
}

// ---- small -----------------------------------------------------------------------------------------------------

@Composable
private fun SmallLayout(solar: SolarModel, price: PriceModel, requested: WidgetContent, entry: SunWattEntry, inner: DpSize) {
    when (WidgetState.smallContent(solar, price, requested)) {
        WidgetContent.PRICE_ONLY -> SmallPrice(price, inner)
        WidgetContent.SOLAR_ONLY -> SmallSolar(solar, price, entry, inner, chart = true, showPill = false)
        WidgetContent.BOTH -> SmallSolar(solar, price, entry, inner, chart = false, showPill = true)
    }
}

@Composable
private fun SmallSolar(solar: SolarModel, price: PriceModel, entry: SunWattEntry, inner: DpSize, chart: Boolean, showPill: Boolean) {
    val context = LocalContext.current
    if (!solar.isAvailable) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            solar.plantName?.let { PlantNameLabel(it) }
            SourceState(solar.stateKind, compact = true, lastUpdate = solar.snapshot?.fetchedAt)
        }
        return
    }
    val figure = min(40f, inner.height.value * 0.3f)
    val figureHeight = figure * 1.35f
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            SolarIcon(solar.isIdle, 18.dp)
            solar.plantName?.let {
                Spacer(GlanceModifier.width(4.dp))
                PlantNameLabel(it, GlanceModifier.defaultWeight())
            }
            Spacer(GlanceModifier.defaultWeight())
            DataTimeMarker(solar.dataTime, solar.staleSince != null)
        }
        Spacer(GlanceModifier.height(4.dp))
        SolarFigure(solar, inner.width, figure)
        Spacer(GlanceModifier.height(2.dp))
        SolarDailyCaption(solar)
        val snapshot = solar.snapshot
        if (chart && snapshot != null) {
            val chartHeight = inner.height - 22.dp - 4.dp - figureHeight.dp - 2.dp - 18.dp - 12.dp
            Spacer(GlanceModifier.height(8.dp))
            if (chartHeight > 20.dp) {
                ChartImage(WidgetCharts.solar(context, snapshot, entry.date, DpSize(inner.width, chartHeight), ChartVariant.WIDGET_SMALL), inner.width, chartHeight)
            }
        } else {
            Spacer(GlanceModifier.defaultWeight())
            if (showPill && price.series != null) PricePill(price.current?.eurPerKWh, price.fraction)
        }
    }
}

@Composable
private fun SmallPrice(price: PriceModel, inner: DpSize) {
    val context = LocalContext.current
    val figure = min(36f, inner.height.value * 0.27f)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(modifier = GlanceModifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            PriceIcon(price.fraction, 18.dp)
            Spacer(GlanceModifier.defaultWeight())
            price.staleSince?.let { StaleBadge(it, price.timeZone) }
        }
        Spacer(GlanceModifier.height(4.dp))
        PriceFigure(price, inner.width, figure, showsUnit = false)
        Spacer(GlanceModifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            WText("€/kWh", 12.sp)
            Spacer(GlanceModifier.width(6.dp))
            LevelLabel(price)
        }
        val chartHeight = inner.height - 22.dp - 4.dp - (figure * 1.35f).dp - 2.dp - 18.dp - 12.dp
        Spacer(GlanceModifier.height(8.dp))
        if (chartHeight > 20.dp) {
            ChartImage(WidgetCharts.price(context, price, DpSize(inner.width, chartHeight), ChartVariant.WIDGET_SMALL), inner.width, chartHeight)
        }
    }
}

// ---- medium and large header -------------------------------------------------------------------------------------

/** Heights of the two compact header rows (figure row + caption row). */
private fun headerHeight(figure: Float): Dp = (figure * 1.35f).dp + 4.dp + 18.dp

@Composable
private fun SolarColumn(solar: SolarModel, entry: SunWattEntry, width: Dp, height: Dp, figure: Float, showChart: Boolean, variant: ChartVariant) {
    val context = LocalContext.current
    if (!solar.isAvailable) {
        Column(modifier = GlanceModifier.width(width).height(height)) {
            solar.plantName?.let { PlantNameLabel(it) }
            SourceState(solar.stateKind, compact = true, lastUpdate = solar.snapshot?.fetchedAt)
        }
        return
    }
    Column(modifier = GlanceModifier.width(width)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SolarIcon(solar.isIdle, 20.dp)
            solar.plantName?.let {
                Spacer(GlanceModifier.width(4.dp))
                PlantNameLabel(it, GlanceModifier.defaultWeight())
            }
            Spacer(GlanceModifier.defaultWeight())
            SolarFigure(solar, width - 28.dp, figure)
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DataTimeMarker(solar.dataTime, solar.staleSince != null)
            Spacer(GlanceModifier.defaultWeight())
            SolarDailyCaption(solar)
        }
        val snapshot = solar.snapshot
        val chartHeight = height - headerHeight(figure) - 6.dp
        if (showChart && snapshot != null && chartHeight > 20.dp) {
            Spacer(GlanceModifier.height(6.dp))
            ChartImage(WidgetCharts.solar(context, snapshot, entry.date, DpSize(width, chartHeight), variant), width, chartHeight)
        }
    }
}

@Composable
private fun PriceColumn(price: PriceModel, width: Dp, height: Dp, figure: Float, showChart: Boolean, variant: ChartVariant) {
    val context = LocalContext.current
    if (price.series == null) {
        SourceState(StateKind.PRICE_ERROR, compact = true, zone = price.timeZone, modifier = GlanceModifier.width(width).height(height))
        return
    }
    Column(modifier = GlanceModifier.width(width)) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PriceIcon(price.fraction, 20.dp)
            Spacer(GlanceModifier.defaultWeight())
            PriceFigure(price, width - 28.dp, figure)
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            price.staleSince?.let { StaleBadge(it, price.timeZone) }
            Spacer(GlanceModifier.defaultWeight())
            LevelLabel(price)
        }
        val chartHeight = height - headerHeight(figure) - 6.dp
        if (showChart && chartHeight > 20.dp) {
            Spacer(GlanceModifier.height(6.dp))
            ChartImage(WidgetCharts.price(context, price, DpSize(width, chartHeight), variant), width, chartHeight)
        }
    }
}

/** The figure size both columns share, so their rows line up. */
private fun sharedFigure(solar: SolarModel, price: PriceModel, columnWidth: Dp, max: Float): Float {
    val solarFit = if (solar.isAvailable && !solar.isIdle) fitSize(Format.kW(solar.powerKW), "kW", columnWidth - 28.dp, max) else max
    val priceFit = if (price.series != null) fitSize(Format.eurPerKWh(price.current?.eurPerKWh), "€/kWh", columnWidth - 28.dp, max) else max
    return min(solarFit, priceFit)
}

@Composable
private fun HeaderRow(solar: SolarModel, price: PriceModel, content: WidgetContent, entry: SunWattEntry, inner: DpSize, height: Dp, maxFigure: Float, charts: Boolean, variant: ChartVariant) {
    when (content) {
        WidgetContent.BOTH -> {
            val column = (inner.width - 17.dp) / 2
            val figure = sharedFigure(solar, price, column, maxFigure)
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                SolarColumn(solar, entry, column, height, figure, charts, variant)
                Spacer(GlanceModifier.width(8.dp))
                Divider(height)
                Spacer(GlanceModifier.width(8.dp))
                PriceColumn(price, column, height, figure, charts, variant)
            }
        }
        WidgetContent.SOLAR_ONLY -> {
            val figure = sharedFigure(solar, price, inner.width, maxFigure + 6f).let { min(it, maxFigure + 6f) }
            SolarColumn(solar, entry, inner.width, height, figure, charts, variant)
        }
        WidgetContent.PRICE_ONLY -> {
            val figure = sharedFigure(solar, price, inner.width, maxFigure + 6f)
            PriceColumn(price, inner.width, height, figure, charts, variant)
        }
    }
}

// ---- medium ----------------------------------------------------------------------------------------------------

@Composable
private fun MediumLayout(solar: SolarModel, price: PriceModel, content: WidgetContent, entry: SunWattEntry, inner: DpSize) {
    // The figure also has to leave room for the charts: the widget is only two rows tall by default.
    val maxFigure = min(34f, max(20f, inner.height.value * 0.3f))
    HeaderRow(solar, price, content, entry, inner, inner.height, maxFigure, charts = true, variant = ChartVariant.WIDGET_MEDIUM)
}

// ---- large -----------------------------------------------------------------------------------------------------

@Composable
private fun LargeLayout(solar: SolarModel, price: PriceModel, content: WidgetContent, entry: SunWattEntry, inner: DpSize) {
    val context = LocalContext.current
    val headerHeight = 64.dp
    val footerHeight = 40.dp
    val caption = 18.dp
    val gap = 8.dp
    val sections = if (content == WidgetContent.BOTH) 2 else 1
    val chartHeight = (inner.height - headerHeight - footerHeight - gap * (sections + 1) - (caption + 4.dp) * sections - 8.dp) / sections
    // A Glance Column holds at most 10 children: each section is one child.
    Column(modifier = GlanceModifier.fillMaxSize()) {
        Box(modifier = GlanceModifier.height(headerHeight)) {
            HeaderRow(solar, price, content, entry, inner, headerHeight, 28f, charts = false, variant = ChartVariant.WIDGET_LARGE)
        }
        if (content != WidgetContent.PRICE_ONLY) {
            Column(modifier = GlanceModifier.padding(top = gap)) {
                ChartCaption(text(TextRes(R.string.widgets_production)), solar.home?.text { Format.kW(it) }?.let { text(it) }, inner.width)
                Spacer(GlanceModifier.height(4.dp))
                val snapshot = solar.snapshot
                if (solar.isAvailable && snapshot != null) {
                    ChartImage(WidgetCharts.solar(context, snapshot, entry.date, DpSize(inner.width, chartHeight), ChartVariant.WIDGET_LARGE), inner.width, chartHeight)
                } else {
                    Spacer(GlanceModifier.height(chartHeight))
                }
            }
        }
        if (content != WidgetContent.SOLAR_ONLY) {
            Column(modifier = GlanceModifier.padding(top = gap)) {
                ChartCaption(text(TextRes(R.string.widgets_price)), "€/kWh", inner.width)
                Spacer(GlanceModifier.height(4.dp))
                ChartImage(WidgetCharts.price(context, price, DpSize(inner.width, chartHeight), ChartVariant.WIDGET_LARGE), inner.width, chartHeight)
            }
        }
        Box(modifier = GlanceModifier.padding(top = gap)) { LargeFooter(solar, price, content, entry, inner.width) }
    }
}

@Composable
private fun LargeFooter(solar: SolarModel, price: PriceModel, content: WidgetContent, entry: SunWattEntry, width: Dp) {
    Row(modifier = GlanceModifier.width(width).height(40.dp), verticalAlignment = Alignment.Bottom) {
        val stats = price.stats
        val peak = solar.snapshot?.peakKW
        if (content != WidgetContent.SOLAR_ONLY && stats != null) {
            PriceStatsRow(stats)
        } else if (peak != null) {
            Column {
                WText(text(TextRes(R.string.widgets_peak)), 10.sp)
                WText("${Format.kW(peak)} kW", 12.sp, weight = FontWeight.Bold, color = WidgetColors.ink)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Column(horizontalAlignment = Alignment.End) {
            if (content != WidgetContent.SOLAR_ONLY && price.series != null) {
                WText(text(price.region.sourceLabel()).uppercase(), 10.sp)
            }
            entry.data.lastUpdated?.let { WText(text(TextRes(R.string.widgets_updated, listOf(Format.time(it)))), 10.sp) }
        }
    }
}
