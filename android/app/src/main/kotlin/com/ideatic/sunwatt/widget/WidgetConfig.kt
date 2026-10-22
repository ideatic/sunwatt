package com.ideatic.sunwatt.widget

import android.content.Context
import com.ideatic.sunwatt.core.models.PriceWindow
import com.ideatic.sunwatt.core.models.WidgetContent
import com.ideatic.sunwatt.core.timeline.WidgetPlantChoice

/**
 * What one widget is set to (the Swift `SunWattIntent`). Stored as the raw values the Apple app uses (FEATURES.md,
 * "stored raw values"): `both|solarOnly|priceOnly`, `wholeDay|around`, `rotate|<plant id>`.
 */
data class WidgetConfig(
    val content: WidgetContent = WidgetContent.BOTH,
    val window: PriceWindow = PriceWindow.WHOLE_DAY,
    /** [ROTATE] or a [com.ideatic.sunwatt.core.models.SolarPlant.id]. Only matters with several plants. */
    val plantRaw: String = ROTATE,
) {
    val plant: WidgetPlantChoice
        get() = if (plantRaw == ROTATE) WidgetPlantChoice.Rotate else WidgetPlantChoice.Plant(plantRaw)

    companion object {
        const val ROTATE = "rotate"
    }
}

/** Per-widget settings, keyed by the app widget id (kept in SharedPreferences: read by the widget, the config screen and the receiver). */
class WidgetConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(appWidgetId: Int): WidgetConfig = WidgetConfig(
        content = prefs.getString("content.$appWidgetId", null)?.let(WidgetContent::fromRaw) ?: WidgetContent.BOTH,
        window = prefs.getString("window.$appWidgetId", null)?.let(PriceWindow::fromRaw) ?: PriceWindow.WHOLE_DAY,
        plantRaw = prefs.getString("plant.$appWidgetId", null) ?: WidgetConfig.ROTATE,
    )

    fun save(appWidgetId: Int, config: WidgetConfig) {
        prefs.edit()
            .putString("content.$appWidgetId", config.content.rawValue)
            .putString("window.$appWidgetId", config.window.rawValue)
            .putString("plant.$appWidgetId", config.plantRaw)
            .apply()
    }

    fun delete(appWidgetId: Int) {
        prefs.edit().remove("content.$appWidgetId").remove("window.$appWidgetId").remove("plant.$appWidgetId").apply()
    }

    private companion object {
        const val FILE = "sunwatt_widgets"
    }
}
