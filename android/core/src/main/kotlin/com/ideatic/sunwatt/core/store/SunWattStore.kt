package com.ideatic.sunwatt.core.store

import com.ideatic.sunwatt.core.models.InstantIso8601Serializer
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.models.SunWattSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/**
 * The last failed fetch of a solar account: when, and what failed. [code] and [detail] are the [SunWattError] case (null
 * in failures stored by an older version, and for an error that is not a [SunWattError]); [message] is its text.
 */
@Serializable
data class SolarFailure(
    @Serializable(with = InstantIso8601Serializer::class) val at: Instant,
    val message: String,
    val code: String? = null,
    val detail: String? = null,
)

/**
 * Settings and last-good data of the app, in app-private files (Android port of the Swift `AppGroupStore`).
 *
 * - Settings are ONE JSON file ([SETTINGS_FILE]) in which every secret field (the kiosk link and the API key of each
 *   plant) holds its encrypted form ([SecretCipher]); the file is replaced atomically, so secrets and the other settings
 *   can never get out of step. A secret that cannot be decrypted (key lost after a restore on a new device) reads as
 *   empty and the other settings stay.
 * - Snapshots are separate JSON files; a write never replaces a newer one (`fetchedAt`).
 * - Calls are short and synchronous (call them from an IO dispatcher) and guarded by one lock.
 * - Nothing here logs or throws a secret. Failed disk operations are reported to [onError] by file name only.
 */
