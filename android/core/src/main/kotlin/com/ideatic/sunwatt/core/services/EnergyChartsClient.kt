package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceSeriesBuilding
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Day-ahead wholesale prices from Energy-Charts (Fraunhofer ISE, CC BY 4.0) for every region except Spain.
 *
 * `GET https://api.energy-charts.info/price?bzn=<zone>&start=yyyy-MM-dd&end=yyyy-MM-dd` answers
 * `{"unix_seconds": [...], "price": [...], "unit": "EUR / MWh", ...}` with 15-minute points (96 per day, 92/100 on DST
 * days). The API rate-limits hard (HTTP 429), so today and tomorrow are one request.
 */
class EnergyChartsClient(
    val region: PriceRegion,
    private val http: HttpClient = OkHttpSunWattClient(),
    private val now: () -> Instant = Instant::now,
) : PriceDataSource {

    /** A market day of the region as `[start, end)`. */
    private class Day(val date: LocalDate, val start: Instant, val end: Instant)

    private fun dayOf(date: LocalDate): Day {
        val zone = region.timeZone
        return Day(date, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
    }

    override suspend fun fetch(day: Instant): PriceSeries {
        val market = dayOf(day.atZone(region.timeZone).toLocalDate())
        return load(market.date, market.date, market.start, market.end)
    }

    /**
     * One request for today and tomorrow (market days of the region). When tomorrow is not published yet, the answer
     * holds today only. With `includeTomorrow == false` the series is trimmed to today.
     */
    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries {
        val current = now()
        val today = dayOf(current.atZone(region.timeZone).toLocalDate())
        val tomorrow = dayOf(today.date.plusDays(1))
        val windowEnd = if (includeTomorrow) tomorrow.end else today.end
        return load(today.date, tomorrow.date, today.start, windowEnd, current)
    }

    private suspend fun load(first: LocalDate, last: LocalDate, from: Instant, until: Instant, fetchedAt: Instant? = null): PriceSeries {
        val response = HttpFetch.perform(http, HttpFetch.request(url(region, first, last)))
        if (response.status == 404) throw SunWattError.Unavailable
        HttpFetch.checkStatus(response)
        return parse(response.body, region, fetchedAt ?: now(), from to until)
    }

    companion object {
        const val ENDPOINT = "https://api.energy-charts.info/price"

        /**
         * Parses an Energy-Charts price response. Points outside [window] (`start <= t < end`) are dropped; null prices
         * are skipped.
         */
        fun parse(data: ByteArray, region: PriceRegion, fetchedAt: Instant, window: Pair<Instant, Instant>? = null): PriceSeries {
            val root = try {
                Json.parseToJsonElement(data.decodeToString()) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: throw SunWattError.BadResponse("invalid JSON")
            val unit = (root["unit"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SunWattError.BadResponse("missing unit")
            if (unit.lowercase().filter { !it.isWhitespace() } != "eur/mwh") throw SunWattError.BadResponse("unit $unit")
            val seconds = root["unix_seconds"] as? JsonArray
            val prices = root["price"] as? JsonArray
            if (seconds == null || prices == null) throw SunWattError.BadResponse("missing price arrays")

            // Entries without a number (null prices) are skipped.
            val raw = ArrayList<Pair<Instant, Double>>()
            for ((s, p) in seconds.zip(prices)) {
                val t = s.asNumber()?.takeIf { it.isFinite() } ?: continue
                val price = p.asNumber()?.takeIf { it.isFinite() } ?: continue
                val start = Instant.ofEpochMilli((t * 1000).roundToLong())
                if (window != null && !(start >= window.first && start < window.second)) continue
                raw.add(start to price)
            }
            raw.sortBy { it.first }
            val unique = ArrayList<Pair<Instant, Double>>()
            for (r in raw) if (unique.lastOrNull()?.first != r.first) unique.add(r)
            if (unique.isEmpty()) throw SunWattError.Unavailable
            return PriceSeries(
                points = PriceSeriesBuilding.makePoints(unique),
                granularity = PriceSeriesBuilding.granularity(unique.map { it.first }),
                fetchedAt = fetchedAt,
                sourceUpdatedAt = null,
                region = region,
            )
        }

        /** The request for market days [first]..[last] of [region]'s bidding zone. */
        internal fun url(region: PriceRegion, first: LocalDate, last: LocalDate): String {
            val zone = region.energyChartsZone ?: throw SunWattError.BadResponse("unsupported region")
            return "$ENDPOINT?bzn=$zone&start=$first&end=$last"
        }

        private fun JsonElement.asNumber(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    }
}
