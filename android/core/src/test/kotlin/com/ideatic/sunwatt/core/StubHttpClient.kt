package com.ideatic.sunwatt.core

import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.HttpRequest
import com.ideatic.sunwatt.core.services.HttpResponse
import kotlinx.coroutines.awaitCancellation
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Fixture-backed HTTP client. Routes by a substring of the request URL; records every request. */
class StubHttpClient(private val fallback: Reply = Reply.Failure(IOException("Unable to resolve host"))) : HttpClient {
    sealed interface Reply {
        class Ok(val body: ByteArray, val status: Int = 200, val headers: Map<String, String> = emptyMap()) : Reply {
            constructor(body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) :
                this(body.toByteArray(), status, headers)
        }

        class Failure(val error: Exception) : Reply

        /** Never answers: for deadline and cancellation tests. */
        data object Hang : Reply
    }

    private val routes = CopyOnWriteArrayList<Pair<String, Reply>>()
    private val recorded = CopyOnWriteArrayList<HttpRequest>()

    /** Replies with [reply] for any URL that contains [match] (first match wins). */
    fun on(match: String, reply: Reply) {
        routes.add(match to reply)
    }

    val requests: List<HttpRequest> get() = recorded.toList()
    val requestedUrls: List<String> get() = recorded.map { it.url }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        recorded.add(request)
        val reply = routes.firstOrNull { request.url.contains(it.first) }?.second ?: fallback
        return when (reply) {
            is Reply.Ok -> HttpResponse(reply.status, reply.headers, reply.body)
            is Reply.Failure -> throw reply.error
            Reply.Hang -> awaitCancellation()
        }
    }
}
