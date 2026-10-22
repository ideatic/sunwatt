package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.Constants
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.SolarSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration

// Huawei FusionSolar Northbound API ("SmartPVMS Northbound API", the "API account" an installer creates in FusionSolar
// under Company Management > Northbound Management). Every call is a POST to https://<server>/thirdData/<interface>
// with a JSON body; answers are HTTP 200 with {"success", "failCode", "data", "message"}.
//
// - login: {"userName", "systemCode"} -> the session token in the "xsrf-token" header (or the XSRF-TOKEN cookie), sent
//   back as the "xsrf-token" header. One session per account: a new login ends the previous one (failCode 305 ->
//   log in again once).
// - getStationList (older servers) or stations (newer): the plant; the first one is used.
// - getStationRealKpi {"stationCodes"}: day_power, month_power, total_power (kWh).
// - getDevList {"stationCodes"} + getDevRealKpi {"devIds", "devTypeId"}: active_power (kW) of the inverters
//   (devTypeId 1 string inverter, 38 residential inverter); their sum is the power now.
// - getKpiStationHour {"stationCodes", "collectTime" (ms)}: inverter_power per hour (kWh in the hour ~ average kW): the
//   curve. Re-read at most once an hour.
// Each interface is rate-limited (failCode 407): calls go one after another, and the provider's fetch interval limits
// fetches to one every 10 minutes.

/**
 * FusionSolar API account client. Keeps the [SolarDataSource] contract. The session (token, plant, inverters, curve)
 * lives in [sessions], shared by every client of the account in the process (DECISIONS D28, D36).
 */
