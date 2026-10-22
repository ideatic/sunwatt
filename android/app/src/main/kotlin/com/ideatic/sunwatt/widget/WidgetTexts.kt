package com.ideatic.sunwatt.widget

import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSource
import com.ideatic.sunwatt.ui.text.TextRes

// Widget, tile and answer texts. Every text is a catalog string (`widgets_*`, `widgetext_*`, `intents_*`).

fun StateKind.title(): TextRes = TextRes(
    when (this) {
        StateKind.NOT_CONFIGURED -> R.string.widgets_solar_not_set_up
        StateKind.SOLAR_ERROR -> R.string.widgets_solar_data_unavailable
        StateKind.LINK_EXPIRED -> R.string.widgets_kiosk_link_expired
        StateKind.PRICE_ERROR -> R.string.widgets_prices_unavailable
        StateKind.UNAVAILABLE -> R.string.widgets_data_unavailable
    },
)

fun StateKind.hint(): TextRes = TextRes(
    when (this) {
        StateKind.NOT_CONFIGURED -> R.string.widgets_set_up_solar_in_sunwatt
        StateKind.LINK_EXPIRED -> R.string.widgets_open_sunwatt_to_replace_it
        StateKind.SOLAR_ERROR, StateKind.PRICE_ERROR, StateKind.UNAVAILABLE -> R.string.widgets_open_sunwatt_to_check_the_connection
    },
)

/** "Cheap", "Average", "Expensive": the Widgets table names (the app uses "Normal"). */
fun PriceLevel.widgetLabel(): TextRes = TextRes(
    when (this) {
        PriceLevel.LOW -> R.string.widgets_cheap
        PriceLevel.MEDIUM -> R.string.widgets_average
        PriceLevel.HIGH -> R.string.widgets_expensive
    },
)

/** Short source label for the large widget footer. */
fun PriceRegion.sourceLabel(): TextRes = TextRes(
    when (profile.source) {
        PriceSource.RedElectrica -> R.string.widgets_pvpc
        is PriceSource.EnergyCharts -> R.string.widgets_day_ahead
    },
)

fun HomeCaption.text(format: (Double) -> String): TextRes = when (grid) {
    HomeCaption.Grid.NONE -> TextRes(R.string.widgets_home_kw, listOf(format(consumptionKW)))
    HomeCaption.Grid.BUYING -> TextRes(R.string.widgets_home_kw_buying_kw, listOf(format(consumptionKW), format(checkNotNull(gridKW))))
    HomeCaption.Grid.SELLING -> TextRes(R.string.widgets_home_kw_selling_kw, listOf(format(consumptionKW), format(checkNotNull(gridKW))))
}
