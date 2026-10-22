package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Reads a site from the SolarEdge Monitoring API (`monitoringapi.solaredge.com`, API key + site ID).
 * Two requests per fetch, sent together: `overview` (power now, energy today/month/year/lifetime, last update)
 * and `powerDetails` (today's 15-minute curves per meter: Production, and with a meter at home also Consumption,
 * FeedIn, Purchased, SelfConsumption). A response without a Production meter falls back to `power` (one more
 * request). SolarEdge allows 300 requests a day per site and per key (see `SolarProvider.minimumFetchInterval`).
 * Times come as "yyyy-MM-dd HH:mm:ss" in the site's time zone; [zone] must be that zone (default: the device's).
 */
class SolarEdgeClient(
    private val credentials: SolarAPICredentials,
    private val http: HttpClient = OkHttpSunWattClient(),
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : SolarDataSource {
    override suspend fun fetch(): SolarSnapshot {
        val date = now()
        val (overviewData, detailsData) = coroutineScope {
            val overview = async { get(overviewURL) }
            val details = async { get(powerURL(date, "powerDetails")) }
            overview.await() to details.await()
        }
        if (hasProduction(detailsData)) return parse(overviewData, detailsData, date, zone)
        // No Production meter in the details: read the inverter curve, keep the meter readings for consumption.
        val powerData = get(powerURL(date, "power"))
        return parse(overviewData, powerData, date, zone).copy(home = home(detailsData, date, zone))
    }

    internal val overviewURL: String get() = url("overview", emptyList())

    /** Today (in [zone]) from 00:00:00 to 23:59:59, for `power` or `powerDetails`. */
    internal fun powerURL(day: Instant, path: String = "powerDetails"): String {
        val local = day.atZone(zone).toLocalDate()
        val dayText = String.format(Locale.ROOT, "%04d-%02d-%02d", local.year, local.monthValue, local.dayOfMonth)
        return url(path, listOf("startTime" to "$dayText 00:00:00", "endTime" to "$dayText 23:59:59"))
    }

    private fun url(path: String, query: List<Pair<String, String>>): String {
        // The key is letters and digits only (`SolarAPICredentials`), so it needs no escaping. Spaces become %20.
        val items = (query + ("api_key" to credentials.apiKey)).joinToString("&") { (name, value) -> "$name=${value.replace(" ", "%20")}" }
        return "https://$HOST/site/${credentials.siteID}/$path?$items"
    }

    private suspend fun get(url: String): ByteArray {
        val response = HttpFetch.perform(http, HttpFetch.request(url), redacting = credentials.apiKey)
        if (response.status == 401 || response.status == 403) throw SunWattError.SolarCredentialsRejected
        if (response.status == 429) throw SunWattError.RateLimited
        HttpFetch.checkStatus(response)
        return response.body
    }

    /** Never prints the API key. */
    override fun toString(): String = "SolarEdgeClient($credentials)"

    companion object {
        const val HOST = "monitoringapi.solaredge.com"

        private val maxAgeNow: Duration = Duration.ofMinutes(30)

        /** Parses the `overview` and `power` (or `powerDetails`) bodies. Curve points later than [fetchedAt] + 5 min lose their value. */
        fun parse(overview: ByteArray, power: ByteArray, fetchedAt: Instant, zone: ZoneId): SolarSnapshot {
            val root = FusionSolarKioskClient.parseJsonObject(overview.toString(Charsets.UTF_8))
            val o = root?.get("overview") as? JsonObject ?: throw SunWattError.BadResponse("overview")
            fun energyKWh(key: String): Double? = SolarLocalTime.number((o[key] as? JsonObject)?.get("energy"))?.let { it / 1000 }
            val currentKW = SolarLocalTime.number((o["currentPower"] as? JsonObject)?.get("power"))?.let { it / 1000 }
            // At night the inverter stops reporting, so after midnight lastUpdateTime is still yesterday evening. That
            // time must not make a fresh snapshot look like yesterday's: only a time from the fetch day counts.
            val lastUpdate = string(o["lastUpdateTime"])
                ?.let { SolarLocalTime.parse(it, zone) }
                ?.takeIf { it.atZone(zone).toLocalDate() == fetchedAt.atZone(zone).toLocalDate() }

            // `power` (one curve) or `powerDetails` (one curve per meter).
            val curve: List<CurvePoint>
            var home: HomeEnergy? = null
            val details = meters(power)
            if (details != null) {
                val production = details.meters["Production"] ?: throw SunWattError.BadResponse("powerDetails")
                curve = curve(production, details.scale, fetchedAt, zone)
                home = home(power, fetchedAt, zone)
            } else {
                val powerRoot = FusionSolarKioskClient.parseJsonObject(power.toString(Charsets.UTF_8))
                val p = powerRoot?.get("power") as? JsonObject ?: throw SunWattError.BadResponse("power")
                curve = curve(objects(p["values"]), scale(p["unit"]), fetchedAt, zone)
            }

            return SolarSnapshot(
                stationName = null,
                currentPowerKW = currentKW,
                dailyEnergyKWh = energyKWh("lastDayData"),
                monthEnergyKWh = energyKWh("lastMonthData"),
                yearEnergyKWh = energyKWh("lastYearData"),
                cumulativeEnergyKWh = energyKWh("lifeTimeData"),
                curve = curve,
                fetchedAt = fetchedAt,
                dataAsOf = lastUpdate?.let { minOf(it, fetchedAt) } ?: curve.lastOrNull { it.kW != null }?.time,
                home = home,
            ).clampingProduction()
        }

        private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** The elements of a JSON array of objects; empty when it is anything else (also when one element is not an object). */
        private fun objects(value: JsonElement?): List<JsonObject> {
            val array = value as? JsonArray ?: return emptyList()
            return if (array.all { it is JsonObject }) array.map { it as JsonObject } else emptyList()
        }

        /** kW per unit. The unit is "W" in practice; "kW" and "MW" are handled in case a site reports them. */
        internal fun scale(unit: JsonElement?): Double =
            when ((string(unit) ?: "W").uppercase()) {
                "KW" -> 1.0
                "MW" -> 1000.0
                else -> 0.001
            }

        /** Points in kW; values later than [fetchedAt] + 5 min lose their value. */
        internal fun curve(values: List<JsonObject>, scale: Double, fetchedAt: Instant, zone: ZoneId): List<CurvePoint> {
            val limit = fetchedAt.plusSeconds(5 * 60)
            val points = values.mapNotNull { item ->
                val time = string(item["date"])?.let { SolarLocalTime.parse(it, zone) } ?: return@mapNotNull null
                CurvePoint(time, SolarLocalTime.number(item["value"])?.let { it * scale })
            }
            return SolarLocalTime.inOrder(points, { it.time }, { point, time -> CurvePoint(time, point.kW) }, zone)
                .map { if (it.time > limit) CurvePoint(it.time, null) else it }
        }

        internal class Meters(val meters: Map<String, List<JsonObject>>, val scale: Double)

        /**
         * The meters of a `powerDetails` body by type ("Production", "Consumption", "FeedIn", "Purchased", ...).
         * Null when the body is not `powerDetails`.
         */
        internal fun meters(data: ByteArray): Meters? {
            val root = FusionSolarKioskClient.parseJsonObject(data.toString(Charsets.UTF_8)) ?: return null
            val details = root["powerDetails"] as? JsonObject ?: return null
            val meters = LinkedHashMap<String, List<JsonObject>>()
            for (meter in objects(details["meters"])) {
                val type = string(meter["type"]) ?: continue
                meters[type] = objects(meter["values"])
            }
            return Meters(meters, scale(details["unit"]))
        }

        /** True when a `powerDetails` body has a Production meter (its values may all be null at night). */
        internal fun hasProduction(data: ByteArray): Boolean = meters(data)?.meters?.containsKey("Production") == true

        /**
         * Consumption and grid exchange from a `powerDetails` body. Null without a Consumption meter that has values
         * (no meter at home: export-only plants show nothing about consumption).
         * Now: the latest 15-minute average, if at most 30 minutes old. Today: the curves summed (15-minute points).
         */
        internal fun home(data: ByteArray, fetchedAt: Instant, zone: ZoneId): HomeEnergy? {
            val details = meters(data) ?: return null
            val consumption = details.meters["Consumption"] ?: return null
            val used = curve(consumption, details.scale, fetchedAt, zone)
            val latest = used.lastOrNull { it.kW != null } ?: return null
            val bought = details.meters["Purchased"]?.let { curve(it, details.scale, fetchedAt, zone) }
            val sold = details.meters["FeedIn"]?.let { curve(it, details.scale, fetchedAt, zone) }
            fun total(points: List<CurvePoint>?): Double? = points?.let { list -> list.mapNotNull { it.kW }.sum() * 0.25 }
            fun value(points: List<CurvePoint>?, time: Instant): Double? = points?.firstOrNull { it.time == time }?.kW
            val recent = Duration.between(latest.time, fetchedAt) <= maxAgeNow
            var grid: Double? = null
            if (recent) {
                val buy = value(bought, latest.time)
                val sell = value(sold, latest.time)
                if (buy != null && sell != null) grid = buy - sell
            }
            return HomeEnergy(
                consumptionKW = if (recent) latest.kW else null,
                gridKW = grid,
                dailyConsumptionKWh = total(used),
                dailyImportKWh = total(bought),
                dailyExportKWh = total(sold),
                curve = used,
            )
        }
    }
}
