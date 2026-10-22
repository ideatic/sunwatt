package com.ideatic.sunwatt.ui

import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.PriceSeries
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.core.models.SunWattLink
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.plants
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.PriceDataSource
import com.ideatic.sunwatt.core.services.SolarDataSource
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.store.FileStorage
import com.ideatic.sunwatt.core.store.SecretCipher
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class MemoryStorage : FileStorage {
    private val files = ConcurrentHashMap<String, ByteArray>()
    override fun read(name: String): ByteArray? = files[name]
    override fun writeAtomically(name: String, bytes: ByteArray) { files[name] = bytes.copyOf() }
    override fun delete(name: String) { files.remove(name) }
}

private object PlainCipher : SecretCipher {
    override fun encrypt(plain: String) = "enc:$plain"
    override fun decrypt(cipher: String) = cipher.removePrefix("enc:")
}

private class CountingSolar(private val now: () -> Instant) : SolarDataSource {
    val calls = AtomicInteger()
    override suspend fun fetch(): SolarSnapshot {
        calls.incrementAndGet()
        return SampleData.snapshot(SampleData.now, now())
    }
}

private class CountingPrices(private val now: () -> Instant) : PriceDataSource {
    val calls = AtomicInteger()
    override suspend fun fetch(day: Instant): PriceSeries = fetchTodayAndTomorrow(false)
    override suspend fun fetchTodayAndTomorrow(includeTomorrow: Boolean): PriceSeries {
        calls.incrementAndGet()
        return SampleData.prices(PriceRegion.SPAIN, now())
    }
}

