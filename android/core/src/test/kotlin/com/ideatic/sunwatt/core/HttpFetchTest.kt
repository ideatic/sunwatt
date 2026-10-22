package com.ideatic.sunwatt.core

import com.ideatic.sunwatt.core.services.HttpFetch
import com.ideatic.sunwatt.core.services.HttpRequest
import com.ideatic.sunwatt.core.services.HttpResponse
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpFetchTest {
    private val secret = "SECRETTOKEN12345"

    @Test
    fun requestAsksForJson() {
        val request = HttpFetch.request("https://example.test/x")
        assertEquals("application/json", request.headers["Accept"])
        assertEquals("GET", request.method)
    }

    @Test
    fun networkErrorRedactsTheSecret() = runTest {
        val stub = StubHttpClient(StubHttpClient.Reply.Failure(IOException("Failed to connect to /v1/$secret/x")))
        val error = assertFailsWith<SunWattError.Network> {
            HttpFetch.perform(stub, HttpRequest("https://example.test/v1/$secret/x"), redacting = secret)
        }
        assertFalse(error.detail.contains(secret))
        assertTrue(error.detail.contains("***"))
        assertFalse(error.toString().contains(secret))
    }

    @Test
    fun cancellationPassesThrough() = runTest {
        val stub = StubHttpClient(StubHttpClient.Reply.Failure(CancellationException("stop")))
        assertFailsWith<CancellationException> { HttpFetch.perform(stub, HttpRequest("https://example.test/")) }
    }

    @Test
    fun cancelledCoroutineIsNotANetworkError() = runTest {
        val stub = StubHttpClient(StubHttpClient.Reply.Hang)
        val job = async { HttpFetch.perform(stub, HttpRequest("https://example.test/")) }
        testScheduler.advanceUntilIdle()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun timeoutIsCancellationNotNetwork() = runTest {
        val stub = StubHttpClient(StubHttpClient.Reply.Hang)
        val result = withTimeoutOrNull(1000) { HttpFetch.perform(stub, HttpRequest("https://example.test/")) }
        assertNull(result)
    }

    @Test
    fun nonSuccessStatusIsHttpError() {
        HttpFetch.checkStatus(HttpResponse(204))
        val error = assertFailsWith<SunWattError.Http> { HttpFetch.checkStatus(HttpResponse(403)) }
        assertEquals(SunWattError.Http(403), error)
        assertFailsWith<SunWattError.Http> { HttpFetch.checkStatus(HttpResponse(199)) }
        assertFailsWith<SunWattError.Http> { HttpFetch.checkStatus(HttpResponse(300)) }
    }

    @Test
    fun stubRoutesByUrlAndRecords() = runTest {
        val stub = StubHttpClient()
        stub.on("/a", StubHttpClient.Reply.Ok("one"))
        stub.on("/b", StubHttpClient.Reply.Ok("two", status = 404, headers = mapOf("X-Test" to "1")))
        assertEquals("one", HttpFetch.perform(stub, HttpRequest("https://h/a")).text())
        val second = HttpFetch.perform(stub, HttpRequest("https://h/b"))
        assertEquals(404, second.status)
        assertEquals("1", second.header("x-test"))
        assertEquals(listOf("https://h/a", "https://h/b"), stub.requestedUrls)
        assertFailsWith<SunWattError.Network> { HttpFetch.perform(stub, HttpRequest("https://h/none")) }
    }

    @Test
    fun fixturesAreReadable() {
        assertTrue(Fixtures.bytes("energycharts-AT-2026-10-17.json").isNotEmpty())
        assertFailsWith<IllegalStateException> { Fixtures.text("does-not-exist.json") }
    }
}
