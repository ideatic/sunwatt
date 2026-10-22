package com.ideatic.sunwatt.core.services

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Minimal HTTP request, independent of any HTTP library, so clients can be tested with fixtures. */
class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeout: Duration = 20.seconds,
) {
    /** Never prints header values (they may carry an API key). */
    override fun toString(): String = "HttpRequest($method <url>)"
}

/** A complete HTTP response (any status). */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    /** Header value by name, ignoring case; null when absent. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The body as UTF-8 text. */
    fun text(): String = body.toString(Charsets.UTF_8)

    override fun toString(): String = "HttpResponse($status, ${body.size} bytes)"
}

/**
 * HTTP abstraction. Contract: [execute] returns the response for ANY status (a non-2xx is not an error here, see
 * [HttpFetch.checkStatus]); it throws an [IOException] (no connection, timeout) or `CancellationException` when
 * cancelled. It is cancellable and never blocks the caller's thread.
 */
interface HttpClient {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * Production client: no cache, no cookies, short timeouts (a widget refresh has a small time budget, so a hanging
 * request must fail fast). The per-request [HttpRequest.timeout] is ignored: connect and read time out at 10 s and
 * the whole call at 12 s, like the Apple session.
 */
class OkHttpSunWattClient(base: OkHttpClient = OkHttpClient()) : HttpClient {
    private val client: OkHttpClient = base.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val contentType = request.headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.toMediaTypeOrNull()
        val needsBody = request.method.uppercase() !in setOf("GET", "HEAD")
        val body = request.body?.toRequestBody(contentType) ?: if (needsBody) ByteArray(0).toRequestBody(contentType) else null
        builder.method(request.method, body)
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val headers = it.headers.names().associateWith { name -> it.headers.values(name).joinToString(", ") }
                        continuation.resume(HttpResponse(it.code, headers, it.body.bytes()))
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}