private const val KIOSK = "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=TESTTOKEN"

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now: Instant = SampleData.now
    private lateinit var store: SunWattStore
    private lateinit var solar: CountingSolar
    private lateinit var prices: CountingPrices
    private var widgetReloads = 0
    private val created = mutableListOf<AppViewModel>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        now = SampleData.now
        store = SunWattStore(MemoryStorage(), PlainCipher, now = { now })
        solar = CountingSolar { now }
        prices = CountingPrices { now }
        widgetReloads = 0
    }

    @AfterEach
    fun tearDown() {
        // Stop the loops (pending refresh, clock) of this test before the main dispatcher goes away.
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    private fun configured(change: (SunWattSettings) -> SunWattSettings = { it }) {
        store.saveSettings(change(SunWattSettings(kioskURL = KIOSK, onboardingCompleted = true)))
    }

    /** Fresh data in the store, so a load inside the cache window needs no request. */
    private fun storeFreshData() {
        val settings = store.loadSettings()
        val account = SolarAccount.fromSettings(settings)!!
        store.saveSolar(SampleData.snapshot(SampleData.now, now).copy(endpointID = account.cacheID))
        store.savePrices(SampleData.prices(PriceRegion.SPAIN, now))
    }

    private fun TestScope.viewModel(): AppViewModel {
        val loader = SunWattLoader(store, makeSolar = { solar }, prices = prices, now = { now })
        val vm = AppViewModel(
            store = store, loader = loader, settingsRepository = SettingsRepository(store, dispatcher),
            makeClient = { solar }, makeInverterFinder = { error("not used") },
            reloadWidgets = { widgetReloads++ }, countWidgets = { 2 }, clock = { now }, io = dispatcher,
        )
        advanceUntilIdle()
        created += vm
        return vm
    }

    @Test
    fun `the first load shows the cache and a start inside the cache window asks nothing`() = runTest(dispatcher) {
        configured()
        storeFreshData()
        val vm = viewModel()
        assertTrue(vm.state.value.isReady)
        assertTrue(vm.state.value.data?.solar != null)
        vm.start()
        assertEquals(0, solar.calls.get())
        assertEquals(0, prices.calls.get())
        assertEquals(2, vm.state.value.widgetCount)
    }

    @Test
    fun `sunwatt refresh link is an explicit refresh that skips the cache window`() = runTest(dispatcher) {
        configured()
        storeFreshData()
        val vm = viewModel()
        vm.start()
        assertEquals(0, solar.calls.get())
        vm.open(SunWattLink.parse("sunwatt://refresh")!!)
        assertEquals(1, solar.calls.get())
        assertEquals(1, prices.calls.get())
        assertTrue(widgetReloads > 0)
    }

    @Test
    fun `a refresh link during onboarding is ignored`() = runTest(dispatcher) {
        store.saveSettings(SunWattSettings(kioskURL = KIOSK, onboardingCompleted = false))
        val vm = viewModel()
        assertTrue(vm.state.value.showsOnboarding)
        vm.open(SunWattLink.REFRESH)
        assertEquals(0, solar.calls.get())
    }

    @Test
    fun `changing region or tomorrow prices fetches once after the pause`() = runTest(dispatcher) {
        configured()
        storeFreshData()
        val vm = viewModel()
        vm.updateSettings { it.copy(priceRegion = PriceRegion.FRANCE) }
        advanceTimeBy(1000)
        assertEquals(0, prices.calls.get())
        advanceUntilIdle()
        assertEquals(1, prices.calls.get())
        assertEquals(PriceRegion.FRANCE, store.loadSettings().priceRegion)
        vm.updateSettings { it.copy(showTomorrowPrices = false) }
        advanceUntilIdle()
        assertEquals(2, prices.calls.get())
    }

    @Test
    fun `edits that change no solar account fetch nothing`() = runTest(dispatcher) {
        configured()
        storeFreshData()
        val vm = viewModel()
        vm.updateSettings { it.copy(primaryPlantName = "Roof") }
        advanceUntilIdle()
        assertEquals(0, solar.calls.get())
        assertEquals(0, prices.calls.get())
        assertEquals("Roof", store.loadSettings().primaryPlantName)
        // Typing an invalid link where there was none changes the status ("not set up" to "not valid"): fetch.
        vm.updateSettings { it.copy(kioskURL = "") }
        advanceUntilIdle()
        val afterClear = solar.calls.get() + prices.calls.get()
        vm.updateSettings { it.copy(kioskURL = "abc") }
        advanceUntilIdle()
        val afterInput = solar.calls.get() + prices.calls.get()
        assertTrue(afterInput > afterClear)
        // A further edit that stays invalid fetches nothing.
        vm.updateSettings { it.copy(kioskURL = "abcd") }
        advanceUntilIdle()
        assertEquals(afterInput, solar.calls.get() + prices.calls.get())
    }

    @Test
    fun `a new kiosk link starts its year now and reopens the expiry notice`() = runTest(dispatcher) {
        configured { it.copy(kioskExpiryNoticeDismissed = true, kioskLinkSavedAt = now.minusSeconds(400L * 24 * 3600)) }
        val vm = viewModel()
        vm.updateSettings { it.copy(kioskURL = KIOSK.replace("TESTTOKEN", "OTHERTOKEN")) }
        advanceUntilIdle()
        assertEquals(now, vm.settings.kioskLinkSavedAt)
        assertFalse(vm.settings.kioskExpiryNoticeDismissed)
        assertTrue(KioskEndpoint.parse(vm.settings.kioskURL) != null)
    }

    @Test
    fun `plants can be added, edited and removed but the first one stays`() = runTest(dispatcher) {
        configured()
        val vm = viewModel()
        val id = vm.addPlant()
        assertEquals(listOf(SolarPlant.PRIMARY_ID, id), vm.settings.plants.map { it.id })
        vm.updatePlant(id) { it.copy(primaryPlantName = "Barn") }
        assertEquals("Barn", vm.settings.additionalPlants.single().name)
        assertEquals("Barn", vm.settingsFocusedOn(id).primaryPlantName)
        // The first plant's own fields are untouched by the edit of another plant.
        assertEquals("", vm.settings.primaryPlantName)
        vm.removePlant(SolarPlant.PRIMARY_ID)
        assertEquals(2, vm.settings.plants.size)
        vm.removePlant(id)
        advanceUntilIdle()
        assertEquals(1, vm.settings.plants.size)
        assertTrue(store.loadSettings().additionalPlants.isEmpty())
    }

    @Test
    fun `the clock moves to the next quarter hour and refreshes data older than the widget interval`() = runTest(dispatcher) {
        configured()
        storeFreshData()
        val vm = viewModel()
        vm.start()
        assertEquals(Instant.parse("2026-10-17T11:45:00Z"), AppViewModel.nextQuarterHour(Instant.parse("2026-10-17T11:40:00Z")))
        assertEquals(Instant.parse("2026-10-17T12:00:00Z"), AppViewModel.nextQuarterHour(Instant.parse("2026-10-17T11:45:00Z")))
        // Five minutes later: only `now` moves.
        now = now.plusSeconds(5 * 60)
        vm.tick(now)
        assertEquals(now, vm.state.value.now)
        assertEquals(0, solar.calls.get())
        // Past the widget interval the data is older than the cache window: it is loaded again.
        now = now.plusSeconds(20 * 60)
        vm.tick(now)
        assertEquals(1, prices.calls.get())
        assertEquals(now, vm.state.value.now)
    }

    @Test
    fun `testing an unsaved kiosk link returns the snapshot and an invalid one fails at once`() = runTest(dispatcher) {
        configured()
        val vm = viewModel()
        assertEquals(SunWattError.InvalidKioskURL, vm.testKiosk("nope").exceptionOrNull())
        assertTrue(vm.testKiosk(KIOSK).isSuccess)
    }

    @Test
    fun `a newer refresh supersedes the load in flight`() = runTest(dispatcher) {
        configured()
        val vm = viewModel()
        val first = async(dispatcher) { vm.refresh() }
        val second = async(dispatcher) { vm.refresh() }
        first.await()
        second.await()
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasLoaded)
        assertTrue(vm.state.value.data?.solar != null)
    }
}
