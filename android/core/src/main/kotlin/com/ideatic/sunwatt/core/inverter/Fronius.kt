package com.ideatic.sunwatt.core.inverter

import com.ideatic.sunwatt.core.models.InverterIdentification
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterReader
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.HttpFetch
import com.ideatic.sunwatt.core.services.OkHttpSunWattClient
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

// Fronius Solar API: the JSON interface Fronius inverters (Datamanager: Symo, Primo, Galvo, Eco; GEN24/Tauro) serve on
// the home network over plain HTTP, port 80, without login. Read-only by design (the API has no write calls).
// GEN24 inverters ship with it turned off ("Solar API" in the inverter's web page, under Communication).
//
// One request per reading: GET /solar_api/v1/GetPowerFlowRealtimeData.fcgi ->
// {"Head": {"Status": {"Code": 0}}, "Body": {"Data": {"Site": {"P_PV", "P_Grid", "P_Load", "E_Day", "E_Total", ...}}}}
// Units W and Wh. P_PV is null at night, P_Grid null without a Fronius meter, E_Day null on GEN24 (today then comes from
// the lifetime counter, like SunSpec: `LocalInverterSolarClient.dailyEnergy`). Signs: P_Grid > 0 buying from the grid,
// P_Load < 0 the home's consumption.

/** Reads a Fronius inverter through its Solar API. `endpoint.port` is the HTTP port (80); the unit ID is not used. */
class FroniusSolarAPIClient(
    val endpoint: ModbusEndpoint,
    http: HttpClient? = null,
) : LocalInverterReader {
    private val http: HttpClient = http ?: OkHttpSunWattClient()

    override suspend fun readLive(): LiveInverterReading = identifyLive().reading

    /** The Solar API does not name the model; any answer it gives is a Fronius inverter, so the model reads "Fronius". */
    override suspend fun identifyLive(): InverterIdentification {
        val url = "http://${endpoint.host}:${endpoint.port}/solar_api/v1/GetPowerFlowRealtimeData.fcgi"
        val body = try {
            val response = HttpFetch.perform(http, HttpFetch.request(url, 5.seconds))
            HttpFetch.checkStatus(response)
            response.body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No connection, or something else answered on the port (router, printer: HTTP 404).
            throw SunWattError.InverterUnreachable
        }
        return InverterIdentification("Fronius", parse(body))
    }

    companion object {
        /** The Solar API's port when the address names none. */
        const val PORT: Int = 80

        /**
         * Reading from a GetPowerFlowRealtimeData answer. Anything that is not a Solar API answer with status 0 reads
         * as "no inverter there" ([SunWattError.InverterUnreachable]): another device on the port, or the inverter busy.
         */
        internal fun parse(data: ByteArray): LiveInverterReading {
            val site = try {
                val root = Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
                val head = root?.get("Head") as? JsonObject
                val status = head?.get("Status") as? JsonObject
                if (number(status?.get("Code")) != 0.0) null else {
                    val payload = (root?.get("Body") as? JsonObject)?.get("Data") as? JsonObject
                    payload?.get("Site") as? JsonObject
                }
            } catch (e: Exception) {
                null
            } ?: throw SunWattError.InverterUnreachable
            fun kW(key: String): Double? = number(site[key])?.let { it / 1000 }
            fun kWh(key: String): Double? = number(site[key])?.let { max(it, 0.0) / 1000 }
            // Positive P_Grid is bought; the reading's meter is positive when feeding the grid.
            val grid = kW("P_Grid")
            return LiveInverterReading(
                activePowerKW = kW("P_PV") ?: 0.0,
                dailyEnergyKWh = kWh("E_Day"),
                totalEnergyKWh = kWh("E_Total"),
                meterActivePowerKW = grid?.let { -it },
                // P_Load is negative while the home uses power; with a battery it is the only right figure.
                homeConsumptionKW = if (grid == null) null else kW("P_Load")?.let { max(-it, 0.0) },
            )
        }

        /** A finite JSON number; null for null, text or a missing key. */
        private fun number(value: JsonElement?): Double? {
            val primitive = value as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }
}
