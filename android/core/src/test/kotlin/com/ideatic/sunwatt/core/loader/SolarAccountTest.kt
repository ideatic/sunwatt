package com.ideatic.sunwatt.core.loader

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.TEST_KIOSK_URL
import com.ideatic.sunwatt.core.data.madrid
import com.ideatic.sunwatt.core.inverter.FakeModbusTransport
import com.ideatic.sunwatt.core.inverter.FakeSolar
import com.ideatic.sunwatt.core.inverter.FroniusSolarAPIClient
import com.ideatic.sunwatt.core.inverter.HuaweiModbusClient
import com.ideatic.sunwatt.core.inverter.LocalInverterSolarClient
import com.ideatic.sunwatt.core.inverter.SunSpecClient
import com.ideatic.sunwatt.core.inverter.fakeSunSpecRegisters
import com.ideatic.sunwatt.core.models.HomeEnergy
import com.ideatic.sunwatt.core.models.LiveInverterReading
import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.ModbusEndpoint
import com.ideatic.sunwatt.core.models.NotConfiguredSolarSource
import com.ideatic.sunwatt.core.models.SolarAPICredentials
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarProviderProfile
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.models.hasSolarInput
import com.ideatic.sunwatt.core.models.invalidSolarInputError
import com.ideatic.sunwatt.core.services.FusionSolarKioskClient
import com.ideatic.sunwatt.core.services.KioskEndpoint
import com.ideatic.sunwatt.core.services.NorthboundClient
import com.ideatic.sunwatt.core.services.NorthboundCredentials
import com.ideatic.sunwatt.core.services.PvOutputClient
import com.ideatic.sunwatt.core.services.SolarEdgeClient
import com.ideatic.sunwatt.core.services.SunWattError
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EDGE_KEY = "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
private fun credentials(id: String = "1234567", key: String = EDGE_KEY) = SolarAPICredentials.parse(id, key)!!

class SolarAccountTest {
    @Test
    fun accountFollowsTheSelectedProvider() {
        var settings = SunWattSettings(kioskURL = TEST_KIOSK_URL, solarSiteID = "1234567", solarAPIKey = EDGE_KEY)
        assertEquals(SolarProvider.FUSION_SOLAR, SolarAccount.fromSettings(settings)?.provider)
        settings = settings.copy(solarProvider = SolarProvider.SOLAR_EDGE)
        assertEquals(SolarAccount.SolarEdge(credentials()), SolarAccount.fromSettings(settings))
        settings = settings.copy(solarProvider = SolarProvider.PV_OUTPUT)
        assertEquals(SolarAccount.PvOutput(credentials()), SolarAccount.fromSettings(settings))
        settings = settings.copy(solarAPIKey = "bad key!")
        assertNull(SolarAccount.fromSettings(settings))
        settings = settings.copy(solarProvider = SolarProvider.FUSION_SOLAR, kioskURL = "nope")
        assertNull(SolarAccount.fromSettings(settings))
    }

    @Test
    fun cacheIDsAreStableDistinctAndSecretFree() {
        val kiosk = SolarAccount.FusionSolar(KioskEndpoint.parse(TEST_KIOSK_URL))
        assertEquals(testKioskID, kiosk.cacheID)
        val edge = SolarAccount.SolarEdge(credentials())
        val pv = SolarAccount.PvOutput(credentials())
        assertEquals(edge.cacheID, SolarAccount.SolarEdge(credentials()).cacheID)
        assertNotEquals(edge.cacheID, pv.cacheID)
        assertNotEquals(edge.cacheID, SolarAccount.SolarEdge(credentials("7654321")).cacheID)
        assertEquals(64, edge.cacheID.length)
        assertTrue(EDGE_KEY !in edge.cacheID && "1234567" !in edge.cacheID)
    }

