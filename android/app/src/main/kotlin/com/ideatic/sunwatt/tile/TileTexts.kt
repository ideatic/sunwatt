package com.ideatic.sunwatt.tile

import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.intents.ControlContent
import com.ideatic.sunwatt.shortcuts.AnswerStrings
import com.ideatic.sunwatt.widget.widgetLabel

/** What a Quick Settings tile shows. Plain data: the tile service only copies it onto the `Tile`. */
data class TileLines(val title: String, val subtitle: String, val icon: TileIcon)

enum class TileIcon { BOLT, SUN, MOON }

/** Port of the Swift `ControlContent.price(from:now:)` / `.solar(from:now:)` texts. */
class TileTexts(private val strings: AnswerStrings) {
    fun price(content: ControlContent.Price): TileLines {
        val value = content.eurPerKWh
        val level = content.level
        if (value == null || level == null) return TileLines(noData(), strings.text(R.string.intents_tap_to_refresh), TileIcon.BOLT)
        return TileLines(
            title = "${Format.eurPerKWh(value, locale = strings.locale)} €/kWh",
            subtitle = strings.text(level.widgetLabel().id),
            icon = TileIcon.BOLT,
        )
    }

    fun solar(content: ControlContent.Solar): TileLines {
        val today = content.todayKWh?.let { strings.text(R.string.intents_today_kwh, Format.kWh(it, strings.locale)) }
        return when (content.state) {
            ControlContent.Solar.State.NOT_SET_UP -> TileLines(strings.text(R.string.intents_not_set_up), APP_NAME, TileIcon.SUN)
            ControlContent.Solar.State.NO_DATA -> TileLines(noData(), strings.text(R.string.intents_tap_to_refresh), TileIcon.SUN)
            ControlContent.Solar.State.NO_PRODUCTION ->
                TileLines(strings.text(R.string.intents_no_production), today.orEmpty(), TileIcon.MOON)
            ControlContent.Solar.State.PRODUCING ->
                TileLines("${Format.kW(content.powerKW, strings.locale)} kW", today.orEmpty(), TileIcon.SUN)
        }
    }

    private fun noData(): String = strings.text(R.string.intents_no_data_d802d2)

    private companion object {
        /** Brand name, never translated. */
        const val APP_NAME = "SunWatt"
    }
}
