package com.ideatic.sunwatt.core.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Shared request plumbing for the clients. */
object HttpFetch {
    /** A GET for [url] that asks for JSON. */
    fun request(url: String, timeout: Duration = 20.seconds): HttpRequest =
        HttpRequest(url = url, headers = mapOf("Accept" to "application/json"), timeout = timeout)

    /**
     * Sends [request]. Cancellation passes through as `CancellationException` (also when the library reports a call
     * cancelled as an I/O error while the coroutine is cancelled); every other failure becomes
     * [SunWattError.Network] with [redacting] (a secret that may appear in the message) replaced by `***`.
     */
    suspend fun perform(http: HttpClient, request: HttpRequest, redacting: String? = null): HttpResponse {
        try {
            return http.execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            var text = e.message ?: e::class.java.simpleName
            if (!redacting.isNullOrEmpty()) text = text.replace(redacting, "***")
            throw SunWattError.Network(text)
        }
    }

    /** A non-2xx status becomes [SunWattError.Http]. */
    fun checkStatus(response: HttpResponse) {
        if (response.status !in 200..299) throw SunWattError.Http(response.status)
    }
}
