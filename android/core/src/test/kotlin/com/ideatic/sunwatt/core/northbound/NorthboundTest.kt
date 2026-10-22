package com.ideatic.sunwatt.core.northbound

import com.ideatic.sunwatt.core.Fixtures
import com.ideatic.sunwatt.core.StubHttpClient
import com.ideatic.sunwatt.core.StubHttpClient.Reply
import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.services.HtmlEntities
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.HttpRequest
import com.ideatic.sunwatt.core.services.HttpResponse
import com.ideatic.sunwatt.core.services.InMemoryNorthboundSessionStore
import com.ideatic.sunwatt.core.services.NorthboundClient
import com.ideatic.sunwatt.core.services.NorthboundCredentials
import com.ideatic.sunwatt.core.services.NorthboundSession
import com.ideatic.sunwatt.core.services.NorthboundStation
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FusionSolar Northbound API ("API account"): credentials, the session, parsing. Twin of `NorthboundTests.swift`. */
class NorthboundTest {
    private val now = madrid(2026, 10, 17, 13, 40)
    private val credentials = northboundCredentials()

    private fun stub() = StubHttpClient().apply { addNorthboundRoutes("token-1") }

    private fun client(http: HttpClient, at: java.time.Instant = now, store: InMemoryNorthboundSessionStore = InMemoryNorthboundSessionStore()) =
        NorthboundClient(credentials, http, { at }, store)

    private fun names(requests: List<HttpRequest>) = requests.map { URI(it.url).path.substringAfterLast('/') }

    private fun bodyOf(request: HttpRequest): JsonObject = Json.parseToJsonElement(request.body!!.decodeToString()).jsonObject

    private fun assertNoSecrets(text: String) {
        assertFalse(text.contains(NORTHBOUND_SECRET), "system code in: $text")
        assertFalse(text.contains("token-1"), "token in: $text")
        assertFalse(text.contains(NORTHBOUND_TEST_TOKEN), "token in: $text")
    }

    @Test
    fun credentialsOnlyGoToHuawei() {
        assertEquals("eu5.fusionsolar.huawei.com", credentials.host)
        assertEquals("intl.fusionsolar.huawei.com", NorthboundCredentials.create(" https://INTL.fusionsolar.huawei.com/ ", "u", "c")?.host)
        assertEquals("fusionsolar.huawei.com", NorthboundCredentials.create("fusionsolar.huawei.com/path/x", "u", "c")?.host)
        for (bad in listOf("evil.example", "fusionsolar.huawei.com.evil.example", "eu5..fusionsolar.huawei.com", "eu5.fusionsolar.huawei.com:8080", "evilfusionsolar.huawei.com")) {
            assertNull(NorthboundCredentials.create(bad, "u", "c"), bad)
        }
        assertNull(NorthboundCredentials.create("", " ", "c"))
        assertNull(NorthboundCredentials.create("", "u", ""))
        assertNull(NorthboundCredentials.create("", "u", "x".repeat(129)))
        assertEquals("https://eu5.fusionsolar.huawei.com/thirdData/", credentials.baseUrl)
        assertEquals(credentials, northboundCredentials())
        assertEquals(64, credentials.accountKey.length)
        assertNoSecrets(credentials.toString())
        assertNoSecrets(credentials.accountKey)
    }

    @Test
    fun accountKeyDependsOnTheWholeAccount() {
        val other = NorthboundCredentials.create("", "api_user", "OTHERCODE")!!
        assertTrue(credentials.accountKey != other.accountKey)
    }

    @Test
    fun fetchReadsThePlant() = runTest {
        val http = stub()
        val s = client(http).fetch()
        assertEquals("Demo plant", s.stationName)
        assertEquals(4.812, s.currentPowerKW)
        assertEquals(14.37, s.dailyEnergyKWh)
        assertEquals(412.6, s.monthEnergyKWh)
        assertEquals(44150.55, s.cumulativeEnergyKWh)
        // Hourly points (mid-hour, before now) and the reading now.
        assertEquals(CurvePoint(now, 4.812), s.curve.last())
        assertTrue(s.curve.contains(CurvePoint(madrid(2026, 10, 17, 12, 30), 4.9)))
        assertTrue(s.curve.none { it.time > now })
        assertEquals(now, s.dataAsOf)
        assertEquals(now, s.fetchedAt)
        // Login first, with the account; the token on every later call; only the inverters asked for power.
        val requests = http.requests
        assertEquals("/thirdData/login", URI(requests.first().url).path)
        val login = bodyOf(requests.first())
        assertEquals(setOf("userName", "systemCode"), login.keys)
        assertEquals("api_user", login.getValue("userName").jsonPrimitive.content)
        assertEquals(NORTHBOUND_SECRET, login.getValue("systemCode").jsonPrimitive.content)
        assertTrue(requests.drop(1).all { it.headers["xsrf-token"] == "token-1" && it.method == "POST" })
        val power = bodyOf(requests.first { URI(it.url).path == "/thirdData/getDevRealKpi" })
        assertEquals("1000000033812345", power.getValue("devIds").jsonPrimitive.content)
        assertEquals(1, power.getValue("devTypeId").jsonPrimitive.int)
    }