    @Test
    fun descriptionsNeverShowASecret() {
        val northbound = SolarAccount.FusionSolarAPI(NorthboundCredentials.create("", "api_user", "SYSTEMCODE99")!!)
        val accounts = listOf(
            SolarAccount.FusionSolar(KioskEndpoint.parse(TEST_KIOSK_URL), ModbusEndpoint("192.168.1.50")),
            SolarAccount.SolarEdge(credentials()), SolarAccount.PvOutput(credentials()), northbound,
        )
        for (account in accounts) {
            val text = account.toString()
            assertFalse(EDGE_KEY in text || "TESTKK" in text || "SYSTEMCODE99" in text, text)
        }
        assertEquals("NotConfiguredSolarSource", NotConfiguredSolarSource.toString())
    }

    @Test
    fun inputStateFollowsTheProvider() {
        var settings = SunWattSettings(kioskURL = TEST_KIOSK_URL)
        assertTrue(settings.hasSolarInput)
        assertEquals(SunWattError.InvalidKioskURL, settings.invalidSolarInputError)
        settings = settings.copy(solarProvider = SolarProvider.SOLAR_EDGE)
        assertFalse(settings.hasSolarInput)
        settings = settings.copy(solarSiteID = "  ")
        assertFalse(settings.hasSolarInput)
        settings = settings.copy(solarAPIKey = "x")
        assertTrue(settings.hasSolarInput)
        assertEquals(SunWattError.InvalidSolarCredentials, settings.invalidSolarInputError)
    }

    @Test
    fun huaweiAccountWithInverterAddress() {
        var settings = SunWattSettings(kioskURL = TEST_KIOSK_URL, huaweiLocalHost = "192.168.1.50")
        val account = assertIs<SolarAccount.FusionSolar>(SolarAccount.fromSettings(settings))
        assertEquals("192.168.1.50", account.local?.host)
        assertNotNull(account.kiosk)
        assertEquals(SolarProvider.FUSION_SOLAR, account.provider)
        assertEquals(testKioskID, account.cacheID)
        assertNotNull(account.kioskEndpoint)
        assertIs<LocalInverterSolarClient>(account.client())

        settings = settings.copy(kioskURL = "")
        val dongle = SolarAccount.fromSettings(settings)!!
        assertNull(dongle.kioskEndpoint)
        // Dongle alone: the cache ID is the one written before the kiosk and the dongle shared one account case.
        assertEquals("9e6a8fcc68d3314f657e5f61637273197b76037150f0aa9004ff90b99f12de59", dongle.cacheID)
        assertIs<LocalInverterSolarClient>(dongle.client())
        assertIs<HuaweiModbusClient>(dongle.localReader())

        assertNull(SolarAccount.fromSettings(settings.copy(huaweiLocalHost = "")))
        val bad = settings.copy(huaweiLocalHost = "not an address!")
        assertNull(SolarAccount.fromSettings(bad))
        assertTrue(bad.hasSolarInput)
        assertEquals(SunWattError.InvalidInverterAddress, bad.invalidSolarInputError)
    }

