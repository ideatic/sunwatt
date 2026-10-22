package com.ideatic.sunwatt.core.models

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One price interval. */
@Serializable
data class PricePoint(
    /** Start of the interval (absolute instant; the API sends an explicit UTC offset). */
    @Serializable(with = InstantIso8601Serializer::class) val start: Instant,
    /** Interval length in seconds: 3600 (hourly) or 900 (15-min). */
    val durationSeconds: Long,
    /** Price in €/MWh, as sent by the source. */
    val eurPerMWh: Double,
) {
    val duration: Duration get() = durationSeconds.seconds
    val end: Instant get() = start.plus(durationSeconds, ChronoUnit.SECONDS)

    /** Price in €/kWh (what the UI shows). */
    val eurPerKWh: Double get() = eurPerMWh / 1000

    operator fun contains(date: Instant): Boolean = date >= start && date < end
}

@Serializable(with = PriceGranularitySerializer::class)
enum class PriceGranularity(val rawValue: String) {
    HOURLY("hourly"),
    QUARTER_HOURLY("quarterHourly");

    companion object {
        fun fromRaw(raw: String): PriceGranularity? = entries.firstOrNull { it.rawValue == raw }
    }
}

object PriceGranularitySerializer :
    RawValueEnumSerializer<PriceGranularity>("PriceGranularity", PriceGranularity.entries, { it.rawValue })

/** Prices for one or more consecutive days (today, plus tomorrow when published). */
@Serializable
data class PriceSeries(
    /** Sorted by `start`, no duplicates. */
    val points: List<PricePoint>,
    val granularity: PriceGranularity,
    /** When this app fetched the data. */
    @Serializable(with = InstantIso8601Serializer::class) val fetchedAt: Instant,
    /** `last-update` reported by the source, if any. */
    @Serializable(with = InstantIso8601Serializer::class) val sourceUpdatedAt: Instant? = null,
    /** Market the prices belong to. Day/hour math uses `region.timeZone`. Missing in old caches: Spain. */
    val region: PriceRegion = PriceRegion.SPAIN,
)

/** Price level relative to a day's min...max range. */
@Serializable(with = PriceLevelSerializer::class)
enum class PriceLevel(val rawValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromRaw(raw: String): PriceLevel? = entries.firstOrNull { it.rawValue == raw }
    }
}

object PriceLevelSerializer : RawValueEnumSerializer<PriceLevel>("PriceLevel", PriceLevel.entries, { it.rawValue })

@Serializable
data class PriceStats(val min: Double, val max: Double, val average: Double)
