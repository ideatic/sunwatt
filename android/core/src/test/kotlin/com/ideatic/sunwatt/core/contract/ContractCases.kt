package com.ideatic.sunwatt.core.contract

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.inverter.FakeModbusTransport
import com.ideatic.sunwatt.core.inverter.FroniusSolarAPIClient
import com.ideatic.sunwatt.core.inverter.HuaweiModbusClient
import com.ideatic.sunwatt.core.inverter.LocalInverterSolarClient
import com.ideatic.sunwatt.core.inverter.ModbusTransportFactory
import com.ideatic.sunwatt.core.inverter.SunSpecClient
import com.ideatic.sunwatt.core.inverter.fakeInverterRegisters
import com.ideatic.sunwatt.core.inverter.fakeSunSpecRegisters
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.LocalInverterReader
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.northbound.NORTHBOUND_SECRET
import com.ideatic.sunwatt.core.northbound.northboundCredentials
import com.ideatic.sunwatt.core.northbound.northboundRoutes
import com.ideatic.sunwatt.core.services.EnergyChartsClient
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.InMemoryNorthboundSessionStore
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.PvpcClient
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.solar.madridAt
import java.time.Instant

// The contracts written on `SolarDataSource`, `PriceDataSource` and `LocalInverterReader`, as tables of cases (twin of
// `ProviderContractTests.swift`). A new provider, price region or inverter kind adds one case to the lists below; the
// generic checks in `ProviderContractTest` run on it unchanged.

private const val CONTRACT_KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
private val contractKiosk = KioskEndpoint("host.example", "TOKENSECRET1")

// Solar