class NorthboundClient(
    val credentials: NorthboundCredentials,
    private val http: HttpClient,
    private val now: () -> Instant = Instant::now,
    private val sessions: NorthboundSessionStore = InMemoryNorthboundSessionStore(),
) : SolarDataSource {

    override suspend fun fetch(): SolarSnapshot {
        val date = now()
        val station = station()
        val kpi = stationKpi(call("getStationRealKpi", jsonBody("stationCodes" to station.code)), station.code)
        val power = currentPower(station)
        val curve = hourlyCurve(station, date).toMutableList()
        if (power != null) curve.add(CurvePoint(date, power))
        return SolarSnapshot(
            stationName = station.name, currentPowerKW = power, dailyEnergyKWh = kpi.day, monthEnergyKWh = kpi.month,
            cumulativeEnergyKWh = kpi.total, curve = curve, fetchedAt = date, dataAsOf = if (power == null) null else date,
        ).clampingProduction()
    }

    override fun toString(): String = "NorthboundClient($credentials)"

    // Plant, inverters, curve

    private suspend fun session(): NorthboundSession = sessions.load(credentials.accountKey) ?: NorthboundSession()

    /** Reads the latest session, applies [change] and saves it. */
    private suspend fun update(change: (NorthboundSession) -> NorthboundSession) {
        sessions.save(credentials.accountKey, change(session()))
    }

    private suspend fun station(): NorthboundStation {
        session().station?.let { return it }
        val station = try {
            firstStationFromList(call("getStationList", JsonObject(emptyMap())))
        } catch (e: SunWattError.NorthboundAccountRejected) {
            // Newer servers answer "no access" to getStationList and offer the paged "stations" instead.
            firstStationFromPaged(call("stations", buildJsonObject { put("pageNo", 1) }))
        }
        update { it.copy(station = station) }
        return station
    }

    /** Sum of the inverters' active power (kW); null when the plant has no inverter the API reports. */
    private suspend fun currentPower(station: NorthboundStation): Double? {
        var inverters = session().inverters
        if (inverters == null) {
            val list = inverters(call("getDevList", jsonBody("stationCodes" to station.code)))
            inverters = list
            update { it.copy(inverters = list) }
        }
        if (inverters.isEmpty()) return null
        var total: Double? = null
        for (type in inverters.map { it.typeID }.toSet().sorted()) {
            val ids = inverters.filter { it.typeID == type }.joinToString(",") { it.id }
            val body = buildJsonObject {
                put("devIds", ids)
                put("devTypeId", type)
            }
            activePower(call("getDevRealKpi", body))?.let { total = (total ?: 0.0) + it }
        }
        return total
    }

    /** Today's hourly curve, read again once an hour; a failure (e.g. rate limit) keeps the last one. */
    private suspend fun hourlyCurve(station: NorthboundStation, date: Instant): List<CurvePoint> {
        val zone = Constants.madrid
        val today = date.atZone(zone).toLocalDate()
        val session = session()
        val kept = session.curve.filter { it.time.atZone(zone).toLocalDate() == today && it.time < date }
        val fetched = session.curveFetchedAt
        if (fetched != null && fetched.atZone(zone).toLocalDate() == today && java.time.Duration.between(fetched, date).toKotlinDuration() < CURVE_REFRESH) return kept
        val body = buildJsonObject {
            put("stationCodes", station.code)
            put("collectTime", date.toEpochMilli())
        }
        val data = try {
            call("getKpiStationHour", body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SunWattError) {
            return kept
        }
        val points = hourlyPoints(data, date, date)
        update { it.copy(curve = points, curveFetchedAt = date) }
        return points
    }

    // Calls

    /** Response of an interface: `data`, after the session and error handling. */
    private suspend fun call(interfaceName: String, body: JsonObject): JsonElement? {
        val token = session().token ?: login()
        return try {
            post(interfaceName, body, token, mayRetry = true)
        } catch (e: SessionExpired) {
            // Another login (the app, a widget, another tool) ended this session: log in again, once.
            post(interfaceName, body, login(), mayRetry = false)
        }
    }

    /** Thrown inside the client only, to log in again; never leaves it. */
    private class SessionExpired : Exception()

    private suspend fun login(): String {
        val body = buildJsonObject {
            put("userName", credentials.userName)
            put("systemCode", credentials.systemCode)
        }
        val response = send("login", body, token = null)
        if (response.status == 401 || response.status == 403) throw SunWattError.NorthboundAccountRejected
        HttpFetch.checkStatus(response)
        val answer = answer(response.body)
        if (answer.failCode == 407) throw SunWattError.RateLimited
        if (!answer.succeeded) throw SunWattError.NorthboundAccountRejected
        val token = tokenFrom(response) ?: throw SunWattError.BadResponse("login token")
        update { it.copy(token = token) }
        return token
    }

    private suspend fun post(interfaceName: String, body: JsonObject, token: String, mayRetry: Boolean): JsonElement? {
        val response = send(interfaceName, body, token)
        if (response.status == 401 || response.status == 403) throw SunWattError.NorthboundAccountRejected
        HttpFetch.checkStatus(response)
        val answer = answer(response.body)
        when (answer.failCode) {
            305 -> {
                update { it.copy(token = null) }
                // A second "session ended" right after a login is not something to retry again.
                if (mayRetry) throw SessionExpired()
                throw SunWattError.BadResponse("failCode 305")
            }
            407 -> throw SunWattError.RateLimited
            // No rights to this interface or plant.
            401, 20056 -> throw SunWattError.NorthboundAccountRejected
        }
        if (!answer.succeeded) throw SunWattError.BadResponse("failCode ${answer.failCode}")
        return answer.data
    }

    private suspend fun send(interfaceName: String, body: JsonObject, token: String?): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            if (token != null) put("xsrf-token", token)
        }
        val request = HttpRequest(
            url = credentials.baseUrl + interfaceName, method = "POST", headers = headers,
            body = body.toString().toByteArray(Charsets.UTF_8),
        )
        try {
            return HttpFetch.perform(http, request, redacting = credentials.systemCode)
        } catch (e: SunWattError.Network) {
            // The system code is already redacted; the session token must not reach the error text either.
            throw if (token.isNullOrEmpty()) e else SunWattError.Network(e.detail.replace(token, "***"))
        }
    }

    // Parsing (pure, tested with fixtures)

    internal class Answer(val succeeded: Boolean, val failCode: Int, val data: JsonElement?)

    internal class StationKpi(val day: Double?, val month: Double?, val total: Double?)

    companion object {
        /** The hourly curve is re-read at most this often (the most limited interface; its points are hourly anyway). */
        val CURVE_REFRESH: Duration = 1.hours

        /** Inverter device types: string inverter, residential inverter. */
        internal val inverterTypes: Set<Int> = setOf(1, 38)

        internal fun answer(data: ByteArray): Answer {
            val root = try {
                Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: throw SunWattError.BadResponse("not JSON")
            val failCode = number(root["failCode"])?.toInt() ?: 0
            val success = (root["success"] as? JsonPrimitive)?.booleanOrNull ?: (failCode == 0)
            return Answer(success && failCode == 0, failCode, root["data"])
        }

        /** The token from the "xsrf-token" header, else from the XSRF-TOKEN cookie. */
        internal fun tokenFrom(response: HttpResponse): String? {
            response.header("xsrf-token")?.takeIf { it.isNotEmpty() }?.let { return it }
            val cookies = response.header("Set-Cookie") ?: ""
            val start = cookies.indexOf("XSRF-TOKEN=")
            if (start < 0) return null
            val value = cookies.substring(start + "XSRF-TOKEN=".length).takeWhile { it != ';' && it != ',' }
            return value.ifEmpty { null }
        }

        internal fun firstStationFromList(data: JsonElement?): NorthboundStation {
            val first = objects(data).firstOrNull() ?: throw SunWattError.Unavailable
            val code = text(first["stationCode"]) ?: throw SunWattError.Unavailable
            return NorthboundStation(code, text(first["stationName"])?.let(HtmlEntities::unescape))
        }

        internal fun firstStationFromPaged(data: JsonElement?): NorthboundStation {
            val list = (data as? JsonObject)?.get("list")
            val first = objects(list).firstOrNull() ?: throw SunWattError.Unavailable
            val code = text(first["plantCode"]) ?: throw SunWattError.Unavailable
            return NorthboundStation(code, text(first["plantName"])?.let(HtmlEntities::unescape))
        }

        internal fun stationKpi(data: JsonElement?, code: String): StationKpi {
            val items = objects(data)
            val item = items.firstOrNull { text(it["stationCode"]) == code } ?: items.firstOrNull()
            val map = item?.get("dataItemMap") as? JsonObject
            return StationKpi(number(map?.get("day_power")), number(map?.get("month_power")), number(map?.get("total_power")))
        }

        internal fun inverters(data: JsonElement?): List<NorthboundDevice> = objects(data).mapNotNull { item ->
            val id = text(item["id"]) ?: return@mapNotNull null
            val type = number(item["devTypeId"])?.toInt() ?: return@mapNotNull null
            if (type in inverterTypes) NorthboundDevice(id, type) else null
        }

        /** Sum of `active_power` over the devices in the answer; null when none reports it. */
        internal fun activePower(data: JsonElement?): Double? {
            val values = objects(data).mapNotNull { number((it["dataItemMap"] as? JsonObject)?.get("active_power")) }
            return if (values.isEmpty()) null else values.sum()
        }

        /** One point per hour of [day] before [until], in the middle of the hour (the value is the hour's energy). */
        internal fun hourlyPoints(data: JsonElement?, day: Instant, until: Instant): List<CurvePoint> {
            val zone = Constants.madrid
            val dayDate = day.atZone(zone).toLocalDate()
            val points = objects(data).mapNotNull { item ->
                val ms = number(item["collectTime"]) ?: return@mapNotNull null
                val time = Instant.ofEpochMilli(ms.toLong()).plusSeconds(30 * 60)
                if (time.atZone(zone).toLocalDate() != dayDate || time >= until) return@mapNotNull null
                CurvePoint(time, number((item["dataItemMap"] as? JsonObject)?.get("inverter_power")))
            }
            return points.sortedBy { it.time }.distinctBy { it.time }
        }

        /** Numbers come as numbers or strings ("12.5"); null, "" and "-" are no value. */
        internal fun number(value: JsonElement?): Double? {
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive is JsonNull || primitive.booleanOrNull != null) return null
            return primitive.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        internal fun text(value: JsonElement?): String? {
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive is JsonNull || primitive.booleanOrNull != null) return null
            return primitive.content.ifEmpty { null }
        }

        private fun objects(data: JsonElement?): List<JsonObject> = (data as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }
}

private fun jsonBody(vararg pairs: Pair<String, String>): JsonObject = JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })
