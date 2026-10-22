package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.PriceSeriesBuilding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Fetches PVPC prices from Red Eléctrica (apidatos.ree.es), no token. Keeps the [PriceDataSource] contract. */
class PvpcClient(
    private val http: HttpClient = OkHttpSunWattClient(),
    private val now: () -> Instant = Instant::now,
) : PriceDataSource {

    override suspend fun fetch(day: Instant): PriceSeries {
        val request = HttpFetch.request(url(day))
        val response = HttpFetch.perform(http, request)
        // An error body means "not published yet", whatever the status.
        if (hasErrorsBody(response.body)) throw SunWattError.Unavailable
        HttpFetch.checkStatus(response)
        return parse(response.body, now())
    }

    /** Today, plus tomorrow when [includeTomorrow] and REE has published it. A failure for tomorrow must NOT fail the whole call. */
    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries {
        val current = now()
        if (!includeTomorrow) return fetch(current)
        val zone = Constants.madrid
        val tomorrow = current.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
        return coroutineScope {
            val todayResult = async { fetch(current) }
            val tomorrowResult = async { attempt { fetch(tomorrow) } }
            val today = todayResult.await()
            val next = tomorrowResult.await() ?: return@coroutineScope today
            PriceSeriesBuilding.merged(listOf(today, next), current)
        }
    }

    private suspend fun <T : Any> attempt(work: suspend () -> T): T? = try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ENDPOINT = "https://apidatos.ree.es/es/datos/mercados/precios-mercados-tiempo-real"

        /** Parses an REE response body, picking the `included[]` entry of type "PVPC". */
        fun parse(data: ByteArray, fetchedAt: Instant): PriceSeries {
            val root = try {
                Json.parseToJsonElement(data.decodeToString())
            } catch (e: SerializationException) {
                throw SunWattError.BadResponse("invalid JSON")
            }
            val dict = root as? JsonObject ?: throw SunWattError.BadResponse("unexpected JSON")
            if ((dict["errors"] as? JsonArray)?.isNotEmpty() == true) throw SunWattError.Unavailable
            val included = (dict["included"] as? JsonArray)?.map { it as? JsonObject }
            if (included == null || included.any { it == null }) throw SunWattError.BadResponse("missing included")
            val entry = included.firstOrNull { (it?.get("type") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "PVPC" }
            val attrs = entry?.get("attributes") as? JsonObject ?: throw SunWattError.Unavailable
            val values = (attrs["values"] as? JsonArray)?.map { it as? JsonObject }
            if (values.isNullOrEmpty() || values.any { it == null }) throw SunWattError.Unavailable

            // Points whose value or date cannot be read are skipped.
            val raw = values.mapNotNull { item ->
                if (item == null) return@mapNotNull null
                val value = item["value"].asNumber()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                val date = item["datetime"].asText()?.let(::parseDate) ?: return@mapNotNull null
                date to value
            }.sortedBy { it.first }
            if (raw.isEmpty()) throw SunWattError.BadResponse("no valid price points")

            // Same start twice: the first wins.
            val unique = ArrayList<Pair<Instant, Double>>()
            for (r in raw) if (unique.lastOrNull()?.first != r.first) unique.add(r)

            val updated = attrs["last-update"].asText()?.let(::parseDate)
            return PriceSeries(
                points = PriceSeriesBuilding.makePoints(unique),
                granularity = PriceSeriesBuilding.granularity(unique.map { it.first }),
                fetchedAt = fetchedAt,
                sourceUpdatedAt = updated,
                region = PriceRegion.SPAIN,
            )
        }

        /** The request for the Madrid market day that contains [day]. */
        internal fun url(day: Instant): String {
            val date = PriceSeriesBuilding.dayString(day, Constants.madrid)
            return "$ENDPOINT?start_date=${date}T00:00&end_date=${date}T23:59&time_trunc=hour"
        }

        private fun hasErrorsBody(data: ByteArray): Boolean {
            val dict = try {
                Json.parseToJsonElement(data.decodeToString()) as? JsonObject
            } catch (e: SerializationException) {
                null
            }
            return (dict?.get("errors") as? JsonArray)?.isNotEmpty() == true
        }

        /** ISO 8601 with an explicit offset (`+02:00`, `Z`), with or without fractional seconds. */
        internal fun parseDate(text: String): Instant? = try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }

        private fun JsonElement?.asNumber(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
