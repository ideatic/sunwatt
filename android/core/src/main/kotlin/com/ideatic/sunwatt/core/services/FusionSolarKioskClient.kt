package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.SolarSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.math.min

/** Fetches and parses the FusionSolar public kiosk. */
class FusionSolarKioskClient(
    val endpoint: KioskEndpoint,
    private val http: HttpClient = OkHttpSunWattClient(),
    private val now: () -> Instant = Instant::now,
) : SolarDataSource {
    override suspend fun fetch(): SolarSnapshot {
        val request = HttpFetch.request(endpoint.apiURL)
        val response = HttpFetch.perform(http, request, redacting = endpoint.kk)
        if (response.status in listOf(401, 403, 404)) throw SunWattError.KioskLinkRejected
        HttpFetch.checkStatus(response)
        val date = now()
        return parse(response.body, fetchedAt = date, day = date)
    }

    /** Never prints the token. */
    override fun toString(): String = "FusionSolarKioskClient($endpoint)"

    companion object {
        /**
         * Parses a raw kiosk response body (outer JSON with HTML-escaped `data` string).
         * Curve points later than [fetchedAt] + 5 min lose their value (stale yesterday curve).
         * [day] is any instant in the Europe/Madrid day the curve labels ("HH:mm") belong to.
         */
        fun parse(data: ByteArray, fetchedAt: Instant, day: Instant): SolarSnapshot {
            val outer = parseJsonObject(data.toString(Charsets.UTF_8)) ?: throw SunWattError.BadResponse("outer JSON")
            // Huawei answers success: false for a link that no longer works (expired after a year, or kiosk turned off).
            val success = outer["success"] as? JsonPrimitive
            if (success == null || success.isString || success.content != "true") throw SunWattError.KioskLinkRejected
            val escaped = (outer["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (escaped.isNullOrEmpty()) throw SunWattError.BadResponse("missing data")
            val root = parseJsonObject(HtmlEntities.unescape(escaped)) ?: throw SunWattError.BadResponse("data is not JSON")

            val kpiObject = root["realKpi"] as? JsonObject
            val powerCurve = root["powerCurve"] as? JsonObject
            if (kpiObject == null && powerCurve == null) throw SunWattError.BadResponse("no realKpi or powerCurve")
            val kpi = kpiObject ?: JsonObject(emptyMap())
            val curveObject = powerCurve ?: JsonObject(emptyMap())
            // Labels that are not strings are dropped (as in the Apple app), values keep their place.
            val labels = (curveObject["xAxis"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
            val values = (curveObject["activePower"] as? JsonArray)?.toList() ?: emptyList()
            val station = ((root["stationOverview"] as? JsonObject)?.get("stationName") as? JsonPrimitive)?.takeIf { it.isString }?.content

            // Near midnight Huawei may still serve yesterday's curve: drop values from the future.
            val limit = fetchedAt.plusSeconds(5 * 60)
            val curve = curvePoints(labels, values, day).map { if (it.time > limit) CurvePoint(it.time, null) else it }
            return SolarSnapshot(
                stationName = station?.ifEmpty { null },
                currentPowerKW = number(kpi["realTimePower"]),
                dailyEnergyKWh = number(kpi["dailyEnergy"]),
                monthEnergyKWh = number(kpi["monthEnergy"]),
                yearEnergyKWh = number(kpi["yearEnergy"]),
                cumulativeEnergyKWh = number(kpi["cumulativeEnergy"]),
                curve = curve,
                fetchedAt = fetchedAt,
                dataAsOf = curve.lastOrNull { it.kW != null }?.time,
            ).clampingProduction()
        }

        /** Lenient number: JSON number or numeric string. Anything else (`"-"`, `""`, null) is null. */
        fun number(value: JsonElement?): Double? {
            val primitive = value as? JsonPrimitive ?: return null
            if (value is JsonNull) return null
            return number(primitive.content)
        }

        /** Lenient number from text; null for anything that is not a plain decimal, and for NaN or infinity. */
        fun number(text: String?): Double? {
            val trimmed = text?.trim { it == ' ' || it == '\t' } ?: return null
            // Plain decimals only: Java would also accept "1.5f", "0x1p3" or "Infinity".
            if (!decimal.matches(trimmed)) return null
            return trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        private val decimal = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

        /** Parses [text] as a JSON object; null when it is not valid JSON or not an object. */
        internal fun parseJsonObject(text: String): JsonObject? = parseJson(text) as? JsonObject

        internal fun parseJson(text: String): JsonElement? =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun curvePoints(labels: List<String>, values: List<JsonElement>, day: Instant): List<CurvePoint> {
            val zone = Constants.madrid
            val date = day.atZone(zone).toLocalDate()
            val firstInstant = HashMap<String, Instant>()
            val points = ArrayList<CurvePoint>()
            for (i in 0 until min(labels.size, values.size)) {
                val label = labels[i]
                val parts = label.split(':').filter { it.isNotEmpty() }
                if (parts.size != 2) continue
                val h = SolarLocalTime.int(parts[0]) ?: continue
                val m = SolarLocalTime.int(parts[1]) ?: continue
                if (h !in 0 until 24 || m !in 0 until 60) continue
                fun matches(instant: Instant): Boolean {
                    val local = instant.atZone(zone)
                    return local.hour == h && local.minute == m && local.toLocalDate() == date
                }
                val first = firstInstant[label]
                val time: Instant? = if (first != null) {
                    // Repeated label (fall-back day): the later instant.
                    first.plusSeconds(3600).takeIf { matches(it) }
                } else {
                    val local = date.atTime(h, m)
                    // Nonexistent label (spring-forward gap): skip. In the repeated hour, the first instant.
                    if (zone.rules.getValidOffsets(local).isEmpty()) {
                        null
                    } else {
                        local.atZone(zone).toInstant().also { firstInstant[label] = it }
                    }
                }
                if (time == null) continue
                points.add(CurvePoint(time, number(values[i])))
            }
            return points.sortedBy { it.time }
        }
    }
}
