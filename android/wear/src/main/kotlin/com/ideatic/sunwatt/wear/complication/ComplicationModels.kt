package com.ideatic.sunwatt.wear.complication

import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.format.Format
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.wear.model.PriceModel
import com.ideatic.sunwatt.wear.model.SolarModel
import java.util.Locale

/** Day range for the price gauge: the current price within today's min...max (Swift `WatchCornerGauge`). */
data class PriceGauge(val value: Double, val lower: Double, val upper: Double, val fraction: Double) {
    companion object {
        fun of(entry: SunWattEntry): PriceGauge? {
            val price = PriceModel.of(entry)
            val stats = price.stats ?: return null
            val current = price.current ?: return null
            if (price.series == null) return null
            val upper = maxOf(stats.max, stats.min + 0.001)
            return PriceGauge(
                value = current.eurPerKWh.coerceIn(stats.min, upper),
                lower = stats.min,
                upper = upper,
                fraction = price.fraction ?: 0.5,
            )
        }
    }
}

/**
 * Ring of the solar gauge: current power within 0...today's peak (at least 1 kW, so a dull morning does not fill the
 * ring). At night the ring is empty (Swift `WatchSolarGauge`).
 */
data class SolarGauge(val value: Double, val upper: Double, val fraction: Double) {
    companion object {
        fun of(entry: SunWattEntry): SolarGauge? {
            val solar = SolarModel(entry)
            if (!solar.isAvailable) return null
            val upper = maxOf(solar.snapshot?.peakKW ?: 0.0, 1.0)
            val value = (solar.powerKW ?: 0.0).coerceIn(0.0, upper)
            return SolarGauge(value, upper, value / upper)
        }
    }
}

/** What the solar complication shows for one entry (Swift `WatchSolarState`). */
sealed interface WatchSolarState {
    data object NotConfigured : WatchSolarState
    data object Error : WatchSolarState

    /** Today's energy and power are known ([idle] at night or without production). */
    data class Data(val idle: Boolean, val stale: Boolean) : WatchSolarState

    /** The snapshot belongs to yesterday: nothing for today yet. */
    data object NoDataYet : WatchSolarState

    companion object {
        fun of(entry: SunWattEntry): WatchSolarState {
            val solar = SolarModel(entry)
            return when (solar.phase) {
                SolarModel.Phase.NOT_CONFIGURED -> NotConfigured
                SolarModel.Phase.ERROR -> Error
                SolarModel.Phase.ACTIVE, SolarModel.Phase.IDLE ->
                    if (entry.isSolarFromEarlierDay) NoDataYet else Data(solar.isIdle, solar.staleSince != null)
            }
        }
    }
}

/** The numbers and words of the price complication for one entry. */
class PriceComplicationModel(entry: SunWattEntry, locale: Locale = Locale.getDefault()) {
    val gauge: PriceGauge? = PriceGauge.of(entry)
    private val price = PriceModel.of(entry)

    val isAvailable: Boolean get() = gauge != null

    /** "0,177": the current price. */
    val valueText: String? = if (gauge == null) null else Format.eurPerKWh(price.current?.eurPerKWh, locale = locale)

    val level: PriceLevel? get() = price.level

    /** 0 (cheapest of the day) ... 1 (dearest). */
    val fraction: Double get() = gauge?.fraction ?: 0.5

    companion object {
        const val UNIT = "€/kWh"
    }
}

/** The numbers of the solar complication for one entry. */
class SolarComplicationModel(entry: SunWattEntry, locale: Locale = Locale.getDefault()) {
    val state: WatchSolarState = WatchSolarState.of(entry)
    val gauge: SolarGauge? = SolarGauge.of(entry)
    private val solar = SolarModel(entry)

    /** Today's kWh; "0,0" when there is no data for today yet. */
    val energyText: String = Format.kWh(if (state == WatchSolarState.NoDataYet) 0.0 else solar.dailyKWh, locale)

    /** Power now in kW. */
    val powerText: String = Format.kW(solar.powerKW, locale)

    /** Time of the data ("13:30"), in the solar time zone. */
    val timeText: String? = solar.dataTime?.let { Format.time(it, Constants.madrid, locale).replace('\u202f', ' ') }

    /** True at night, without production, or when the data is stale or from yesterday (the sun symbol is muted). */
    val muted: Boolean
        get() = when (val s = state) {
            is WatchSolarState.Data -> s.idle || s.stale
            else -> true
        }

    val isNight: Boolean
        get() = when (val s = state) {
            is WatchSolarState.Data -> s.idle
            WatchSolarState.NoDataYet -> true
            else -> false
        }
}
