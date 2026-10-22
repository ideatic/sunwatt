package com.ideatic.sunwatt.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.work.WidgetWork
import kotlin.math.min

/**
 * The SunWatt home screen widget, one per size (small, medium, large). It only draws what the store holds: the
 * network belongs to the refresh worker (DECISIONS D11, D14). The three sizes are three widgets in the picker because
 * Android has no "family" a widget could switch between, and so `WidgetPinning` can ask for one size.
 */
abstract class SunWattGlanceWidget(private val family: WidgetSize) : GlanceAppWidget() {
    // The layout depends on the real size: charts are drawn to fit their slot.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = (context.applicationContext as SunWattApplication).graph
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigStore(context).load(appWidgetId)
        val entry = widgetEntry(graph.loader.latest(), graph.loader.now(), config.plant)
        // Read in the content so a size the launcher sends later replaces the estimate.
        provideContent { SunWattWidgetContent(entry, config, family, sizeWithoutLauncherSize(context, appWidgetId, family)) }
    }
}

/**
 * Size to draw at when the launcher never sent the widget's size (the MIUI launcher on Android 10 sends none). Glance
 * then uses the minimum size of the widget XML, and everything is drawn too small in a corner. Null when the launcher
 * sent a size. Android 12 and later launchers send it, so there the options are not read.
 */
internal fun sizeWithoutLauncherSize(context: Context, appWidgetId: Int, family: WidgetSize): DpSize? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    if (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) > 0) return null
    val configuration = context.resources.configuration
    return estimatedWidgetSize(configuration.screenWidthDp, configuration.screenHeightDp, family)
}

/**
 * The usual phone grid: 4 columns across the short side of the screen, rows a little taller than a column is wide
 * (MIUI: 92 x 106 dp). A column is at most [MAX_COLUMN_DP]: tablets have more columns, and a size larger than the real
 * widget would cut the content, while a smaller one only leaves space. A resize the launcher does not report is not seen.
 */
internal fun estimatedWidgetSize(screenWidthDp: Int, screenHeightDp: Int, family: WidgetSize): DpSize {
    val column = min((min(screenWidthDp, screenHeightDp) - 2 * GRID_MARGIN_DP) / 4f, MAX_COLUMN_DP)
    val (columns, rows) = when (family) {
        WidgetSize.SMALL -> 2 to 2
        WidgetSize.MEDIUM -> 4 to 2
        WidgetSize.LARGE -> 4 to 4
    }
    return DpSize((column * columns).dp, (column * ROW_TO_COLUMN * rows).dp)
}

private const val GRID_MARGIN_DP = 12
private const val MAX_COLUMN_DP = 100f
private const val ROW_TO_COLUMN = 1.1f

class SunWattSmallWidget : SunWattGlanceWidget(WidgetSize.SMALL)

class SunWattMediumWidget : SunWattGlanceWidget(WidgetSize.MEDIUM)

class SunWattLargeWidget : SunWattGlanceWidget(WidgetSize.LARGE)

/** Shared receiver behaviour: keep the refresh work in step with the widgets that exist. */
abstract class SunWattWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetWork.sync(context, refreshNow = true)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = WidgetConfigStore(context)
        appWidgetIds.forEach(store::delete)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetWork.sync(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // A widget the app asked to pin gets its first update here, also on launchers that send no pin callback.
        WidgetPinning.confirmPinned(context)
        // The system asks for an update at boot and after an app update: make sure the periodic work is there.
        WidgetWork.sync(context)
    }
}

class SunWattSmallWidgetReceiver : SunWattWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SunWattSmallWidget()
}

class SunWattMediumWidgetReceiver : SunWattWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SunWattMediumWidget()
}

class SunWattLargeWidgetReceiver : SunWattWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SunWattLargeWidget()
}