    @Test
    fun laterFetchesReuseTheSession() = runTest {
        val http = stub()
        val store = InMemoryNorthboundSessionStore()
        client(http, now, store).fetch()
        val first = http.requests.size
        // A second client sharing the store (another process-level user of the account): no second login.
        client(http, now.plusSeconds(600), store).fetch()
        // No login, plant list, device list or hourly curve again: only the plant KPIs and the inverters' power.
        assertEquals(listOf("getStationRealKpi", "getDevRealKpi"), names(http.requests.drop(first)))
        assertEquals(1, names(http.requests).count { it == "login" })
        assertTrue(http.requests.drop(first).all { it.headers["xsrf-token"] == "token-1" })
        // An hour later the curve is read again.
        client(http, now.plusSeconds(3700), store).fetch()
        assertEquals("getKpiStationHour", names(http.requests).last())
    }

    @Test
    fun aSeparateStoreLogsInAgain() = runTest {
        val http = stub()
        client(http).fetch()
        client(http).fetch()
        assertEquals(2, names(http.requests).count { it == "login" })
    }

    @Test
    fun theSessionIsSavedByAccountKeyWithoutTheCode() = runTest {
        val store = InMemoryNorthboundSessionStore()
        client(stub(), now, store).fetch()
        val session = assertNotNull(store.load(credentials.accountKey))
        assertEquals("token-1", session.token)
        assertEquals(NorthboundStation("NE=12345678", "Demo plant"), session.station)
        assertNoSecrets(session.toString())
        store.clear(credentials.accountKey)
        assertNull(store.load(credentials.accountKey))
    }

