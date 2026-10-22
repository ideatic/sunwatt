package com.ideatic.sunwatt.core.store

import com.ideatic.sunwatt.core.data.TEST_KIOSK_URL
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.data.samplePrices
import com.ideatic.sunwatt.core.data.sampleSolar
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.models.SunWattSettings
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Not the identity: "enc:" + the text reversed, so a test sees whether plaintext reaches the disk. */
class FakeCipher : SecretCipher {
    @Volatile var failDecrypt = false
    @Volatile var failEncrypt = false

    override fun encrypt(plain: String): String {
        if (failEncrypt) throw IllegalStateException("keystore unavailable")
        return "enc:" + plain.reversed()
    }

    override fun decrypt(cipher: String): String {
        if (failDecrypt || !cipher.startsWith("enc:")) throw IllegalStateException("cannot decrypt")
        return cipher.removePrefix("enc:").reversed()
    }
}

class MemoryStorage : FileStorage {
    val files = ConcurrentHashMap<String, ByteArray>()
    override fun read(name: String): ByteArray? = files[name]
    override fun writeAtomically(name: String, bytes: ByteArray) { files[name] = bytes.copyOf() }
    override fun delete(name: String) { files.remove(name) }
}

private const val API_KEY = "ABCDEFGH12345678"
private const val ROOF_KEY = "ROOFKEYROOFKEY99"
private const val ROOF_KIOSK = "https://roof.test/kiosk?kk=ROOFTOKEN"

private fun settingsWithSecrets() = SunWattSettings(
    kioskURL = TEST_KIOSK_URL, showTomorrowPrices = false, priceRegion = PriceRegion.PORTUGAL,
    solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567", solarAPIKey = API_KEY, primaryPlantName = "Home",
    additionalPlants = listOf(
        SolarPlant(id = "plant-2", name = "Roof", provider = SolarProvider.PV_OUTPUT, siteID = "42", apiKey = ROOF_KEY, kioskURL = ROOF_KIOSK),
    ),
)

class StoreTest {
    private val dir: File = Files.createTempDirectory("sunwatt-store-test").toFile()
    private val cipher = FakeCipher()
    private val storage = DirectoryFileStorage(dir)
    private fun store(now: () -> Instant = { madrid(2026, 6, 1, 12) }, errors: MutableList<String>? = null) =
        SunWattStore(storage, cipher, now) { errors?.add(it) }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // --- Settings

    @Test
    fun settingsDefaultAndRoundTrip() {
        val store = store()
        assertEquals(SunWattSettings(), store.loadSettings())
        store.saveSettings(SunWattSettings(kioskURL = "https://x/y?kk=1", showTomorrowPrices = false))
        assertEquals("https://x/y?kk=1", store.loadSettings().kioskURL)
        assertFalse(store.loadSettings().showTomorrowPrices)
    }

