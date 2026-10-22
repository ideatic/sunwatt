package com.ideatic.sunwatt

import androidx.test.platform.app.InstrumentationRegistry
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.services.NorthboundSession
import com.ideatic.sunwatt.core.services.NorthboundStation
import com.ideatic.sunwatt.core.store.DirectoryFileStorage
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.security.EncryptedNorthboundSessionStore
import com.ideatic.sunwatt.security.KeystoreSecretCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Settings and sessions saved and loaded with the real Keystore cipher and file storage. Also meant to run against a release
 * build (`testBuildType = "release"`) to check that R8 keeps the serializers.
 */
class StoreRoundTripTest {
    private lateinit var directory: File
    private val cipher = KeystoreSecretCipher()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "store-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun settingsRoundTripWithSecrets() {
        val store = SunWattStore(DirectoryFileStorage(directory), cipher)
        val settings = SunWattSettings(
            kioskURL = "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/index.html#/kiosk?kk=demo",
            priceRegion = PriceRegion.PORTUGAL,
            onboardingCompleted = true,
            solarAPIKey = "demo-key",
        )
        store.saveSettings(settings)

        val raw = File(directory, SunWattStore.SETTINGS_FILE).readText()
        assertFalse("secret written in plain text", raw.contains("kk=demo") || raw.contains("demo-key"))
        assertEquals(settings, SunWattStore(DirectoryFileStorage(directory), cipher).loadSettings())
    }

    @Test
    fun sessionsSurviveANewStoreAndAreEncrypted() = runBlocking {
        val storage = DirectoryFileStorage(directory)
        val session = NorthboundSession(token = "demo-session-token", station = NorthboundStation("NE=1", "Demo"))
        EncryptedNorthboundSessionStore(storage, cipher).save("account", session)

        assertFalse(File(directory, EncryptedNorthboundSessionStore.FILE).readText().contains("demo-session-token"))
        val reopened = EncryptedNorthboundSessionStore(storage, cipher)
        assertEquals(session, reopened.load("account"))
        reopened.clear("account")
        assertNull(EncryptedNorthboundSessionStore(storage, cipher).load("account"))
    }
}