    /** An HTTP client answering from a closure; `count(path)` is how many requests that interface has had so far. */
    private class ScriptedHttp(private val reply: (HttpRequest, (String) -> Int) -> Pair<ByteArray, Map<String, String>>) : HttpClient {
        private val counts = HashMap<String, AtomicInteger>()
        val paths = CopyOnWriteArrayList<String>()
        val tokens = CopyOnWriteArrayList<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            val path = URI(request.url).path.substringAfterLast('/')
            val snapshot = synchronized(counts) {
                counts.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                paths.add(path)
                request.headers["xsrf-token"]?.let { tokens.add(it) }
                counts.mapValues { it.value.get() }
            }
            val (data, headers) = reply(request) { snapshot[it] ?: 0 }
            return HttpResponse(200, headers, data)
        }
    }

    private fun routeFor(request: HttpRequest): ByteArray {
        val path = URI(request.url).path
        return Fixtures.bytes(northboundRoutes.first { path.endsWith(it.first.removePrefix("/thirdData")) }.second)
    }

    @Test
    fun anEndedSessionLogsInAgainOnce() = runTest {
        val http = ScriptedHttp { request, count ->
            when (val name = URI(request.url).path.substringAfterLast('/')) {
                "login" -> Fixtures.bytes("northbound-login-synthetic.json") to mapOf("xsrf-token" to "token-${count("login")}")
                // The first KPI call finds the session ended (another login elsewhere).
                "getStationRealKpi" -> if (count(name) == 1) """{"success":false,"failCode":305,"data":null}""".toByteArray() to emptyMap()
                else routeFor(request) to emptyMap()
                else -> routeFor(request) to emptyMap()
            }
        }
        val s = client(http).fetch()
        assertEquals(14.37, s.dailyEnergyKWh)
        assertEquals(2, http.paths.count { it == "login" })
        assertEquals("token-2", http.tokens.last())
    }

    @Test
    fun aSecondEndedSessionIsAnErrorNotALoop() = runTest {
        val http = ScriptedHttp { request, count ->
            when (URI(request.url).path.substringAfterLast('/')) {
                "login" -> Fixtures.bytes("northbound-login-synthetic.json") to mapOf("xsrf-token" to "token-${count("login")}")
                "getStationRealKpi" -> """{"success":false,"failCode":305,"data":null}""".toByteArray() to emptyMap()
                else -> routeFor(request) to emptyMap()
            }
        }
        val error = assertFailsWith<SunWattError> { client(http).fetch() }
        assertEquals(SunWattError.BadResponse("failCode 305"), error)
        assertEquals(2, http.paths.count { it == "login" })
        assertEquals(2, http.paths.count { it == "getStationRealKpi" })
        assertNoSecrets(error.toString())
    }

    @Test
    fun rateLimitIsReportedWithoutRetrying() = runTest {
        // Routes are first-match: the 407 goes before the fixtures.
        val http = StubHttpClient()
        http.on("getStationRealKpi", Reply.Ok(Fixtures.bytes("northbound-failcode-synthetic.json")))
        http.addNorthboundRoutes("token-1")
        val error = assertFailsWith<SunWattError> { client(http).fetch() }
        assertEquals(SunWattError.RateLimited, error)
        assertNoSecrets(error.toString())
        // login, plant, the limited KPI call: no retry.
        assertEquals(listOf("login", "getStationList", "getStationRealKpi"), names(http.requests))
    }

    @Test
    fun aLoginRateLimitIsRateLimited() = runTest {
        val http = StubHttpClient(Reply.Ok(Fixtures.bytes("northbound-failcode-synthetic.json")))
        assertEquals(SunWattError.RateLimited, assertFailsWith<SunWattError> { client(http).fetch() })
        assertEquals(listOf("login"), names(http.requests))
    }

    @Test
    fun aRefusedLoginIsAnAccountError() = runTest {
        val refused = StubHttpClient(Reply.Ok("""{"success":false,"failCode":20001,"data":null}"""))
        val error = assertFailsWith<SunWattError> { client(refused).fetch() }
        assertEquals(SunWattError.NorthboundAccountRejected, error)
        assertNoSecrets(error.toString())
    }

    @Test
    fun aLoginWithoutTokenIsBadResponse() = runTest {
        val http = StubHttpClient()
        http.on("login", Reply.Ok(Fixtures.bytes("northbound-login-synthetic.json")))
        assertEquals(SunWattError.BadResponse("login token"), assertFailsWith<SunWattError> { client(http).fetch() })
    }

    @Test
    fun aRateLimitOnTheCurveKeepsTheFetchGoing() = runTest {
        val limited = StubHttpClient()
        limited.on("getKpiStationHour", Reply.Ok(Fixtures.bytes("northbound-failcode-synthetic.json")))
        limited.addNorthboundRoutes("t")
        val s = client(limited).fetch()
        assertEquals(listOf(CurvePoint(now, 4.812)), s.curve)
    }

    @Test
    fun newerServersListPlantsPaged() = runTest {
        val http = StubHttpClient()
        http.on("getStationList", Reply.Ok("""{"success":false,"failCode":401,"data":null}"""))
        http.on("/thirdData/stations", Reply.Ok("""{"success":true,"failCode":0,"data":{"list":[{"plantCode":"NE=12345678","plantName":"Paged &amp; plant"}],"pageCount":1}}"""))
        http.addNorthboundRoutes("t")
        val s = client(http).fetch()
        assertEquals("Paged & plant", s.stationName)
        assertEquals(1, bodyOf(http.requests.first { it.url.endsWith("/stations") }).getValue("pageNo").jsonPrimitive.int)
    }

    @Test
    fun errorTextsNeverHoldTheCodeOrTheToken() = runTest {
        val failures = listOf(
            StubHttpClient(Reply.Failure(IOException("request for $NORTHBOUND_SECRET failed"))),
            StubHttpClient(Reply.Ok("oops $NORTHBOUND_SECRET", status = 500)),
            StubHttpClient(Reply.Ok(ByteArray(0), status = 403)),
            StubHttpClient(Reply.Ok("<html>$NORTHBOUND_SECRET</html>")),
            StubHttpClient(Reply.Ok("""{"success":false,"failCode":1,"data":null}""")),
        )
        for (http in failures) {
            val error = assertFailsWith<SunWattError> { client(http).fetch() }
            assertNoSecrets(error.toString())
            assertNoSecrets(error.message.orEmpty())
        }
        // Failing after the login: the token exists but stays out of the text.
        val afterLogin = StubHttpClient()
        afterLogin.on("getStationList", Reply.Failure(IOException("boom token-1 $NORTHBOUND_SECRET")))
        afterLogin.addNorthboundRoutes("token-1")
        val error = assertFailsWith<SunWattError> { client(afterLogin).fetch() }
        assertTrue(error is SunWattError.Network)
        assertNoSecrets(error.toString())
        assertNoSecrets(error.message.orEmpty())
    }

    @Test
    fun descriptionsHideTheSecrets() = runTest {
        val store = InMemoryNorthboundSessionStore()
        val c = client(stub(), now, store)
        c.fetch()
        val session = assertNotNull(store.load(credentials.accountKey))
        assertEquals("token-1", session.token)
        for (text in listOf(c.toString(), credentials.toString(), session.toString(), store.toString(), NorthboundSession(token = "token-1").toString())) {
            assertNoSecrets(text)
        }
        assertTrue(credentials.toString().contains("systemCode=***"))
    }

    @Test
    fun tokenFromTheCookie() {
        val cookie = HttpResponse(200, mapOf("Set-Cookie" to "XSRF-TOKEN=abc123; Path=/; Secure"))
        assertEquals("abc123", NorthboundClient.tokenFrom(cookie))
        val two = HttpResponse(200, mapOf("Set-Cookie" to "JSESSIONID=x; Path=/, XSRF-TOKEN=def456, other=1"))
        assertEquals("def456", NorthboundClient.tokenFrom(two))
        // The header wins, whatever its case.
        val both = HttpResponse(200, mapOf("XSRF-Token" to "hdr", "Set-Cookie" to "XSRF-TOKEN=abc123"))
        assertEquals("hdr", NorthboundClient.tokenFrom(both))
        assertNull(NorthboundClient.tokenFrom(HttpResponse(200)))
        assertNull(NorthboundClient.tokenFrom(HttpResponse(200, mapOf("Set-Cookie" to "XSRF-TOKEN=; Path=/"))))
    }

    @Test
    fun parsingTolerates() {
        val parse = { text: String -> Json.parseToJsonElement(text) }
        assertEquals(12.5, NorthboundClient.number(parse("\"12.5\"")))
        assertEquals(3.0, NorthboundClient.number(parse("3")))
        assertNull(NorthboundClient.number(parse("\"-\"")))
        assertNull(NorthboundClient.number(parse("\"\"")))
        assertNull(NorthboundClient.number(parse("null")))
        assertNull(NorthboundClient.number(null))
        val devices = parse("""[{"id":"7","devTypeId":38},{"id":8,"devTypeId":62}]""")
        assertEquals(listOf("7"), NorthboundClient.inverters(devices).map { it.id })
        val readings = parse("""[{"dataItemMap":{"active_power":"1.5"}},{"dataItemMap":{"active_power":2}}]""")
        assertEquals(3.5, NorthboundClient.activePower(readings))
        assertNull(NorthboundClient.activePower(parse("[]")))
        assertFailsWith<SunWattError.BadResponse> { NorthboundClient.answer("not json".toByteArray()) }
        assertFailsWith<SunWattError.BadResponse> { NorthboundClient.answer("[1]".toByteArray()) }
        val answer = NorthboundClient.answer("""{"failCode":"407","data":"x"}""".toByteArray())
        assertEquals(407, answer.failCode)
        assertFalse(answer.succeeded)
        assertTrue(NorthboundClient.answer("""{"data":[]}""".toByteArray()).succeeded)
        assertTrue(NorthboundClient.answer("""{"data":[]}""".toByteArray()).data is JsonArray)
    }

    @Test
    fun htmlEntitiesInPlantNames() {
        assertEquals("A & B \"C\" 'D' <E>", HtmlEntities.unescape("A &amp; B &quot;C&quot; &#39;D&#39; &lt;E&gt;"))
        assertEquals("&quot;", HtmlEntities.unescape("&amp;quot;"))
        assertEquals("'", HtmlEntities.unescape("&#x27;"))
        assertEquals("&unknown; & x", HtmlEntities.unescape("&unknown; & x"))
    }

    @Test
    fun hourlyPointsKeepTodayBeforeNowOnly() {
        val data = Json.parseToJsonElement(Fixtures.text("northbound-hour-synthetic.json")).jsonObject.getValue("data")
        val points = NorthboundClient.hourlyPoints(data, now, now)
        assertTrue(points.isNotEmpty())
        assertTrue(points.zipWithNext().all { (a, b) -> a.time < b.time })
        assertTrue(points.all { it.time < now && it.time >= madrid(2026, 10, 17) })
    }
}