class SunWattStore(
    private val storage: FileStorage,
    private val cipher: SecretCipher,
    private val now: () -> Instant = Instant::now,
    private val onError: (String) -> Unit = {},
) {
    private val lock = Any()

    /** Stored bytes and the settings read from them; reused while the file does not change (decrypting costs a Keystore call). */
    private var settingsCache: Pair<ByteArray, SunWattSettings>? = null

    // --- Settings

    /**
     * The saved settings with their secrets. No file, or an unreadable one: the defaults. Secrets that cannot be decrypted
     * read as empty.
     */
    fun loadSettings(): SunWattSettings = synchronized(lock) {
        val bytes = storage.read(SETTINGS_FILE) ?: return SunWattSettings()
        settingsCache?.let { (cachedBytes, cachedSettings) -> if (cachedBytes.contentEquals(bytes)) return cachedSettings }
        val root = try {
            SunWattJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return SunWattSettings().also { onError("Cannot decode $SETTINGS_FILE") }
        var complete = true
        val decrypted = mapSecrets(root) { stored ->
            if (stored.isEmpty()) ""
            else try {
                cipher.decrypt(stored)
            } catch (e: Exception) {
                complete = false
                ""
            }
        }
        val settings = try {
            SunWattJson.decodeFromJsonElement(SunWattSettings.serializer(), decrypted)
        } catch (e: IllegalArgumentException) {
            onError("Cannot decode $SETTINGS_FILE")
            return SunWattSettings()
        }
        // A secret that failed may be a passing Keystore problem: read again next time instead of caching the gap.
        settingsCache = if (complete) bytes to settings else null
        settings
    }

    /** Saves [settings], the secrets encrypted, in one atomic write. Unchanged settings are not written again. */
    fun saveSettings(settings: SunWattSettings) {
        synchronized(lock) {
            val plain = SunWattJson.encodeToJsonElement(SunWattSettings.serializer(), settings) as JsonObject
            val encrypted = try {
                mapSecrets(plain) { secret -> if (secret.isBlank()) "" else cipher.encrypt(secret) }
            } catch (e: Exception) {
                // Never store a secret that could not be encrypted, and do not keep the old file half-valid.
                onError("Cannot encrypt secrets of $SETTINGS_FILE")
                return
            }
            val bytes = SunWattJson.encodeToString(JsonObject.serializer(), encrypted).toByteArray(Charsets.UTF_8)
            if (!write(SETTINGS_FILE, bytes)) return
            settingsCache = null
        }
    }

    /** Deletes the saved settings (the secrets go with them: they live in the same file). */
    fun clearSettings() {
        synchronized(lock) {
            settingsCache = null
            storage.delete(SETTINGS_FILE)
        }
    }

    /**
     * [root] with [transform] applied to every secret field: `kioskURL`, `solarAPIKey` and the `kioskURL` and `apiKey` of
     * each additional plant. Other fields and unexpected shapes pass through untouched.
     */
    private fun mapSecrets(root: JsonObject, transform: (String) -> String): JsonObject {
        fun JsonObject.mapped(vararg keys: String): JsonObject = JsonObject(
            mapValues { (key, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                if (key in keys && text != null) JsonPrimitive(transform(text)) else value
            },
        )
        val plants = root["additionalPlants"] as? JsonArray
        val result = root.mapped("kioskURL", "solarAPIKey")
        if (plants == null) return result
        return JsonObject(
            result + ("additionalPlants" to JsonArray(plants.map { if (it is JsonObject) it.mapped("kioskURL", "apiKey") else it })),
        )
    }

    // --- Last good data

    /** The last good snapshot of a plant ([SolarPlant.id]; the first plant by default). */
    fun loadSolar(plantID: String = SolarPlant.PRIMARY_ID): SolarSnapshot? =
        read(SolarSnapshot.serializer(), solarFile(plantID))

    /** Skipped when the stored snapshot has a newer `fetchedAt`. */
    fun saveSolar(snapshot: SolarSnapshot, plantID: String = SolarPlant.PRIMARY_ID) =
        writeIfNewer(SolarSnapshot.serializer(), snapshot, solarFile(plantID), snapshot.fetchedAt)

    fun loadPrices(): PriceSeries? = read(PriceSeries.serializer(), PRICES_FILE)

    /** Skipped when the stored series has a newer `fetchedAt`. */
    fun savePrices(series: PriceSeries) = writeIfNewer(PriceSeries.serializer(), series, PRICES_FILE, series.fetchedAt)

    /** Forgets the snapshot of a plant (e.g. when the plant is removed). */
    fun deleteSolar(plantID: String) {
        synchronized(lock) { storage.delete(solarFile(plantID)) }
    }

    private fun solarFile(plantID: String): String {
        if (plantID == SolarPlant.PRIMARY_ID) return "solar.json"
        // Plant ids are UUIDs; anything else is reduced to safe file name characters.
        return "solar-${plantID.filter { it.isLetterOrDigit() || it == '-' }}.json"
    }

    private fun <T> read(serializer: kotlinx.serialization.KSerializer<T>, file: String): T? = synchronized(lock) {
        val bytes = storage.read(file) ?: return null
        try {
            SunWattJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            onError("Cannot decode $file")
            null
        }
    }

    @Serializable
    private class FetchedAtProbe(@Serializable(with = InstantIso8601Serializer::class) val fetchedAt: Instant)

    /** Writes unless the stored data is newer. A stored time far in the future (clock moved back) does not block. */
    private fun <T> writeIfNewer(serializer: kotlinx.serialization.KSerializer<T>, value: T, file: String, fetchedAt: Instant) {
        synchronized(lock) {
            val bytes = SunWattJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
            val stored = storage.read(file)?.let {
                try {
                    SunWattJson.decodeFromString(FetchedAtProbe.serializer(), it.toString(Charsets.UTF_8)).fetchedAt
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            if (stored != null && stored > fetchedAt && stored <= now().plusSeconds(CLOCK_TOLERANCE_SECONDS)) return
            write(file, bytes)
        }
    }

    /** Caller holds the lock. False (and a report by file name) when the disk write failed. */
    private fun write(file: String, bytes: ByteArray): Boolean = try {
        storage.writeAtomically(file, bytes)
        true
    } catch (e: java.io.IOException) {
        onError("Cannot write $file")
        false
    }

    // --- Failed fetches of rate-limited providers

    /** The last failed fetch of the solar account with this cache ID: when, and the message to show. */
    fun solarFailure(accountID: String): SolarFailure? = synchronized(lock) { failuresLocked()[accountID] }

    fun noteSolarFailure(accountID: String, at: Instant, message: String, code: String? = null, detail: String? = null) {
        synchronized(lock) {
            writeMap(FAILURES_FILE, SolarFailure.serializer(), failuresLocked() + (accountID to SolarFailure(at, message, code, detail)))
        }
    }

    /** Forgets the failure of this account; null: of every account. */
    fun clearSolarFailure(accountID: String? = null) {
        synchronized(lock) {
            if (accountID == null) return storage.delete(FAILURES_FILE)
            val all = failuresLocked()
            if (accountID in all) writeMap(FAILURES_FILE, SolarFailure.serializer(), all - accountID)
        }
    }

    private fun failuresLocked(): Map<String, SolarFailure> = readMap(FAILURES_FILE, SolarFailure.serializer())

    // --- Kiosk link rejections

    /** Since when the kiosk link with this cache ID has been rejected without a success in between. Null when it was not. */
    fun kioskRejectedSince(linkID: String): Instant? = synchronized(lock) { rejectionsLocked()[linkID] }

    /** Records a rejection of the link; keeps the first time when it was already rejected. Returns when rejections started. */
    fun noteKioskRejected(linkID: String, at: Instant): Instant = synchronized(lock) {
        val all = rejectionsLocked()
        all[linkID] ?: at.also { writeMap(REJECTIONS_FILE, InstantIso8601Serializer, all + (linkID to at)) }
    }

    /** A successful fetch (or a new link) ends the rejection of this link; null: of every link. */
    fun clearKioskRejection(linkID: String? = null) {
        synchronized(lock) {
            if (linkID == null) return storage.delete(REJECTIONS_FILE)
            val all = rejectionsLocked()
            if (linkID in all) writeMap(REJECTIONS_FILE, InstantIso8601Serializer, all - linkID)
        }
    }

    private fun rejectionsLocked(): Map<String, Instant> = readMap(REJECTIONS_FILE, InstantIso8601Serializer)

    private fun <V> readMap(file: String, valueSerializer: kotlinx.serialization.KSerializer<V>): Map<String, V> {
        val bytes = storage.read(file) ?: return emptyMap()
        return try {
            SunWattJson.decodeFromString(MapSerializer(String.serializer(), valueSerializer), bytes.toString(Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    private fun <V> writeMap(file: String, valueSerializer: kotlinx.serialization.KSerializer<V>, map: Map<String, V>) {
        write(file, SunWattJson.encodeToString(MapSerializer(String.serializer(), valueSerializer), map).toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val SETTINGS_FILE = "settings.json"
        const val PRICES_FILE = "prices.json"
        private const val FAILURES_FILE = "solar-failures.json"
        private const val REJECTIONS_FILE = "kiosk-rejections.json"

        /** A stored `fetchedAt` this far in the future means the device clock went back: it no longer blocks writes. */
        const val CLOCK_TOLERANCE_SECONDS = 10 * 60L
    }
}