/** One solar provider set up against its fixtures. */
class SolarContractCase(
    val name: String,
    /** The account the case is built from; its [SolarProvider] is what the coverage check counts. */
    val account: SolarAccount,
    /** The client's clock. */
    val now: Instant,
    /** URL fragment to fixture, first match wins; null when the source does not use HTTP. */
    val routes: List<Pair<String, String>>?,
    /** The secret that must never appear in an error text. */
    val secret: String?,
    /** What HTTP 401 and 403 mean for this provider (the account was rejected). */
    val rejected: SunWattError?,
    /** The statuses that mean [rejected]. */
    val rejectedStatuses: List<Int>,
    val expectsLive: Boolean,
    val make: (StubHttpClient, Instant) -> SolarDataSource,
) {
    override fun toString(): String = name

    /** Every reply carries a session token header (the FusionSolar API login sends it; the others ignore it). */
    fun stub(): StubHttpClient {
        val http = StubHttpClient()
        for ((match, fixture) in routes.orEmpty()) {
            http.on(match, Reply.Ok(Fixtures.bytes(fixture), headers = mapOf("xsrf-token" to "test-token")))
        }
        return http
    }

    companion object {
        private val edgeAccount = SolarAccount.SolarEdge(SolarAPICredentials.parse("1234567", CONTRACT_KEY)!!)
        private val pvAccount = SolarAccount.PvOutput(SolarAPICredentials.parse("42", CONTRACT_KEY)!!)
        private val summer = madridAt(2026, 6, 1, 12, 35)
        private val froniusEndpoint = ModbusEndpoint("x", 80)

        /** The account's own client over [http]; the cloud accounts here have no inverter address. */
        private fun cloud(account: SolarAccount) = { http: StubHttpClient, now: Instant ->
            account.client(http, InMemoryNorthboundSessionStore(), { now })
        }

        /** The account's inverter reader over a fake device, with no cloud (the dongle or the inverter alone). */
        private fun inverterOnly(account: SolarAccount, makeTransport: ModbusTransportFactory) = { _: StubHttpClient, now: Instant ->
            LocalInverterSolarClient(
                account.localReader(makeTransport)!!, cloud = null, keepsCloudHome = account.provider.profile.keepsCloudHome, now = { now },
            )
        }

        val all: List<SolarContractCase> = listOf(
            SolarContractCase(
                "FusionSolar kiosk", SolarAccount.FusionSolar(contractKiosk), madridAt(2026, 10, 17, 14, 0),
                listOf("station-kiosk-file" to "kiosk-day-synthetic.json"), contractKiosk.kk, SunWattError.KioskLinkRejected,
                listOf(401, 403), false, cloud(SolarAccount.FusionSolar(contractKiosk)),
            ),
            SolarContractCase(
                "SolarEdge", edgeAccount, summer,
                listOf("/overview" to "solaredge-overview-synthetic.json", "/powerDetails" to "solaredge-powerdetails-nometer-synthetic.json"),
                CONTRACT_KEY, SunWattError.SolarCredentialsRejected, listOf(401, 403), false, cloud(edgeAccount),
            ),
            SolarContractCase(
                "SolarEdge with meter", edgeAccount, summer,
                listOf("/overview" to "solaredge-overview-synthetic.json", "/powerDetails" to "solaredge-powerdetails-meter-synthetic.json"),
                CONTRACT_KEY, SunWattError.SolarCredentialsRejected, listOf(401, 403), false, cloud(edgeAccount),
            ),
            SolarContractCase(
                "PVOutput", pvAccount, summer, listOf("pvoutput.org" to "pvoutput-status-synthetic.txt"),
                CONTRACT_KEY, SunWattError.SolarCredentialsRejected, listOf(401, 403), false, cloud(pvAccount),
            ),
            SolarContractCase(
                "PVOutput with consumption", pvAccount, summer, listOf("pvoutput.org" to "pvoutput-status-consumption-synthetic.txt"),
                CONTRACT_KEY, SunWattError.SolarCredentialsRejected, listOf(401, 403), false, cloud(pvAccount),
            ),
            SolarContractCase(
                "FusionSolar API", SolarAccount.FusionSolarAPI(northboundCredentials()), madridAt(2026, 10, 17, 13, 40),
                northboundRoutes, NORTHBOUND_SECRET, SunWattError.NorthboundAccountRejected, listOf(401), false,
                cloud(SolarAccount.FusionSolarAPI(northboundCredentials())),
            ),
            // The dongle alone: a FusionSolar account without a kiosk link.
            SolarContractCase(
                "Huawei inverter", SolarAccount.FusionSolar(null, ModbusEndpoint("x")), summer, null, null, null, emptyList(), true,
                inverterOnly(SolarAccount.FusionSolar(null, ModbusEndpoint("x"))) { FakeModbusTransport(fakeInverterRegisters(meterW = -500)) },
            ),
            SolarContractCase(
                "SunSpec inverter", SolarAccount.SolarEdge(SolarAPICredentials.parse("1234567", CONTRACT_KEY)!!, ModbusEndpoint("x")),
                summer, null, null, null, emptyList(), true,
                inverterOnly(SolarAccount.SolarEdge(SolarAPICredentials.parse("1234567", CONTRACT_KEY)!!, ModbusEndpoint("x"))) {
                    FakeModbusTransport(fakeSunSpecRegisters())
                },
            ),
            SolarContractCase(
                "Fronius inverter", SolarAccount.Fronius(froniusEndpoint), summer, null, null, null, emptyList(), true,
            ) { _, now -> SolarAccount.Fronius(froniusEndpoint).client(froniusStub("fronius-powerflow-day-synthetic.json"), InMemoryNorthboundSessionStore(), { now }) },
        )
    }
}

// Prices

