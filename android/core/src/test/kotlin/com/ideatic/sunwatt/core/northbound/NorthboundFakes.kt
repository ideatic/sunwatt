package com.ideatic.sunwatt.core.northbound

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.services.NorthboundCredentials
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

const val NORTHBOUND_SECRET = "SYSTEMCODE99"
const val NORTHBOUND_TEST_TOKEN = "test-token"

/** FusionSolar API interfaces -> fixtures (first match wins). */
val northboundRoutes: List<Pair<String, String>> = listOf(
    "/thirdData/login" to "northbound-login-synthetic.json",
    "/thirdData/getStationList" to "northbound-stationlist-synthetic.json",
    "/thirdData/getStationRealKpi" to "northbound-realkpi-synthetic.json",
    "/thirdData/getDevList" to "northbound-devlist-synthetic.json",
    "/thirdData/getDevRealKpi" to "northbound-devrealkpi-synthetic.json",
    "/thirdData/getKpiStationHour" to "northbound-hour-synthetic.json",
)

fun madrid(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneId.of("Europe/Madrid")).toInstant()

fun northboundCredentials(): NorthboundCredentials =
    NorthboundCredentials.create(host = "", userName = "api_user", systemCode = NORTHBOUND_SECRET)!!

/** Adds the fixture routes to [this]; the login reply carries [token] in the xsrf-token header. */
fun StubHttpClient.addNorthboundRoutes(token: String = NORTHBOUND_TEST_TOKEN) {
    for ((match, fixture) in northboundRoutes) {
        on(match, StubHttpClient.Reply.Ok(Fixtures.bytes(fixture), headers = if (match.endsWith("login")) mapOf("xsrf-token" to token) else emptyMap()))
    }
}