    @Test
    fun everySecretRoundTripsAndPlaintextNeverHitsTheDisk() {
        store().saveSettings(settingsWithSecrets())
        // A new store (no cache) reads the file back.
        assertEquals(settingsWithSecrets(), store().loadSettings())
        val onDisk = dir.listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }
        for (secret in listOf(TEST_KIOSK_URL, "TESTKK", API_KEY, ROOF_KEY, ROOF_KIOSK, "ROOFTOKEN")) {
            assertFalse(secret in onDisk, "plain secret on disk")
        }
        // The non-secret fields stay readable in the file.
        assertTrue("\"1234567\"" in onDisk && "Roof" in onDisk)
    }

    @Test
    fun theFileIsTheOnlyPlaceSettingsAreKept() {
        store().saveSettings(settingsWithSecrets())
        assertEquals(listOf(SunWattStore.SETTINGS_FILE), dir.list()!!.sorted())
    }

    @Test
    fun aSecretThatCannotBeDecryptedReadsAsEmptyAndTheRestStays() {
        store().saveSettings(settingsWithSecrets())
        cipher.failDecrypt = true
        val read = store().loadSettings()
        assertEquals("", read.kioskURL)
        assertEquals("", read.solarAPIKey)
        assertEquals("", read.additionalPlants.single().apiKey)
        assertEquals("", read.additionalPlants.single().kioskURL)
        assertEquals(settingsWithSecrets().copy(kioskURL = "", solarAPIKey = "", additionalPlants = listOf(settingsWithSecrets().additionalPlants.single().copy(apiKey = "", kioskURL = ""))), read)
        // The key comes back (a passing Keystore problem): the secrets read again.
        cipher.failDecrypt = false
        assertEquals(settingsWithSecrets(), store().loadSettings())
    }

    @Test
    fun aFailedDecryptIsNotCachedOnTheSameStore() {
        val store = store()
        store.saveSettings(settingsWithSecrets())
        cipher.failDecrypt = true
        assertEquals("", store.loadSettings().kioskURL)
        cipher.failDecrypt = false
        assertEquals(TEST_KIOSK_URL, store.loadSettings().kioskURL)
    }

    @Test
    fun aCorruptSettingsFileReadsAsDefaults() {
        val errors = mutableListOf<String>()
        for (garbage in listOf("nope", "", "[1,2]", "{\"kioskURL\": 5, \"additionalPlants\": 7}x")) {
            File(dir, SunWattStore.SETTINGS_FILE).writeText(garbage)
            assertEquals(SunWattSettings(), store(errors = errors).loadSettings(), "for: $garbage")
        }
        assertTrue(errors.isNotEmpty())
        // Saving over a corrupt file works.
        val store = store()
        store.saveSettings(SunWattSettings(kioskURL = "https://x/y?kk=1"))
        assertEquals("https://x/y?kk=1", store.loadSettings().kioskURL)
    }

    @Test
    fun aSecretThatCannotBeEncryptedIsNeverWrittenAndTheOldFileStays() {
        val store = store()
        store.saveSettings(SunWattSettings(kioskURL = "https://old/?kk=OLD"))
        cipher.failEncrypt = true
        store.saveSettings(SunWattSettings(kioskURL = "https://new/?kk=NEW"))
        val onDisk = File(dir, SunWattStore.SETTINGS_FILE).readText()
        assertFalse("NEW" in onDisk || "WEN" in onDisk)
        cipher.failEncrypt = false
        assertEquals("https://old/?kk=OLD", store().loadSettings().kioskURL)
    }

    @Test
    fun emptySecretsStayEmptyOnDisk() {
        store().saveSettings(SunWattSettings(additionalPlants = listOf(SolarPlant(id = "p2"))))
        val text = File(dir, SunWattStore.SETTINGS_FILE).readText()
        assertFalse("enc:" in text)
        assertEquals(SunWattSettings(additionalPlants = listOf(SolarPlant(id = "p2"))), store().loadSettings())
    }

    @Test
    fun priceRegionRoundTripAndOldSettings() {
        for (region in PriceRegion.entries) {
            val store = store()
            store.saveSettings(SunWattSettings(priceRegion = region))
            assertEquals(region, store.loadSettings().priceRegion)
        }
        File(dir, SunWattStore.SETTINGS_FILE).writeText("""{"kioskURL":"","showTomorrowPrices":false}""")
        val old = store().loadSettings()
        assertEquals(PriceRegion.SPAIN, old.priceRegion)
        assertFalse(old.showTomorrowPrices)
    }

    @Test
    fun anUnknownRegionAndAnUnreadablePlantListKeepTheOtherSettings() {
        File(dir, SunWattStore.SETTINGS_FILE).writeText(
            """{"priceRegion":"mars","showTomorrowPrices":false,"additionalPlants":[{"id":5}]}""",
        )
        val read = store().loadSettings()
        assertEquals(PriceRegion.SPAIN, read.priceRegion)
        assertFalse(read.showTomorrowPrices)
        assertTrue(read.additionalPlants.isEmpty())
    }

    @Test
    fun clearSettingsRemovesTheSecretsToo() {
        val store = store()
        store.saveSettings(settingsWithSecrets())
        store.clearSettings()
        assertEquals(SunWattSettings(), store.loadSettings())
        assertTrue(dir.list()!!.isEmpty())
    }

    // --- Snapshots

    @Test
    fun solarRoundTrip() {
        val store = store()
        assertNull(store.loadSolar())
        val snapshot = sampleSolar()
        store.saveSolar(snapshot)
        assertEquals(snapshot, store.loadSolar())
    }

    @Test
    fun pricesRoundTrip() {
        val store = store()
        assertNull(store.loadPrices())
        val series = samplePrices()
        store.savePrices(series)
        assertEquals(series, store.loadPrices())
    }

    @Test
    fun eachPlantHasItsOwnSnapshotFile() {
        val store = store()
        store.saveSolar(sampleSolar().copy(currentPowerKW = 3.0))
        store.saveSolar(sampleSolar().copy(currentPowerKW = 1.5), plantID = "B8E3C1F2-0000-4000-8000-000000000001")
        assertEquals(3.0, store.loadSolar()?.currentPowerKW)
        assertEquals(1.5, store.loadSolar("B8E3C1F2-0000-4000-8000-000000000001")?.currentPowerKW)
        assertEquals(listOf("solar-B8E3C1F2-0000-4000-8000-000000000001.json", "solar.json"), dir.list()!!.sorted())
        store.deleteSolar("B8E3C1F2-0000-4000-8000-000000000001")
        assertNull(store.loadSolar("B8E3C1F2-0000-4000-8000-000000000001"))
    }

    @Test
    fun aPlantIdIsReducedToSafeFileNameCharacters() {
        val store = store()
        store.saveSolar(sampleSolar(), plantID = "../evil/id")
        assertEquals(listOf("solar-evilid.json"), dir.list()!!.sorted())
        assertEquals(sampleSolar(), store.loadSolar("../evil/id"))
    }

    @Test
    fun corruptFilesGiveNull() {
        val store = store()
        store.saveSolar(sampleSolar())
        store.savePrices(samplePrices())
        File(dir, "solar.json").writeText("garbage")
        File(dir, "prices.json").writeText("{}")
        assertNull(store.loadSolar())
        assertNull(store.loadPrices())
    }

    @Test
    fun olderSnapshotDoesNotReplaceNewerOne() {
        val store = store()
        val newer = sampleSolar(madrid(2026, 6, 1, 12))
        val older = sampleSolar(madrid(2026, 6, 1, 11))
        store.saveSolar(newer)
        store.saveSolar(older)
        assertEquals(newer, store.loadSolar())
        val newest = sampleSolar(madrid(2026, 6, 1, 13))
        store.saveSolar(newest)
        assertEquals(newest, store.loadSolar())
        store.saveSolar(newest) // equal timestamp is accepted
        assertEquals(newest, store.loadSolar())

        val p1 = samplePrices(fetchedAt = madrid(2026, 6, 1, 12))
        store.savePrices(p1)
        store.savePrices(samplePrices(fetchedAt = madrid(2026, 6, 1, 9)))
        assertEquals(p1, store.loadPrices())
    }

    @Test
    fun corruptExistingFileIsReplaced() {
        val store = store()
        store.savePrices(samplePrices())
        File(dir, "prices.json").writeText("garbage")
        val fresh = samplePrices(fetchedAt = madrid(2026, 6, 1, 13))
        store.savePrices(fresh)
        assertEquals(fresh, store.loadPrices())
    }

    @Test
    fun snapshotsLiveInTheGivenDirectory() {
        val store = store()
        store.saveSolar(sampleSolar())
        store.savePrices(samplePrices())
        assertEquals(listOf("prices.json", "solar.json"), dir.list()!!.sorted())
    }

    @Test
    fun unwritableDirectoryDoesNotCrash() {
        val errors = mutableListOf<String>()
        val blocked = File(dir, "file-not-folder").apply { writeText("x") }
        val store = SunWattStore(DirectoryFileStorage(File(blocked, "nope")), cipher, { madrid(2026, 6, 1, 12) }) { errors += it }
        store.saveSolar(sampleSolar())
        store.saveSettings(SunWattSettings(kioskURL = "https://x/?kk=1"))
        assertNull(store.loadSolar())
        assertEquals(SunWattSettings(), store.loadSettings())
        assertTrue(errors.any { it.startsWith("Cannot write") })
        assertFalse(errors.any { "kk=1" in it })
    }

    @Test
    fun concurrentSavesStayConsistent() {
        val store = store()
        val base = madrid(2026, 6, 1, 12)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (0 until 40).map { i ->
            pool.submit {
                start.await()
                store.saveSolar(sampleSolar(base.plusSeconds(i.toLong())))
                store.saveSettings(SunWattSettings(kioskURL = "https://x/?kk=$i", showTomorrowPrices = i % 2 == 0))
                store.noteSolarFailure("account", base.plusSeconds(i.toLong()), "m$i")
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        // Every file is readable and the snapshot is the newest one.
        assertEquals(base.plusSeconds(39), store().loadSolar()?.fetchedAt)
        assertTrue(store().loadSettings().kioskURL.startsWith("https://x/?kk="))
        assertNotNull(store().solarFailure("account"))
        assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
    }

    @Test
    fun futureStampsFromAClockChangeAreReplaced() {
        val real = madrid(2026, 6, 1, 12)
        val store = store(now = { real })
        // A stored time far in the future (clock moved back) no longer blocks a write; a near one still does.
        store.saveSolar(sampleSolar(real.plusSeconds(24 * 3600)))
        store.saveSolar(sampleSolar(real))
        assertEquals(real, store.loadSolar()?.fetchedAt)
        store.saveSolar(sampleSolar(real.plusSeconds(5 * 60)))
        store.saveSolar(sampleSolar(real))
        assertEquals(real.plusSeconds(5 * 60), store.loadSolar()?.fetchedAt)
    }

    @Test
    fun storedSnapshotsUseTheSwiftDateFormat() {
        store().saveSolar(sampleSolar())
        assertTrue("\"fetchedAt\":\"2026-06-01T10:00:00Z\"" in File(dir, "solar.json").readText())
    }

    // --- Failed fetches and kiosk rejections

    @Test
    fun solarFailuresAreKeptPerAccount() {
        val store = store()
        val at = madrid(2026, 6, 1, 12)
        assertNull(store.solarFailure("a"))
        store.noteSolarFailure("a", at, "Limit reached")
        store.noteSolarFailure("b", at.plusSeconds(60), "Rejected")
        assertEquals(SolarFailure(at, "Limit reached"), store().solarFailure("a"))
        assertEquals(SolarFailure(at.plusSeconds(60), "Rejected"), store().solarFailure("b"))
        store.clearSolarFailure("a")
        assertNull(store.solarFailure("a"))
        assertNotNull(store.solarFailure("b"))
        store.clearSolarFailure()
        assertNull(store.solarFailure("b"))
        // Clearing what is not there is fine.
        store.clearSolarFailure("zzz")
    }

    @Test
    fun kioskRejectionKeepsTheFirstTime() {
        val store = store()
        val first = madrid(2026, 6, 1, 12)
        assertNull(store.kioskRejectedSince("link"))
        assertEquals(first, store.noteKioskRejected("link", first))
        assertEquals(first, store.noteKioskRejected("link", first.plusSeconds(600)))
        assertEquals(first, store().kioskRejectedSince("link"))
        assertNull(store.kioskRejectedSince("other"))
        store.noteKioskRejected("other", first.plusSeconds(10))
        store.clearKioskRejection("link")
        assertNull(store.kioskRejectedSince("link"))
        assertEquals(first.plusSeconds(10), store.kioskRejectedSince("other"))
        store.clearKioskRejection()
        assertNull(store.kioskRejectedSince("other"))
    }

    @Test
    fun corruptFailureFilesReadAsNothing() {
        File(dir, "solar-failures.json").writeText("garbage")
        File(dir, "kiosk-rejections.json").writeText("[]")
        val store = store()
        assertNull(store.solarFailure("a"))
        assertNull(store.kioskRejectedSince("a"))
        store.noteSolarFailure("a", madrid(2026, 6, 1, 12), "m")
        assertNotNull(store.solarFailure("a"))
    }

    // --- DirectoryFileStorage

    @Test
    fun directoryStorageWritesAtomicallyAndLeavesNoTempFiles() {
        storage.writeAtomically("a.json", "one".toByteArray())
        storage.writeAtomically("a.json", "two".toByteArray())
        assertContentEquals("two".toByteArray(), storage.read("a.json"))
        assertEquals(listOf("a.json"), dir.list()!!.sorted())
        storage.delete("a.json")
        storage.delete("a.json")
        assertNull(storage.read("a.json"))
    }

    @Test
    fun directoryStorageRefusesNamesWithFolders() {
        for (name in listOf("../x", "a/b", "", "..", "a\\b")) {
            assertFailsWith<IllegalArgumentException> { storage.writeAtomically(name, byteArrayOf(1)) }
        }
    }

    @Test
    fun directoryStorageCreatesTheFolder() {
        val nested = DirectoryFileStorage(File(dir, "a/b"))
        nested.writeAtomically("x.json", byteArrayOf(1))
        assertContentEquals(byteArrayOf(1), nested.read("x.json"))
    }

    @Test
    fun storeAlsoWorksOnMemoryStorage() {
        val memory = MemoryStorage()
        val store = SunWattStore(memory, cipher) { madrid(2026, 6, 1, 12) }
        store.saveSettings(settingsWithSecrets())
        assertEquals(settingsWithSecrets(), store.loadSettings())
        val stored = memory.files.getValue(SunWattStore.SETTINGS_FILE).toString(Charsets.UTF_8)
        assertEquals(settingsWithSecrets(), SunWattJson.decodeFromString<SunWattSettings>(
            // The file has the same shape as a plain settings JSON, only the secrets differ.
            stored.replace("enc:" + TEST_KIOSK_URL.reversed(), TEST_KIOSK_URL)
                .replace("enc:" + API_KEY.reversed(), API_KEY)
                .replace("enc:" + ROOF_KEY.reversed(), ROOF_KEY)
                .replace("enc:" + ROOF_KIOSK.reversed(), ROOF_KIOSK),
        ))
        assertTrue(SunWattJson.encodeToString(settingsWithSecrets()).length > 10)
    }
}
