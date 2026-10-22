package com.ideatic.sunwatt.security

import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.services.NorthboundSession
import com.ideatic.sunwatt.core.services.NorthboundSessionStore
import com.ideatic.sunwatt.core.store.FileStorage
import com.ideatic.sunwatt.core.store.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

/**
 * FusionSolar API sessions in memory and on disk (DECISIONS D36). The whole file is encrypted with [cipher], so the
 * token is never in plain text on disk. A file that cannot be read or decrypted (key lost, damaged) counts as empty:
 * the client logs in again. A failed disk write keeps the in-memory session.
 */
class EncryptedNorthboundSessionStore(
    private val storage: FileStorage,
    private val cipher: SecretCipher,
    private val fileName: String = FILE,
) : NorthboundSessionStore {
    private val mutex = Mutex()
    private var sessions: MutableMap<String, NorthboundSession>? = null
    private val serializer = MapSerializer(String.serializer(), NorthboundSession.serializer())

    override suspend fun load(accountKey: String): NorthboundSession? = mutex.withLock { loaded()[accountKey] }

    override suspend fun save(accountKey: String, session: NorthboundSession) {
        mutex.withLock {
            loaded()[accountKey] = session
            persist()
        }
    }

    override suspend fun clear(accountKey: String) {
        mutex.withLock {
            if (loaded().remove(accountKey) != null) persist()
        }
    }

    /** Caller holds the lock. Reads the file once. */
    private suspend fun loaded(): MutableMap<String, NorthboundSession> {
        sessions?.let { return it }
        val map = withContext(Dispatchers.IO) {
            val bytes = storage.read(fileName)
            if (bytes == null) {
                HashMap()
            } else try {
                HashMap(SunWattJson.decodeFromString(serializer, cipher.decrypt(bytes.toString(Charsets.UTF_8))))
            } catch (e: Exception) {
                HashMap()
            }
        }
        sessions = map
        return map
    }

    private suspend fun persist() {
        val current = sessions ?: return
        withContext(Dispatchers.IO) {
            try {
                if (current.isEmpty()) {
                    storage.delete(fileName)
                } else {
                    val encrypted = cipher.encrypt(SunWattJson.encodeToString(serializer, current))
                    storage.writeAtomically(fileName, encrypted.toByteArray(Charsets.UTF_8))
                }
            } catch (e: IOException) {
                // Disk problem: the session stays in memory, the next process logs in again.
            } catch (e: java.security.GeneralSecurityException) {
                // Keystore problem: never write the token in the clear.
            }
        }
    }

    override fun toString(): String = "EncryptedNorthboundSessionStore"

    companion object {
        const val FILE = "northbound-sessions.enc"
    }
}
