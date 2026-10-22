package com.ideatic.sunwatt.core.services

import com.ideatic.sunwatt.core.models.CurvePoint
import com.ideatic.sunwatt.core.models.InstantIso8601Serializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant

/** The plant of a FusionSolar API account (the first one the API lists). */
@Serializable
data class NorthboundStation(val code: String, val name: String? = null)

/** An inverter of the plant (device type 1 string inverter, 38 residential inverter). */
@Serializable
data class NorthboundDevice(val id: String, val typeID: Int)

/**
 * What a [NorthboundClient] keeps between fetches: the session token, the plant, its inverters and today's hourly
 * curve. The token is a secret (AGENTS.md rule 1, DECISIONS D36): [toString] hides it, and a persistent store must
 * keep it encrypted.
 */
@Serializable
data class NorthboundSession(
    val token: String? = null,
    val station: NorthboundStation? = null,
    val inverters: List<NorthboundDevice>? = null,
    val curve: List<CurvePoint> = emptyList(),
    @Serializable(with = InstantIso8601Serializer::class) val curveFetchedAt: Instant? = null,
) {
    override fun toString(): String =
        "NorthboundSession(token=${if (token == null) "none" else "***"}, station=${station?.code}, " +
            "inverters=${inverters?.size}, curve=${curve.size} points)"
}

/**
 * Where FusionSolar API sessions are kept, by account key ([NorthboundCredentials.accountKey]). Huawei allows one
 * session per account and limits logins, so every client of an account in a process shares one store.
 *
 * Implementations are thread-safe. A persistent implementation (encrypted storage in the app module, DECISIONS D36)
 * must keep the token encrypted, never in a plain file or a log. The default [InMemoryNorthboundSessionStore] keeps
 * sessions in this process only.
 */
interface NorthboundSessionStore {
    suspend fun load(accountKey: String): NorthboundSession?

    suspend fun save(accountKey: String, session: NorthboundSession)

    suspend fun clear(accountKey: String)
}

/** Process-local store: for tests and as the default until the app provides the encrypted one. */
class InMemoryNorthboundSessionStore : NorthboundSessionStore {
    private val mutex = Mutex()
    private val sessions = HashMap<String, NorthboundSession>()

    override suspend fun load(accountKey: String): NorthboundSession? = mutex.withLock { sessions[accountKey] }

    override suspend fun save(accountKey: String, session: NorthboundSession) {
        mutex.withLock { sessions[accountKey] = session }
    }

    override suspend fun clear(accountKey: String) {
        mutex.withLock { sessions.remove(accountKey) }
    }

    override fun toString(): String = "InMemoryNorthboundSessionStore"
}