    @Test
    fun solarEdgeWithAnInverter() {
        var settings = SunWattSettings(solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567", solarAPIKey = "ABCDEFGH12345678")
        val cloudOnly = SolarAccount.fromSettings(settings)!!
        assertNull(cloudOnly.localInverter)
        assertNull(cloudOnly.liveOnlyClient())
        settings = settings.copy(sunSpecHost = "192.168.1.40:1502")
        val account = SolarAccount.fromSettings(settings)!!
        val edge = credentials(key = "ABCDEFGH12345678")
        assertEquals(SolarAccount.SolarEdge(edge, ModbusEndpoint("192.168.1.40", 1502)), account)
        // Adding the inverter keeps the cloud cache.
        assertEquals(cloudOnly.cacheID, account.cacheID)
        assertIs<LocalInverterSolarClient>(account.client())
        assertIs<LocalInverterSolarClient>(account.liveOnlyClient())
        assertIs<SunSpecClient>(account.localReader())
        settings = settings.copy(solarProvider = SolarProvider.PV_OUTPUT)
        assertEquals(1502, SolarAccount.fromSettings(settings)?.localInverter?.port)
        // An address that does not parse is ignored: the cloud keeps working.
        settings = settings.copy(sunSpecHost = "not an address!")
        assertEquals(SolarAccount.PvOutput(edge), SolarAccount.fromSettings(settings))
        // FusionSolar does not read the SunSpec address.
        settings = settings.copy(solarProvider = SolarProvider.FUSION_SOLAR, kioskURL = TEST_KIOSK_URL, sunSpecHost = "192.168.1.40")
        assertNull(SolarAccount.fromSettings(settings)?.localInverter)
    }

    @Test
    fun froniusAccountFromSettings() {
        var settings = SunWattSettings(solarProvider = SolarProvider.FRONIUS)
        assertNull(SolarAccount.fromSettings(settings))
        assertFalse(settings.hasSolarInput)
        settings = settings.copy(froniusHost = "not an address!")
        assertNull(SolarAccount.fromSettings(settings))
        assertTrue(settings.hasSolarInput)
        assertEquals(SunWattError.InvalidInverterAddress, settings.invalidSolarInputError)

        settings = settings.copy(froniusHost = " 192.168.1.30 ")
        val account = SolarAccount.fromSettings(settings)!!
        assertEquals(SolarAccount.Fronius(ModbusEndpoint("192.168.1.30", 80)), account)
        assertEquals(SolarProvider.FRONIUS, account.provider)
        assertNull(account.kioskEndpoint)
        assertEquals(64, account.cacheID.length)
        assertIs<LocalInverterSolarClient>(account.client())
        assertIs<FroniusSolarAPIClient>(account.localReader())
        assertNotNull(account.liveOnlyClient())

        settings = settings.copy(froniusHost = "192.168.1.30:8080")
        assertEquals(8080, SolarAccount.fromSettings(settings)?.localInverter?.port)
        assertNotEquals(account.cacheID, SolarAccount.fromSettings(settings)?.cacheID)
    }

    @Test
    fun froniusProviderProfile() {
        assertTrue(SolarProvider.FRONIUS in SolarProvider.pickerCases)
        assertEquals(SolarProviderProfile.Setup.HOME_NETWORK, SolarProvider.FRONIUS.profile.setup)
        assertTrue(!SolarProvider.FRONIUS.usesCredentialFields && !SolarProvider.FRONIUS.usesAPIKey)
        assertEquals(LocalInverterKind.FRONIUS, LocalInverterKind.of(SolarProvider.FRONIUS))
        assertEquals(listOf(80), LocalInverterKind.FRONIUS.ports)
        assertEquals(80, LocalInverterKind.FRONIUS.defaultPort)
    }

    @Test
    fun northboundAccountAndProfile() {
        var plant = SolarPlant(provider = SolarProvider.FUSION_SOLAR_API, siteID = "api_user", apiKey = "SYSTEMCODE99")
        val account = SolarAccount.fromPlant(plant)!!
        assertEquals(SolarAccount.FusionSolarAPI(NorthboundCredentials.create("", "api_user", "SYSTEMCODE99")!!), account)
        assertIs<NorthboundClient>(account.client())
        assertEquals(10 * 60, account.provider.minimumFetchInterval.inWholeSeconds)
        assertEquals(SolarProvider.FUSION_SOLAR, account.provider.pickerGroup)
        assertTrue(SolarProvider.FUSION_SOLAR_API !in SolarProvider.pickerCases)
        assertEquals(
            listOf(SolarProvider.FUSION_SOLAR, SolarProvider.SOLAR_EDGE, SolarProvider.PV_OUTPUT, SolarProvider.FRONIUS),
            SolarProvider.pickerCases,
        )
        assertEquals(LocalInverterKind.HUAWEI, account.provider.profile.localInverter)
        // With the dongle at home: live data on top, the Huawei reader.
        plant = plant.copy(huaweiLocalHost = "192.168.1.50")
        val local = SolarAccount.fromPlant(plant)!!
        assertIs<LocalInverterSolarClient>(local.client())
        assertIs<HuaweiModbusClient>(local.localReader())
        assertEquals(account.cacheID, local.cacheID)
        // Typed but not valid: the account error.
        var settings = SunWattSettings(solarProvider = SolarProvider.FUSION_SOLAR_API, solarSiteID = "api_user")
        assertTrue(SolarAccount.fromSettings(settings) == null && settings.hasSolarInput)
        assertEquals(SunWattError.InvalidNorthboundAccount, settings.invalidSolarInputError)
        settings = settings.copy(solarAPIKey = "SYSTEMCODE99", northboundHost = "evil.example")
        assertNull(SolarAccount.fromSettings(settings))
    }

    @Test
    fun clientsMatchTheProvider() {
        var settings = SunWattSettings(kioskURL = TEST_KIOSK_URL)
        assertIs<FusionSolarKioskClient>(SolarAccount.fromSettings(settings)!!.client())
        settings = settings.copy(solarProvider = SolarProvider.SOLAR_EDGE)
        assertNull(SolarAccount.fromSettings(settings))
        settings = settings.copy(solarSiteID = "1234567", solarAPIKey = EDGE_KEY)
        assertIs<SolarEdgeClient>(SolarAccount.fromSettings(settings)!!.client())
        settings = settings.copy(solarProvider = SolarProvider.PV_OUTPUT)
        assertIs<PvOutputClient>(SolarAccount.fromSettings(settings)!!.client())
        // A provider set up with an ID and key has no API-key account for the others.
        assertNull(SolarAccount.api(SolarProvider.FRONIUS, credentials()))
        assertNull(SolarAccount.api(SolarProvider.FUSION_SOLAR, credentials()))
    }

    @Test
    fun accountWithoutAnySourceReadsAsNotConfigured() = runTest {
        // FromPlant never builds this; the stand-in keeps the contract if one ever slips through.
        assertFailsWith<SunWattError.NotConfigured> { NotConfiguredSolarSource.fetch() }
    }
}

class SolarProviderLoaderTest {
    @Test
    fun invalidCredentialsAreAnErrorWithoutNetwork() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "abc", solarAPIKey = EDGE_KEY)) }
        val loader = SunWattLoader(store, prices = FakePlain(Result.success(storeSamplePrices())), makeSolar = { SolarAccount.fromSettings(it)?.client() }, now = { loaderNow })
        assertEquals(SourceStatus.Error.of(SunWattError.InvalidSolarCredentials), loader.load().solarStatus)
    }

    @Test
    fun emptyCredentialsAreNotConfigured() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(kioskURL = TEST_KIOSK_URL, solarProvider = SolarProvider.PV_OUTPUT)) }
        val loader = SunWattLoader(store, prices = FakePlain(Result.success(storeSamplePrices())), makeSolar = { SolarAccount.fromSettings(it)?.client() }, now = { loaderNow })
        assertEquals(SourceStatus.NotConfigured, loader.load().solarStatus)
    }

    @Test
    fun snapshotIsTiedToTheAccount() = runTest {
        val store = tempStore().apply { saveSettings(SunWattSettings(solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567", solarAPIKey = EDGE_KEY)) }
        val solar = storeSampleSolar(endpointID = null)
        val prices = FakePlain(Result.success(storeSamplePrices()))
        val data = makeLoader(store, FakeSolar(Result.success(solar)), prices).load()
        assertEquals(SourceStatus.Fresh, data.solarStatus)
        assertEquals(SolarAccount.SolarEdge(credentials()).cacheID, store.loadSolar()?.endpointID)
        assertNotNull(makeLoader(store, FakeSolar(Result.success(solar)), prices).cached().solar)

        // Another provider with the same ID and key: the cached snapshot does not apply.
        store.saveSettings(store.loadSettings().copy(solarProvider = SolarProvider.PV_OUTPUT))
        val after = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), prices).load()
        assertNull(after.solar)
        assertEquals(SourceStatus.Error.of(SunWattError.Unavailable), after.solarStatus)
    }

    @Test
    fun solarEdgeReusesDataYoungerThanItsFetchInterval() = runTest {
        val settings = SunWattSettings(solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567", solarAPIKey = EDGE_KEY)
        val store = tempStore().apply { saveSettings(settings) }
        val id = SolarAccount.SolarEdge(credentials()).cacheID
        val prices = FakePlain(Result.success(storeSamplePrices()))
        val newer = storeSampleSolar(loaderNow, endpointID = null)

        // 10 minutes old: reused without fetching, even with no cache window (explicit refresh).
        val recent = storeSampleSolar(loaderNow.minusSeconds(10 * 60), id)
        store.saveSolar(recent)
        val reused = makeLoader(store, FakeSolar(Result.failure(SunWattError.Unavailable)), prices).load()
        assertEquals(SourceStatus.Fresh, reused.solarStatus)
        assertEquals(recent.fetchedAt, reused.solar?.fetchedAt)

        // 25 minutes old: fetched again. A fresh store: `saveSolar` never replaces a newer snapshot with an older one.
        val oldStore = tempStore().apply { saveSettings(settings) }
        oldStore.saveSolar(storeSampleSolar(loaderNow.minusSeconds(25 * 60), id))
        val fetched = makeLoader(oldStore, FakeSolar(Result.success(newer)), prices).load()
        assertEquals(loaderNow, fetched.solar?.fetchedAt)

        // PVOutput has no limit.
        assertEquals(0, SolarProvider.PV_OUTPUT.minimumFetchInterval.inWholeSeconds)
        assertEquals(0, SolarProvider.FUSION_SOLAR.minimumFetchInterval.inWholeSeconds)
    }
}

class SunSpecLoaderTest {
    private val now = madrid(2026, 6, 1, 12, 30)
    private val settings = SunWattSettings(
        showTomorrowPrices = false, solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567",
        solarAPIKey = "ABCDEFGH12345678", sunSpecHost = "192.168.1.40:1502",
    )

    private fun inverter(registers: Map<Int, Int>?) = SunSpecClient(ModbusEndpoint("192.168.1.40", 1502)) {
        if (registers != null) FakeModbusTransport(registers) else FakeModbusTransport(emptyMap(), reachable = false)
    }

    @Test
    fun inverterIsReadWhileTheCloudWaits() = runTest {
        val store = tempStore().apply { saveSettings(settings) }
        val id = SolarAccount.fromSettings(settings)!!.cacheID
        store.saveSolar(storeSampleSolar(now.minusSeconds(5 * 60), id).copy(dailyEnergyKWh = 4.0))
        val cloudSource = CallCountingSolar(Result.success(storeSampleSolar(now)))
        val local = inverter(fakeSunSpecRegisters())
        fun loader(date: Instant) = SunWattLoader(
            store, makeSolar = { cloudSource }, prices = FakePlain(Result.success(storeSamplePrices())), now = { date },
            makeLiveSolar = { LocalInverterSolarClient(local, cloud = null, keepsCloudHome = true, now = { date }) },
        )
        val first = loader(now).load()
        assertEquals(0, cloudSource.count)
        assertEquals(SourceStatus.Fresh, first.solarStatus)
        assertEquals(true, first.solar?.isLive)
        assertEquals(2.912, first.solar?.currentPowerKW!!, 1e-9)
        assertEquals(4.0, first.solar?.dailyEnergyKWh)
        assertEquals(now.minusSeconds(5 * 60), first.solar?.cloudFetchedAt)
        // 10 minutes later: the live snapshot is new, but the cloud limit still counts from the cloud fetch.
        loader(now.plusSeconds(10 * 60)).load()
        assertEquals(0, cloudSource.count)
        loader(now.plusSeconds(16 * 60)).load()
        assertEquals(1, cloudSource.count)
    }

    @Test
    fun awayTheStoredCloudDataIsReused() = runTest {
        val store = tempStore().apply { saveSettings(settings) }
        val id = SolarAccount.fromSettings(settings)!!.cacheID
        val cloud = storeSampleSolar(now.minusSeconds(5 * 60), id)
        store.saveSolar(cloud)
        val cloudSource = CallCountingSolar(Result.success(storeSampleSolar(now)))
        val away = inverter(null)
        val data = SunWattLoader(
            store, makeSolar = { cloudSource }, prices = FakePlain(Result.success(storeSamplePrices())), now = { now },
            makeLiveSolar = { LocalInverterSolarClient(away, cloud = null) },
        ).load()
        assertEquals(0, cloudSource.count)
        assertEquals(cloud, data.solar)
        assertEquals(SourceStatus.Fresh, data.solarStatus)
    }

    @Test
    fun aFailingCloudIsNotAskedAgainWhileTheInverterAnswers() = runTest {
        val store = tempStore().apply { saveSettings(settings) }
        val cloudSource = CallCountingSolar(Result.failure(SunWattError.Http(429)))
        val local = inverter(fakeSunSpecRegisters())
        fun loader(date: Instant) = SunWattLoader(
            store,
            makeSolar = { LocalInverterSolarClient(local, cloudSource, keepsCloudHome = true, now = { date }) },
            prices = FakePlain(Result.success(storeSamplePrices())), now = { date },
            makeLiveSolar = { LocalInverterSolarClient(local, cloud = null, keepsCloudHome = true, now = { date }) },
        )
        val first = loader(now).load()
        assertEquals(1, cloudSource.count)
        assertEquals(true, first.solar?.isLive)
        val second = loader(now.plusSeconds(5 * 60)).load()
        assertEquals(1, cloudSource.count)
        assertEquals(true, second.solar?.isLive)
        assertEquals(SourceStatus.Fresh, second.solarStatus)
        loader(now.plusSeconds(21 * 60)).load()
        assertEquals(2, cloudSource.count)
    }
}

class FillingInTest {
    @Test
    fun fillingInKeepsCloudFiguresForAnInverterAlone() {
        val cloud = storeSampleSolar(madrid(2026, 6, 1, 12, 0)).copy(
            dailyEnergyKWh = 4.0, home = HomeEnergy(consumptionKW = 0.8, gridKW = -2.0, dailyConsumptionKWh = 6.0),
        )
        val alone = LocalInverterSolarClient.merge(LiveInverterReading(3.0, totalEnergyKWh = 18420.0), null, madrid(2026, 6, 1, 12, 5), keepsBaseHome = true)
        val filled = SunWattLoader.fillingIn(alone, cloud)
        assertEquals(4.0, filled.dailyEnergyKWh)
        assertEquals(cloud.home, filled.home)
        assertEquals(madrid(2026, 6, 1, 12, 0), filled.cloudFetchedAt)
        assertEquals("Casa", filled.stationName)
    }

    @Test
    fun liveFillsTheCurveFromTheCache() {
        val live = LocalInverterSolarClient.merge(LiveInverterReading(3.0, dailyEnergyKWh = 5.0, totalEnergyKWh = 1.0), null, madrid(2026, 6, 1, 12, 30))
            .copy(monthEnergyKWh = null)
        val cached = storeSampleSolar(madrid(2026, 6, 1, 12, 0)).copy(monthEnergyKWh = 300.0)
        val filled = SunWattLoader.fillingIn(live, cached)
        assertEquals(2, filled.curve.size)
        assertEquals(3.0, filled.curve.last().kW)
        assertEquals("Casa", filled.stationName)
        assertEquals(300.0, filled.monthEnergyKWh)
    }

    @Test
    fun monthAndYearOfAnotherPeriodAreNotCarriedOver() {
        val live = LocalInverterSolarClient.merge(LiveInverterReading(3.0, dailyEnergyKWh = 5.0), null, madrid(2026, 7, 1, 10, 0))
        val cached = storeSampleSolar(madrid(2026, 6, 30, 20, 0)).copy(monthEnergyKWh = 300.0, yearEnergyKWh = 2000.0)
        val filled = SunWattLoader.fillingIn(live, cached)
        assertNull(filled.monthEnergyKWh)
        assertEquals(2000.0, filled.yearEnergyKWh)
    }
}
