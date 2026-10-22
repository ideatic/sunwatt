package com.ideatic.sunwatt.wear

import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.store.DirectoryFileStorage
import com.ideatic.sunwatt.core.store.SecretCipher
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.wear.sync.WearSettingsInbox
import com.ideatic.sunwatt.wear.sync.WearSettingsSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class WearSettingsInboxTest {
    @TempDir
    lateinit var folder: File

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = java.util.Base64.getEncoder().encodeToString(plain.toByteArray())
        override fun decrypt(cipher: String) = String(java.util.Base64.getDecoder().decode(cipher))
    }

    private fun store() = SunWattStore(DirectoryFileStorage(folder), cipher)

    private fun bytes(region: PriceRegion, kiosk: String = "") =
        WearSettingsSync.encode(SunWattSettings(kioskURL = kiosk, showTomorrowPrices = true, priceRegion = region))

    @Test
    fun contextRoundTripsSettings() {
        val settings = SunWattSettings(kioskURL = "https://example.invalid/kiosk", showTomorrowPrices = false, priceRegion = PriceRegion.GERMANY)
        assertEquals(settings, WearSettingsSync.decode(WearSettingsSync.encode(settings)))
    }

    @Test
    fun missingOrBrokenBytesGiveNull() {
        assertEquals(null, WearSettingsSync.decode(null))
        assertEquals(null, WearSettingsSync.decode("nope".toByteArray()))
    }

    @Test
    fun applySavesIntoTheStoreAndNotifies() {
        val store = store()
        val saved = AtomicInteger()
        val notified = AtomicInteger()
        val inbox = WearSettingsInbox(store, afterSave = { saved.incrementAndGet() })
        inbox.onSettingsChange { notified.incrementAndGet() }
        assertTrue(inbox.apply(bytes(PriceRegion.GERMANY)))
        assertEquals(PriceRegion.GERMANY, store.loadSettings().priceRegion)
        assertEquals(1, saved.get())
        assertEquals(1, notified.get())
    }

    @Test
    fun secretsAreEncryptedInTheWatchStore() {
        val store = store()
        WearSettingsInbox(store).apply(bytes(PriceRegion.SPAIN, kiosk = "https://example.invalid/kiosk?kk=DEMO"))
        assertEquals("https://example.invalid/kiosk?kk=DEMO", store.loadSettings().kioskURL)
        assertFalse(File(folder, SunWattStore.SETTINGS_FILE).readText().contains("kk=DEMO"))
    }

    @Test
    fun sameSettingsAreNotAChange() {
        val store = store()
        val saved = AtomicInteger()
        val notified = AtomicInteger()
        val inbox = WearSettingsInbox(store, afterSave = { saved.incrementAndGet() })
        inbox.onSettingsChange { notified.incrementAndGet() }
        assertTrue(inbox.apply(bytes(PriceRegion.FRANCE)))
        assertFalse(inbox.apply(bytes(PriceRegion.FRANCE)))
        assertEquals(1, saved.get())
        assertEquals(1, notified.get())
    }

    @Test
    fun brokenBytesAreIgnored() {
        val store = store()
        val notified = AtomicInteger()
        val inbox = WearSettingsInbox(store)
        inbox.onSettingsChange { notified.incrementAndGet() }
        assertFalse(inbox.apply(null))
        assertFalse(inbox.apply("nope".toByteArray()))
        assertEquals(SunWattSettings(), store.loadSettings())
        assertEquals(0, notified.get())
    }

    /** The service gets the stored item at start, before the UI registers its handler. */
    @Test
    fun aChangeBeforeTheHandlerExistsIsDeliveredOnceWhenItRegisters() {
        val inbox = WearSettingsInbox(store())
        assertTrue(inbox.apply(bytes(PriceRegion.AUSTRIA)))
        val notified = AtomicInteger()
        inbox.onSettingsChange { notified.incrementAndGet() }
        assertEquals(1, notified.get())
        // Registering again does not repeat it.
        inbox.onSettingsChange { notified.incrementAndGet() }
        assertEquals(1, notified.get())
        // New changes after that go straight to the handler.
        assertTrue(inbox.apply(bytes(PriceRegion.BELGIUM)))
        assertEquals(2, notified.get())
    }

    @Test
    fun nothingPendingMeansNoCallOnRegistration() {
        val inbox = WearSettingsInbox(store())
        val notified = AtomicInteger()
        inbox.onSettingsChange { notified.incrementAndGet() }
        assertEquals(0, notified.get())
    }

    @Test
    fun aChangeSeenByTheHandlerIsNotKeptForLater() {
        val inbox = WearSettingsInbox(store())
        val first = AtomicInteger()
        val second = AtomicInteger()
        inbox.onSettingsChange { first.incrementAndGet() }
        assertTrue(inbox.apply(bytes(PriceRegion.ITALY_NORTH)))
        inbox.onSettingsChange { second.incrementAndGet() }
        assertEquals(1, first.get())
        assertEquals(0, second.get())
    }

    @Test
    fun parallelAppliesAndRegistrationAreSafe() {
        val store = store()
        val inbox = WearSettingsInbox(store)
        val notified = AtomicInteger()
        val regions = PriceRegion.entries
        val seen = CopyOnWriteArrayList<Boolean>()
        val pool = Executors.newFixedThreadPool(8)
        val tasks = (0 until 40).map { i ->
            pool.submit {
                seen += inbox.apply(bytes(regions[i % regions.size], kiosk = "https://h.test/x?kk=$i"))
                if (i == 20) inbox.onSettingsChange { notified.incrementAndGet() }
            }
        }
        tasks.forEach { it.get() }
        pool.shutdown()
        assertTrue(regions.contains(store.loadSettings().priceRegion))
        // Every change was either seen by the handler or kept for it; at least the ones after registration arrived.
        assertTrue(notified.get() >= 1)
    }
}
