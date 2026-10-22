package com.ideatic.sunwatt.core.format

import com.ideatic.sunwatt.core.models.Constants
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Energy with its unit, as [Format.energy] returns it. */
data class EnergyText(val value: String, val unit: String)

/**
 * Formatting for all UI figures. Follows the given locale (default: the device's current one; Spanish: comma decimal
 * separator). Times are in the data time zone. Port of the Swift `Format`; every function takes the locale so tests
 * and the app control it.
 */
object Format {
    /** Shown instead of a missing number. */
    const val MISSING = "–"

    /**
     * Locales whose CLDR "minimum grouping digits" is 2: they write 1234 without a separator and 12.345 with one. The
     * Java number formats do not know the rule (ICU, used by the Swift app, does), so the separator is dropped for
     * numbers below 10000.
     */
    private fun skipsGroupingBelow10000(locale: Locale): Boolean =
        locale.language == "es" || locale.language == "pl" || (locale.language == "pt" && locale.country == "PT")

    /**
     * [value] with exactly [digits] decimals. Rounds half-even on the shortest decimal text of the double, the way ICU
     * does, so the same figure gives the same text as in the Apple app.
     */
    private fun number(value: Double, digits: Int, locale: Locale): String {
        val format = NumberFormat.getNumberInstance(locale)
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        if (abs(value) < 10_000 && skipsGroupingBelow10000(locale)) format.isGroupingUsed = false
        return if (value.isFinite()) format.format(BigDecimal(value.toString())) else format.format(value)
    }

    /** "4,8" (1 decimal for < 10 kW, 0 decimals above). */
    fun kW(value: Double?, locale: Locale = Locale.getDefault()): String {
        value ?: return MISSING
        return number(value, if (abs(value) < 10) 1 else 0, locale)
    }

    /** "23,4" kWh (1 decimal under 1000, grouped integer above). */
    fun kWh(value: Double?, locale: Locale = Locale.getDefault()): String {
        value ?: return MISSING
        return number(value, if (abs(value) < 1000) 1 else 0, locale)
    }

    /**
     * Energy with its unit: kWh as [kWh] below 10.000 kWh, else MWh with 1 decimal ("17,1" MWh), so totals such as a
     * year's production stay short.
     */
    fun energy(kWh: Double?, locale: Locale = Locale.getDefault()): EnergyText {
        if (kWh == null || abs(kWh) < 10_000) return EnergyText(kWh(kWh, locale), "kWh")
        return EnergyText(number(kWh / 1000, 1, locale), "MWh")
    }

    /** "0,147" €/kWh (3 decimals by default). */
    fun eurPerKWh(value: Double?, decimals: Int = 3, locale: Locale = Locale.getDefault()): String {
        value ?: return MISSING
        // A value that rounds to zero must not print as "-0,000".
        val rounded = abs(value * 10.0.pow(decimals)) < 0.5
        return number(if (rounded) 0.0 else value, decimals, locale)
    }

    /**
     * "14:30" in [zone] (Europe/Madrid by default); "2:30 PM" in 12-hour locales, where "02:30" alone would read as night.
     */
    fun time(date: Instant, zone: ZoneId = Constants.madrid, locale: Locale = Locale.getDefault()): String {
        val pattern = if (usesTwelveHourClock(locale)) "h:mm a" else "HH:mm"
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(date)
    }

    /** True when the locale's preferred hour format has an AM/PM marker (en_US), false for 24-hour locales (es, en_GB). */
    fun usesTwelveHourClock(locale: Locale): Boolean =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
            .contains('a')

    /** "14 h" hour label in [zone] (Europe/Madrid by default). */
    fun hour(date: Instant, zone: ZoneId = Constants.madrid): String = "${date.atZone(zone).hour} h"
}
