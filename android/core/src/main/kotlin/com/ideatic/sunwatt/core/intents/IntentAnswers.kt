package com.ideatic.sunwatt.core.intents

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PricePoint
import com.ideatic.sunwatt.core.models.cheapestUpcoming
import com.ideatic.sunwatt.core.models.currentHour
import com.ideatic.sunwatt.core.models.level
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

// What the "price now", "solar now" and "cheapest hours" answers (Siri / Shortcuts on Apple; shortcuts, tiles and
// widgets on Android) and the two controls say, as values. No text: the app formats them with the `intents_*` catalog
// strings and its own number and time formats (port of the data side of Swift `IntentAnswers.swift`).

/** "What is the electricity price now?" */
data class PriceAnswer(
    /** Current price in EUR/kWh; null when there is none. */
    val eurPerKWh: Double?,
    val level: PriceLevel?,
) {
    /** False: there is no price for the current hour. */
    val isAvailable: Boolean get() = eurPerKWh != null

    companion object {
        fun make(data: SunWattData, now: Instant): PriceAnswer {
            val series = data.prices ?: return PriceAnswer(null, null)
            val point = series.currentHour(now) ?: return PriceAnswer(null, null)
            return PriceAnswer(point.eurPerKWh, series.level(point))
        }
    }
}

/** "How much solar am I producing?" */
data class SolarAnswer(
    val outcome: Outcome,
    /** Power now (kW), or the total of all plants; null when [outcome] is not producing or idle. */
    val currentKW: Double?,
    /** Energy today (kWh), or the total of all plants. */
    val todayKWh: Double?,
    /** True with several plants: the figures above are totals and [plants] lists each one. */
    val isTotal: Boolean = false,
    /** Each plant, in display order. Only with several plants (empty with one). */
    val plants: List<PlantLine> = emptyList(),
    /** True when Huawei rejected the kiosk link long enough to call it expired (outcome is [Outcome.UNAVAILABLE]). */
    val linkExpired: Boolean = false,
    /** Set when the reading is older than [Constants.staleAfter]: the time of the reading to tell the user. */
    val lastReading: Instant? = null,
) {
    enum class Outcome { PRODUCING, IDLE, NOT_CONFIGURED, UNAVAILABLE }

    /** One plant of a several-plant answer. */
    data class PlantLine(val name: String, val state: State, val kW: Double?) {
        enum class State { NO_DATA, NOT_PRODUCING, PRODUCING }
    }

    companion object {
        fun make(data: SunWattData, now: Instant): SolarAnswer {
            if (data.hasSeveralPlants) return several(data, now)
            if (data.solarStatus is SourceStatus.NotConfigured) return SolarAnswer(Outcome.NOT_CONFIGURED, null, null)
            if (data.solarLinkExpired) return SolarAnswer(Outcome.UNAVAILABLE, null, null, linkExpired = true)
            // A snapshot from yesterday has no power or daily energy for today (see `SunWattEntry.solarForDisplay`).
            val solar = SunWattEntry(now, data).solarForDisplay
            val power = solar?.currentPowerKW
            if (solar == null || power == null) return SolarAnswer(Outcome.UNAVAILABLE, null, null)
            return SolarAnswer(
                outcome = if (solar.isIdle) Outcome.IDLE else Outcome.PRODUCING,
                currentKW = power, todayKWh = solar.dailyEnergyKWh,
                lastReading = if (isOld(solar.fetchedAt, now)) solar.dataAsOf ?: solar.fetchedAt else null,
            )
        }

        /** Several plants: the total, then each plant. */
        private fun several(data: SunWattData, now: Instant): SolarAnswer {
            val entry = SunWattEntry(now, data)
            val plants = entry.plantsForDisplay
            if (plants.all { it.status is SourceStatus.NotConfigured }) return SolarAnswer(Outcome.NOT_CONFIGURED, null, null)
            val total = entry.combinedSolarForDisplay
            val power = total?.currentPowerKW
            if (total == null || power == null) return SolarAnswer(Outcome.UNAVAILABLE, null, null)
            val lines = plants.map { plant ->
                val kW = plant.solar?.currentPowerKW
                when {
                    plant.solar == null || kW == null -> PlantLine(plant.name, PlantLine.State.NO_DATA, null)
                    plant.solar.isIdle -> PlantLine(plant.name, PlantLine.State.NOT_PRODUCING, kW)
                    else -> PlantLine(plant.name, PlantLine.State.PRODUCING, kW)
                }
            }
            return SolarAnswer(
                outcome = if (total.isIdle) Outcome.IDLE else Outcome.PRODUCING,
                currentKW = power, todayKWh = total.dailyEnergyKWh, isTotal = true, plants = lines,
                lastReading = if (isOld(total.fetchedAt, now)) total.dataAsOf ?: total.fetchedAt else null,
            )
        }

        private fun isOld(fetchedAt: Instant, now: Instant): Boolean =
            Duration.between(fetchedAt, now).toKotlinDuration() > Constants.staleAfter
    }
}

