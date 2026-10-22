package com.ideatic.sunwatt.shortcuts

import android.content.Context
import android.icu.text.ListFormatter
import androidx.annotation.StringRes
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.intents.CheapestHoursAnswer
import com.ideatic.sunwatt.core.intents.PriceAnswer
import com.ideatic.sunwatt.core.intents.SolarAnswer
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.widget.widgetLabel
import java.time.ZoneId
import java.util.Locale

/**
 * Catalog texts and number formats for the spoken answers (port of the Swift `IntentStrings`). Plain interface so the
 * answer texts are tested on the JVM with the generated catalog.
 */
interface AnswerStrings {
    val locale: Locale

    fun text(@StringRes id: Int, vararg args: Any): String

    /** "a, b and c" in the user's language. */
    fun list(items: List<String>): String
}

class ContextAnswerStrings(private val context: Context) : AnswerStrings {
    override val locale: Locale get() = context.resources.configuration.locales[0]

    override fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun list(items: List<String>): String = ListFormatter.getInstance(locale).format(items)
}

/** The spoken answers: the same sentences as the Siri dialogs of the Apple app. */
class AnswerTexts(private val strings: AnswerStrings) {
    private fun eur(value: Double) = Format.eurPerKWh(value, locale = strings.locale)
    private fun kW(value: Double) = Format.kW(value, strings.locale)
    private fun kWh(value: Double) = Format.kWh(value, strings.locale)

    fun levelName(level: PriceLevel): String = strings.text(level.widgetLabel().id)

    fun price(answer: PriceAnswer): String {
        val value = answer.eurPerKWh
        val level = answer.level
        if (value == null || level == null) return strings.text(R.string.intents_i_cannot_get_the_electricity_price)
        val id = when (level) {
            PriceLevel.LOW -> R.string.intents_the_electricity_price_now_is_kwh_940d85
            PriceLevel.MEDIUM -> R.string.intents_the_electricity_price_now_is_kwh_83ee16
            PriceLevel.HIGH -> R.string.intents_the_electricity_price_now_is_kwh_f61aa2
        }
        return strings.text(id, eur(value))
    }

    fun solar(answer: SolarAnswer): String {
        val outcome = when (answer.outcome) {
            SolarAnswer.Outcome.NOT_CONFIGURED -> strings.text(R.string.intents_solar_production_is_not_set_up)
            SolarAnswer.Outcome.UNAVAILABLE ->
                if (answer.linkExpired) strings.text(R.string.intents_your_fusionsolar_kiosk_link_has_expired)
                else strings.text(R.string.intents_i_cannot_get_the_solar_production)
            SolarAnswer.Outcome.IDLE, SolarAnswer.Outcome.PRODUCING -> producing(answer)
        }
        val last = answer.lastReading?.let { strings.text(R.string.intents_the_last_reading_is_from, time(it)) }
        return listOfNotNull(outcome, last).joinToString(" ")
    }

    private fun producing(answer: SolarAnswer): String {
        val power = checkNotNull(answer.currentKW)
        val today = answer.todayKWh
        val idle = answer.outcome == SolarAnswer.Outcome.IDLE
        val head = if (answer.isTotal) {
            when {
                idle && today != null -> strings.text(R.string.intents_your_plants_are_not_producing_now_c647f2, kWh(today))
                idle -> strings.text(R.string.intents_your_plants_are_not_producing_now_b2a96b)
                today != null -> strings.text(R.string.intents_your_plants_are_producing_kw_in_4f81a6, kW(power), kWh(today))
                else -> strings.text(R.string.intents_your_plants_are_producing_kw_in_63a3ec, kW(power))
            }
        } else {
            when {
                idle && today != null -> strings.text(R.string.intents_the_plant_is_not_producing_now_a40cd9, kWh(today))
                idle -> strings.text(R.string.intents_the_plant_is_not_producing_now_041eff)
                today != null -> strings.text(R.string.intents_solar_production_now_is_kw_today, kW(power), kWh(today))
                else -> strings.text(R.string.intents_solar_production_now_is_kw, kW(power))
            }
        }
        if (answer.plants.isEmpty()) return head
        val items = answer.plants.map { plant ->
            val value = plant.kW
            when (plant.state) {
                SolarAnswer.PlantLine.State.NO_DATA -> strings.text(R.string.intents_no_data_803583, plant.name)
                SolarAnswer.PlantLine.State.NOT_PRODUCING -> strings.text(R.string.intents_not_producing, plant.name)
                SolarAnswer.PlantLine.State.PRODUCING -> strings.text(R.string.intents_kw, plant.name, kW(checkNotNull(value)))
            }
        }
        return "$head ${strings.text(R.string.intents_by_plant, strings.list(items))}"
    }

    fun cheapestHours(answer: CheapestHoursAnswer, zone: ZoneId): String {
        val lowest = answer.lowestEurPerKWh
        if (answer.hours.isEmpty() || lowest == null) return strings.text(R.string.intents_i_cannot_find_upcoming_electricity_prices)
        // Hours of tomorrow say so ("3 h tomorrow").
        val labels = answer.hours.map { hour ->
            val label = Format.hour(hour.point.start, zone)
            if (hour.isToday) label else strings.text(R.string.intents_tomorrow, label)
        }
        return if (labels.size == 1) strings.text(R.string.intents_the_cheapest_hour_coming_up_is, labels[0], eur(lowest))
        else strings.text(R.string.intents_the_cheapest_hours_coming_up_are, strings.list(labels), eur(lowest))
    }

    /** Reading time in the solar time zone (Europe/Madrid), in the user's clock style. */
    private fun time(at: java.time.Instant): String = Format.time(at, locale = strings.locale)
}