/** One price source set up against a fixture of 17 October 2026. */
class PriceContractCase(
    val name: String,
    val region: PriceRegion,
    val fixture: String,
    val match: String,
    /** Tomorrow is a separate request (PVPC) that can fail on its own. */
    val separateTomorrowRequest: Boolean,
    val make: (StubHttpClient, Instant) -> PriceDataSource,
) {
    fun stub(): StubHttpClient = StubHttpClient().also { it.on(match, Reply.Ok(Fixtures.bytes(fixture))) }

    override fun toString(): String = name

    companion object {
        val now: Instant = madridAt(2026, 10, 17, 10)

        // France and Belgium have no fixture of their own: the parser does not look at the zone, so the Germany body serves.
        private val energyCharts = listOf(
            PriceRegion.GERMANY to "energycharts-DE-LU-2026-10-17.json",
            PriceRegion.AUSTRIA to "energycharts-AT-2026-10-17.json",
            PriceRegion.NETHERLANDS to "energycharts-NL-2026-10-17.json",
            PriceRegion.ITALY_NORTH to "energycharts-IT-North-2026-10-17.json",
            PriceRegion.PORTUGAL to "energycharts-PT-2026-10-17.json",
            PriceRegion.FRANCE to "energycharts-DE-LU-2026-10-17.json",
            PriceRegion.BELGIUM to "energycharts-DE-LU-2026-10-17.json",
        )

        val all: List<PriceContractCase> = listOf(
            PriceContractCase("PVPC", PriceRegion.SPAIN, "pvpc-2026-10-17.json", "apidatos", true) { http, now ->
                PvpcClient(http, { now })
            },
        ) + energyCharts.map { (region, fixture) ->
            PriceContractCase("Energy-Charts ${region.rawValue}", region, fixture, "api.energy-charts.info", false) { http, now ->
                EnergyChartsClient(region, http, { now })
            }
        }
    }
}

// Inverters

/** The Solar API answering with [fixture]. The inverter is on the home network: its errors are "unreachable". */
fun froniusStub(fixture: String): StubHttpClient =
    StubHttpClient().also { it.on("GetPowerFlowRealtimeData", Reply.Ok(Fixtures.bytes(fixture))) }

/**
 * One inverter kind with a fake device that answers like it. [kind] is the entry of [LocalInverterKind] the case
 * stands for.
 */
sealed interface InverterContractCase {
    val name: String
    val kind: LocalInverterKind

    /** A Modbus reader and the registers of the fake device it reads. */
    class Modbus(
        override val name: String,
        override val kind: LocalInverterKind,
        val registers: Map<Int, Int>,
        val make: (ModbusEndpoint, ModbusTransportFactory) -> LocalInverterReader,
    ) : InverterContractCase {
        override fun toString(): String = name
    }

    /** An HTTP reader and the fixture its fake device answers with. */
    class Http(
        override val name: String,
        override val kind: LocalInverterKind,
        val fixture: String,
        val make: (ModbusEndpoint, HttpClient) -> LocalInverterReader,
    ) : InverterContractCase {
        override fun toString(): String = name
    }

    companion object {
        val modbus: List<Modbus> = listOf(
            Modbus("Huawei", LocalInverterKind.HUAWEI, fakeInverterRegisters(meterW = 1200)) { endpoint, transport ->
                HuaweiModbusClient(endpoint, transport)
            },
            Modbus("SunSpec integer", LocalInverterKind.SUN_SPEC, fakeSunSpecRegisters()) { endpoint, transport ->
                SunSpecClient(endpoint, transport)
            },
            Modbus("SunSpec float", LocalInverterKind.SUN_SPEC, fakeSunSpecRegisters(float = true)) { endpoint, transport ->
                SunSpecClient(endpoint, transport)
            },
        )

        val http: List<Http> = listOf(
            Http("Fronius day", LocalInverterKind.FRONIUS, "fronius-powerflow-day-synthetic.json") { endpoint, http ->
                FroniusSolarAPIClient(endpoint, http)
            },
            Http("Fronius GEN24 night", LocalInverterKind.FRONIUS, "fronius-powerflow-gen24-night-synthetic.json") { endpoint, http ->
                FroniusSolarAPIClient(endpoint, http)
            },
        )

        val all: List<InverterContractCase> = modbus + http
    }
}