/** "When is electricity cheapest?" */
data class CheapestHoursAnswer(
    /** The cheapest upcoming hours, in time order. Empty: no upcoming prices. */
    val hours: List<Hour>,
    /** The lowest price among [hours] (EUR/kWh); null when empty. */
    val lowestEurPerKWh: Double?,
) {
    /** One of the cheapest hours. */
    data class Hour(
        val point: PricePoint,
        /** False when the hour falls on the next market day (the answer says "tomorrow"). */
        val isToday: Boolean,
    )

    companion object {
        fun make(data: SunWattData, now: Instant, count: Int = 3): CheapestHoursAnswer {
            val series = data.prices ?: return CheapestHoursAnswer(emptyList(), null)
            val cheapest = series.cheapestUpcoming(now, count)
            val lowest = cheapest.minOfOrNull { it.eurPerKWh } ?: return CheapestHoursAnswer(emptyList(), null)
            // The ranking spans today and tomorrow, in the market's time zone.
            val zone = series.region.timeZone
            val today = now.atZone(zone).toLocalDate()
            return CheapestHoursAnswer(
                hours = cheapest.map { Hour(it, it.start.atZone(zone).toLocalDate() == today) },
                lowestEurPerKWh = lowest,
            )
        }
    }
}

/** What the two controls (Quick Settings tiles on Android) show, as values. */
sealed interface ControlContent {
    /** Price control: the current price and its level; both null: "No data / Tap to refresh". */
    data class Price(val eurPerKWh: Double?, val level: PriceLevel?) : ControlContent

    /** Solar control. */
    data class Solar(val state: State, val powerKW: Double? = null, val todayKWh: Double? = null) : ControlContent {
        enum class State { NOT_SET_UP, NO_DATA, NO_PRODUCTION, PRODUCING }

        /** No production now (night): the control shows a moon. */
        val isIdle: Boolean get() = state == State.NO_PRODUCTION
    }

    companion object {
        fun price(data: SunWattData, now: Instant): Price {
            val answer = PriceAnswer.make(data, now)
            return Price(answer.eurPerKWh, answer.level)
        }

        fun solar(data: SunWattData, now: Instant): Solar {
            if (data.solarStatus is SourceStatus.NotConfigured) return Solar(Solar.State.NOT_SET_UP)
            // A snapshot from yesterday has no power or daily energy for today. Several plants: their total.
            val solar = SunWattEntry(now, data).combinedSolarForDisplay
            val power = solar?.currentPowerKW
            if (solar == null || power == null) return Solar(Solar.State.NO_DATA)
            val today = solar.dailyEnergyKWh
            return if (solar.isIdle) Solar(Solar.State.NO_PRODUCTION, powerKW = power, todayKWh = today)
            else Solar(Solar.State.PRODUCING, powerKW = power, todayKWh = today)
        }
    }
}
