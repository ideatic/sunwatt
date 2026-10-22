package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarSnapshot
import kotlinx.serialization.json.JsonElement
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Reads a system from pvoutput.org (API key + system ID, sent as headers). One request per fetch:
 * `getstatus.jsp?h=1` returns today's status history (system time zone) as text:
 * `date,time,energy,efficiency,power,average,normalised,consumption,power,temperature,voltage;...`
 * with `yyyymmdd`, `HH:mm`, energy in Wh since midnight and power in W; missing values are `NaN`. The second
 * "power" column is consumption, filled only when the uploader sends it (a meter at home): then `home` is set.
 * Today's grid import and export are worked out from the power readings (PVOutput has no such totals here).
 * PVOutput allows 60 requests an hour per key. Before the first upload of the day it answers
 * `400 Bad request 400: No status found`, which is a day without production, not an error.
 * Times are read in [zone] (default: the device's, where the plant almost always is).
 */
class PvOutputClient(
    private val credentials: SolarAPICredentials,
    private val http: HttpClient = OkHttpSunWattClient(),
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : SolarDataSource {
    internal val statusURL: String = "https://$HOST/service/r2/getstatus.jsp?h=1&limit=288&asc=1"

    override suspend fun fetch(): SolarSnapshot {
        val request = HttpRequest(
            url = statusURL,
            headers = mapOf(
                "Accept" to "text/plain",
                "X-Pvoutput-Apikey" to credentials.apiKey,
                "X-Pvoutput-SystemId" to credentials.siteID,
            ),
        )
        val response = HttpFetch.perform(http, request, redacting = credentials.apiKey)
        val date = now()
        val status = response.status
        if (status == 429 || (status == 403 && isRateLimit(response.body))) throw SunWattError.RateLimited
        if (status == 401 || status == 403) throw SunWattError.SolarCredentialsRejected
        if (status == 400 && isNoStatus(response.body)) {
            return SolarSnapshot(currentPowerKW = 0.0, dailyEnergyKWh = 0.0, curve = emptyList(), fetchedAt = date, dataAsOf = null)
        }
        HttpFetch.checkStatus(response)
        return parse(response.body, date, zone)
    }

    /** Never prints the API key. */
    override fun toString(): String = "PvOutputClient($credentials)"

    companion object {
        const val HOST = "pvoutput.org"

        /** The latest status counts as "power now" only if it is this recent; older means the plant stopped uploading. */
        val currentPowerMaxAge: kotlin.time.Duration = 30.minutes

        private val maxAge: Duration = currentPowerMaxAge.toJavaDuration()

        internal fun isNoStatus(data: ByteArray): Boolean = data.toString(Charsets.UTF_8).contains("no status found", ignoreCase = true)

        /** "Forbidden 403: Exceeded 60 requests per hour" is a rate limit, not a wrong key. */
        internal fun isRateLimit(data: ByteArray): Boolean = data.toString(Charsets.UTF_8).contains("exceeded", ignoreCase = true)

        private class Record(
            val time: Instant,
            val energyWh: Double?,
            val powerW: Double?,
            val consumedWh: Double?,
            val consumptionW: Double?,
        )

        /** Parses a status history body. Records that do not parse are skipped; no valid record at all is an error. */
        fun parse(data: ByteArray, fetchedAt: Instant, zone: ZoneId): SolarSnapshot {
            val text = data.toString(Charsets.UTF_8).trim()
            val parsed = text.split(';').filter { it.isNotEmpty() }.mapNotNull { line ->
                val fields = line.split(',').map { field -> field.trim { it == ' ' || it == '\t' } }
                if (fields.size < 5) return@mapNotNull null
                val time = timestamp(fields[0], fields[1], zone) ?: return@mapNotNull null
                // Instantaneous power; the interval average when it is missing.
                val power = SolarLocalTime.number(fields[4]) ?: if (fields.size > 5) SolarLocalTime.number(fields[5]) else null
                Record(
                    time = time,
                    energyWh = SolarLocalTime.number(fields[2]),
                    powerW = power,
                    consumedWh = if (fields.size > 7) SolarLocalTime.number(fields[7]) else null,
                    consumptionW = if (fields.size > 8) SolarLocalTime.number(fields[8]) else null,
                )
            }
            // `asc=1`: oldest first, so the second 02:xx of the autumn DST day moves one hour later.
            val records = SolarLocalTime.inOrder(parsed, { it.time }, { record, time ->
                Record(time, record.energyWh, record.powerW, record.consumedWh, record.consumptionW)
            }, zone)
            if (records.isEmpty()) throw SunWattError.BadResponse("status")

            val limit = fetchedAt.plusSeconds(5 * 60)
            val curve = records.map { CurvePoint(it.time, if (it.time > limit) null else it.powerW?.let { w -> w / 1000 }) }
            val latest = records.lastOrNull { it.time <= limit && it.powerW != null }
            val current: Double? = latest?.let { record ->
                if (Duration.between(record.time, fetchedAt) <= maxAge) (record.powerW ?: 0.0) / 1000 else 0.0
            }
            val energy = records.lastOrNull { it.time <= limit && it.energyWh != null }?.energyWh?.let { it / 1000 }

            // Home consumption: only when the uploader sends it.
            val past = records.filter { it.time <= limit }
            var home: HomeEnergy? = null
            if (past.any { it.consumptionW != null || it.consumedWh != null }) {
                val latestUse = past.lastOrNull { it.consumptionW != null }
                val recent = latestUse?.let { Duration.between(it.time, fetchedAt) <= maxAge } ?: false
                val useKW = if (recent) latestUse?.consumptionW?.let { it / 1000 } else null
                val gridKW = if (recent) latestUse?.let { r -> r.consumptionW?.let { (it - (r.powerW ?: 0.0)) / 1000 } } else null
                // Import / export: what each interval used beyond (or below) what the panels made, interval average of
                // the two readings at its ends. Gaps over 30 minutes are skipped.
                var importKWh = 0.0
                var exportKWh = 0.0
                val pairs = past.zipWithNext()
                for ((a, b) in pairs) {
                    val hours = Duration.between(a.time, b.time).toNanos() / 3_600_000_000_000.0
                    val ua = a.consumptionW
                    val ub = b.consumptionW
                    if (hours <= 0 || hours > 0.5 || ua == null || ub == null) continue
                    val net = ((ua + ub) - ((a.powerW ?: 0.0) + (b.powerW ?: 0.0))) / 2 / 1000
                    if (net > 0) importKWh += net * hours else exportKWh -= net * hours
                }
                val hasPairs = pairs.any { (a, b) -> a.consumptionW != null && b.consumptionW != null }
                home = HomeEnergy(
                    consumptionKW = useKW,
                    gridKW = gridKW,
                    dailyConsumptionKWh = past.lastOrNull { it.consumedWh != null }?.consumedWh?.let { it / 1000 },
                    dailyImportKWh = if (hasPairs) importKWh else null,
                    dailyExportKWh = if (hasPairs) exportKWh else null,
                    curve = records.map { CurvePoint(it.time, if (it.time > limit) null else it.consumptionW?.let { w -> w / 1000 }) },
                )
            }
            return SolarSnapshot(
                stationName = null,
                currentPowerKW = current,
                dailyEnergyKWh = energy,
                curve = curve,
                fetchedAt = fetchedAt,
                dataAsOf = curve.lastOrNull { it.kW != null }?.time,
                home = home,
            ).clampingProduction()
        }

        /** `yyyymmdd` + `HH:mm` in [zone]. */
        private fun timestamp(day: String, time: String, zone: ZoneId): Instant? {
            if (day.length != 8 || !day.all { it in '0'..'9' }) return null
            val y = day.substring(0, 4).toInt()
            val m = day.substring(4, 6).toInt()
            val d = day.substring(6, 8).toInt()
            val hm = time.split(':').filter { it.isNotEmpty() }.mapNotNull { SolarLocalTime.int(it) }
            if (hm.size != 2) return null
            return SolarLocalTime.date(y, m, d, hm[0], hm[1], zone = zone)
        }
    }
}

/** Helpers shared by the SolarEdge and PVOutput clients. */
object SolarLocalTime {
    /** "yyyy-MM-dd HH:mm:ss" or "yyyy-MM-dd HH:mm" in [zone]. Null for anything else. */
    internal fun parse(text: String, zone: ZoneId): Instant? {
        val parts = text.trim { it == ' ' || it == '\t' }.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val day = parts[0].split('-').filter { it.isNotEmpty() }.mapNotNull { int(it) }
        val time = parts[1].split(':').filter { it.isNotEmpty() }.mapNotNull { int(it) }
        if (day.size != 3 || time.size !in 2..3) return null
        return date(day[0], day[1], day[2], time[0], time[1], if (time.size == 3) time[2] else 0, zone)
    }

    /** Validated local date; null when a field is out of range or the time does not exist (spring-forward gap). */
    internal fun date(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, zone: ZoneId): Instant? {
        if (month !in 1..12 || day !in 1..31 || hour !in 0 until 24 || minute !in 0 until 60 || second !in 0 until 60) return null
        val local = try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (e: DateTimeException) {
            return null
        }
        if (zone.rules.getValidOffsets(local).isEmpty()) return null
        // In the repeated hour of the autumn DST day, the first instant.
        return ZonedDateTime.ofLocal(local, zone, null).toInstant()
    }

    /**
     * [items] in the order the provider sent them (oldest first; a newest-first list is turned around), with strictly
     * increasing times. On the autumn DST day the local times 02:00-02:59 come twice; [date] gives the first instant
     * for both, so a time that does not move forward is the repeated hour: it moves one hour later. Anything still
     * not later than the previous item (a real duplicate) is dropped. The `SolarDataSource` contract allows no two
     * points at the same time.
     */
    internal fun <T> inOrder(items: List<T>, time: (T) -> Instant, moving: (T, Instant) -> T, zone: ZoneId): List<T> {
        val result = ArrayList<T>()
        val oldestFirst = if (items.size > 1 && time(items.first()) > time(items.last())) items.reversed() else items
        fun wall(instant: Instant): List<Int> =
            instant.atZone(zone).let { listOf(it.dayOfMonth, it.hour, it.minute, it.second) }
        for (item in oldestFirst) {
            var current = item
            val last = result.lastOrNull()?.let(time)
            if (last != null && time(current) <= last) {
                val later = time(current).plusSeconds(3600)
                if (later <= last || wall(later) != wall(time(current))) continue
                current = moving(current, later)
            }
            result.add(current)
        }
        return result
    }

    /** Lenient number: JSON number or numeric string; null for anything else, NaN or infinity. */
    internal fun number(value: JsonElement?): Double? = FusionSolarKioskClient.number(value)

    internal fun number(text: String?): Double? = FusionSolarKioskClient.number(text)

    /** Whole number from ASCII digits with an optional sign (Swift `Int(_:)`); null otherwise. */
    internal fun int(text: String): Int? {
        val digits = text.removePrefix("+").removePrefix("-")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' } || text.startsWith("+-") || text.startsWith("-+")) return null
        return text.toIntOrNull()
    }
}
