package com.ideatic.sunwatt.core.timeline

import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.services.PriceDataSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration

/** Source compatibility with the Swift name: `fetchTodayAndTomorrow(includeTomorrow)` is part of [PriceDataSource]. */
typealias PriceRangeDataSource = PriceDataSource

/** Which plant a widget shows when there are several. */
sealed interface WidgetPlantChoice {
    /** Each plant in turn, switching every [TimelineBuilder.rotationInterval]. */
    data object Rotate : WidgetPlantChoice

    /** Always this plant ([com.ideatic.sunwatt.core.models.SolarPlant.id]); the first one when it no longer exists. */
    data class Plant(val id: String) : WidgetPlantChoice
}

/** Pure timeline generation, testable without a widget host. The Glance widget and the worker use these entries. */
object TimelineBuilder {
    /** How long a rotating widget shows one plant (the widget refresh interval). */
    val rotationInterval: Duration = Constants.refreshInterval

    /**
     * Swift counts rotation steps from 2001-01-01 UTC (`timeIntervalSinceReferenceDate`). Kept, so both apps agree on
     * whose turn it is.
     */
    private const val REFERENCE_EPOCH_SECONDS = 978_307_200L

    /**
     * Entries for a widget. One plant: the hourly entries, unchanged. Several: a fixed plant gets the same hourly
     * entries with that plant shown; rotating adds an entry at every quarter hour, each showing the plant whose turn it
     * is. The turn depends only on the time, so every widget and every reload agree on it.
     */
    fun entries(data: SunWattData, now: Instant, plant: WidgetPlantChoice, hours: Int = 24): List<SunWattEntry> {
        if (!data.hasSeveralPlants) return entries(data, now, hours)
        return when (plant) {
            is WidgetPlantChoice.Plant -> {
                val shown = if (data.allPlants.any { it.id == plant.id }) data.showingPlant(plant.id) else data
                entries(shown, now, hours)
            }
            WidgetPlantChoice.Rotate -> {
                val count = data.allPlants.size
                val intervalSeconds = rotationInterval.inWholeSeconds
                val first = Math.floorDiv(now.epochSecond - REFERENCE_EPOCH_SECONDS, intervalSeconds)
                val steps = (hours * 3600L / intervalSeconds).toInt()
                (0..steps).map { step ->
                    val date = if (step == 0) now
                    else Instant.ofEpochSecond(REFERENCE_EPOCH_SECONDS + (first + step) * intervalSeconds)
                    SunWattEntry(date, data.showingPlant(turn(date, count)))
                }
            }
        }
    }

    /** Index of the plant shown at [date] by a rotating widget. */
    fun turn(date: Instant, plants: Int): Int {
        if (plants <= 1) return 0
        val steps = Math.floorDiv(date.epochSecond - REFERENCE_EPOCH_SECONDS, rotationInterval.inWholeSeconds)
        return Math.floorMod(steps, plants.toLong()).toInt()
    }

    /**
     * Entries: one at [now], then one at every following Europe/Madrid hour start for [hours] hours, so the current-hour
     * price and highlight advance without a fetch. DST-safe (23/25-hour days).
     */
    fun entries(data: SunWattData, now: Instant, hours: Int = 24): List<SunWattEntry> {
        val result = mutableListOf(SunWattEntry(now, data))
        if (hours <= 0) return result
        // Madrid DST shifts are whole hours, so absolute 3600 s steps stay on wall-clock hour starts.
        val firstBoundary = now.truncatedTo(ChronoUnit.HOURS).plusSeconds(3600)
        for (i in 0 until hours) result += SunWattEntry(firstBoundary.plusSeconds(i * 3600L), data)
        return result
    }

    /** When to ask the host for the next reload: `now + Constants.refreshInterval`. */
    fun nextReload(now: Instant): Instant = now.plusSeconds(Constants.refreshInterval.inWholeSeconds)
}
