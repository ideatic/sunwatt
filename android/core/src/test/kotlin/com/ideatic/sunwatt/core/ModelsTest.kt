package com.ideatic.sunwatt.core

import com.ideatic.sunwatt.core.models.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private const val EDGE_KEY = "ABCDEFGH12345678"
private const val KIOSK = "https://eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=SECRETKIOSKTOKEN"

class ModelsTest {
    // --- Credentials

    @Test
    fun credentialsAreTrimmedAndValidated() {
        val c = SolarAPICredentials.parse(" 1234567\n", "  $EDGE_KEY ")
        assertEquals("1234567", c?.siteID)
        assertEquals(EDGE_KEY, c?.apiKey)
        assertNull(SolarAPICredentials.parse("", EDGE_KEY))
        assertNull(SolarAPICredentials.parse("12a4", EDGE_KEY))
        assertNull(SolarAPICredentials.parse("1234567", "short"))
        assertNull(SolarAPICredentials.parse("1234567", "ABCD-EFGH-1234"))
        assertNull(SolarAPICredentials.parse("1234567", "ABCDÉFGH1234"))
        assertNull(SolarAPICredentials.parse("١٢٣", EDGE_KEY))
        assertNull(SolarAPICredentials.parse("1234567890123", EDGE_KEY))
        assertNotNull(SolarAPICredentials.parse("123456789012", EDGE_KEY))
        assertNull(SolarAPICredentials.parse("1", "A".repeat(129)))
        assertNotNull(SolarAPICredentials.parse("1", "A".repeat(128)))
        assertEquals(SolarAPICredentials.parse("1", EDGE_KEY), SolarAPICredentials.parse("1", EDGE_KEY))
    }

    @Test
    fun credentialsDescriptionHidesTheKey() {
        val c = SolarAPICredentials.parse("1234567", EDGE_KEY)!!
        assertFalse("$c".contains(EDGE_KEY))
        assertTrue("$c".contains("***"))
    }

    // --- Modbus endpoint

    @Test
    fun endpointUsesThePortOfTheKind() {
        val plain = ModbusEndpoint.parse("192.168.1.30", defaultPort = 80)!!
        assertEquals(80, plain.port)
        assertEquals("192.168.1.30", plain.text(80))
        val other = ModbusEndpoint.parse("192.168.1.30:8080", defaultPort = 80)!!
        assertEquals(8080, other.port)
        assertEquals("192.168.1.30:8080", other.text(80))
        assertEquals(502, ModbusEndpoint.parse("192.168.1.40")?.port)
    }

    @Test
    fun endpointText() {
        assertEquals("192.168.1.40:1502", ModbusEndpoint("192.168.1.40", 1502).text)
        assertEquals("192.168.1.40", ModbusEndpoint("192.168.1.40").text)
        assertTrue(ModbusEndpoint.hasPort("192.168.1.40:502"))
        assertFalse(ModbusEndpoint.hasPort(" 192.168.1.40 "))
    }

    @Test
    fun endpointParsingRejectsBadAddresses() {
        assertEquals(ModbusEndpoint("192.168.1.40", 502, 1), ModbusEndpoint.parse("  192.168.1.40 "))
        assertEquals(ModbusEndpoint("inverter.local", 1502, 126), ModbusEndpoint.parse("inverter.local:1502", unitID = 126))
        for (bad in listOf(
            "", "  ", "http://192.168.1.40", "192.168.1.40/x", "192.168.1.", "192.168.1", "192.168.1.256", "1.2.3.4.5",
            "192.168..1", ".local", "-x", "host.", "a b", "host:0", "host:65536", "host:abc", "host:", ":80", "::1",
            "host:١٢", "host:-5",
        )) {
            assertNull(ModbusEndpoint.parse(bad), "should reject '$bad'")
        }
        assertNull(ModbusEndpoint.parse("192.168.1.40", unitID = 248))
        assertNull(ModbusEndpoint.parse("192.168.1.40", unitID = -1))
        assertEquals(247, ModbusEndpoint.parse("192.168.1.40", unitID = 247)?.unitID)
        assertEquals(0, ModbusEndpoint.parse("192.168.1.40", unitID = 0)?.unitID)
        assertNull(ModbusEndpoint.parse("a".repeat(254)))
        assertNotNull(ModbusEndpoint.parse("a".repeat(253)))
        assertEquals(65535, ModbusEndpoint.parse("host:65535")?.port)
        assertEquals(80, ModbusEndpoint.parse("host:0080")?.port)
    }

    @Test
    fun inverterKindData() {
        assertEquals(LocalInverterKind.FRONIUS, LocalInverterKind.of(SolarProvider.FRONIUS))
        assertEquals(listOf(80), LocalInverterKind.FRONIUS.ports)
        assertEquals(80, LocalInverterKind.FRONIUS.defaultPort)
        assertEquals(listOf(502, 1502), LocalInverterKind.SUN_SPEC.ports)
        assertEquals(listOf(1, 0), LocalInverterKind.HUAWEI.unitIDs)
        assertEquals(LocalInverterKind.SUN_SPEC, LocalInverterKind.fromRaw("sunSpec"))
    }

    // --- Link

    @Test
    fun linkParsing() {
        assertEquals(SunWattLink.REFRESH, SunWattLink.parse(SunWattLink.REFRESH_URL))
        assertEquals(SunWattLink.REFRESH, SunWattLink.parse("SUNWATT://Refresh"))
        assertNull(SunWattLink.parse("sunwatt://settings"))
        assertNull(SunWattLink.parse("https://refresh"))
        assertNull(SunWattLink.parse("not a url"))
        assertNull(SunWattLink.parse("sunwatt:refresh"))
    }

    // --- Snapshot

    @Test
    fun selfSufficiencyNeedsImportAndUse() {
        assertEquals(0.6, HomeEnergy(dailyConsumptionKWh = 10.0, dailyImportKWh = 4.0).selfSufficiency)
        assertNull(HomeEnergy(dailyConsumptionKWh = 10.0).selfSufficiency)
        assertNull(HomeEnergy(dailyConsumptionKWh = 0.0, dailyImportKWh = 0.0).selfSufficiency)
        assertEquals(0.0, HomeEnergy(dailyConsumptionKWh = 1.0, dailyImportKWh = 3.0).selfSufficiency)
        assertEquals(1.0, HomeEnergy(dailyConsumptionKWh = 10.0, dailyImportKWh = -2.0).selfSufficiency)
    }

    @Test
    fun clampingProductionRemovesNegatives() {
        val t = Instant.parse("2026-06-01T10:00:00Z")
        val snapshot = SolarSnapshot(
            currentPowerKW = -0.02, dailyEnergyKWh = -0.1,
            curve = listOf(CurvePoint(t, -0.5), CurvePoint(t.plusSeconds(300), null), CurvePoint(t.plusSeconds(600), 2.0)),
            fetchedAt = t, monthEnergyKWh = 5.0,
        ).clampingProduction()
        assertEquals(0.0, snapshot.currentPowerKW)
        assertEquals(0.0, snapshot.dailyEnergyKWh)
        assertEquals(listOf(0.0, null, 2.0), snapshot.curve.map { it.kW })
        assertEquals(5.0, snapshot.monthEnergyKWh)
    }

    @Test
    fun snapshotHelpers() {
        val t = Instant.parse("2026-06-01T10:00:00Z")
        val curve = listOf(CurvePoint(t, 1.0), CurvePoint(t.plusSeconds(300), 3.5), CurvePoint(t.plusSeconds(600), null))
        val snapshot = SolarSnapshot(currentPowerKW = 3.5, curve = curve, fetchedAt = t)
        assertEquals(curve[1], snapshot.latestPoint)
        assertEquals(3.5, snapshot.peakKW)
        assertFalse(snapshot.isIdle)
        assertTrue(snapshot.copy(currentPowerKW = 0.01).isIdle)
        assertTrue(snapshot.copy(currentPowerKW = null).isIdle)
        assertNull(SolarSnapshot(curve = emptyList(), fetchedAt = t).peakKW)
    }

    // --- Provider and region data

    @Test
    fun providerProfile() {
        assertTrue(SolarProvider.pickerCases.contains(SolarProvider.FRONIUS))
        assertEquals(
            listOf(SolarProvider.FUSION_SOLAR, SolarProvider.SOLAR_EDGE, SolarProvider.PV_OUTPUT, SolarProvider.FRONIUS),
            SolarProvider.pickerCases,
        )
        assertEquals(SolarProvider.FUSION_SOLAR, SolarProvider.FUSION_SOLAR_API.pickerGroup)
        assertEquals(SolarProviderProfile.Setup.HOME_NETWORK, SolarProvider.FRONIUS.profile.setup)
        assertTrue(!SolarProvider.FRONIUS.usesCredentialFields && !SolarProvider.FRONIUS.usesAPIKey)
        assertTrue(SolarProvider.FUSION_SOLAR_API.usesCredentialFields && !SolarProvider.FUSION_SOLAR_API.usesAPIKey)
        assertTrue(SolarProvider.SOLAR_EDGE.usesAPIKey && SolarProvider.PV_OUTPUT.usesCredentialFields)
        assertEquals(20.minutes, SolarProvider.SOLAR_EDGE.minimumFetchInterval)
        assertEquals(10.minutes, SolarProvider.FUSION_SOLAR_API.minimumFetchInterval)
        assertEquals(Duration.ZERO, SolarProvider.PV_OUTPUT.minimumFetchInterval)
        assertEquals("https://eu5.fusionsolar.huawei.com", SolarProvider.FUSION_SOLAR_API.portalUrl)
        assertEquals(LocalInverterKind.HUAWEI, SolarProvider.FUSION_SOLAR_API.profile.localInverter)
        assertTrue(SolarProvider.SOLAR_EDGE.profile.keepsCloudHome && !SolarProvider.FUSION_SOLAR.profile.keepsCloudHome)
    }

    @Test
    fun rawValuesMatchTheSwiftEnums() {
        assertEquals(
            listOf("fusionSolar", "solarEdge", "pvOutput", "fusionSolarAPI", "fronius"),
            SolarProvider.entries.map { it.rawValue },
        )
        assertEquals(
            listOf("spain", "portugal", "france", "germany", "austria", "belgium", "netherlands", "italyNorth"),
            PriceRegion.entries.map { it.rawValue },
        )
        assertEquals(listOf("both", "solarOnly", "priceOnly"), WidgetContent.entries.map { it.rawValue })
        assertEquals(listOf("wholeDay", "around"), PriceWindow.entries.map { it.rawValue })
        assertEquals(listOf("hourly", "quarterHourly"), PriceGranularity.entries.map { it.rawValue })
        assertEquals(listOf("low", "medium", "high"), PriceLevel.entries.map { it.rawValue })
        assertEquals(listOf("huawei", "sunSpec", "fronius"), LocalInverterKind.entries.map { it.rawValue })
        assertEquals(PriceRegion.ITALY_NORTH, PriceRegion.fromRaw("italyNorth"))
        assertNull(PriceRegion.fromRaw("mars"))
    }

    @Test
    fun priceRegionProfile() {
        assertTrue(PriceRegion.SPAIN.isRetailTariff)
        assertEquals("PVPC", PriceRegion.SPAIN.tariffName)
        assertNull(PriceRegion.SPAIN.energyChartsZone)
        assertEquals("Red Eléctrica", PriceRegion.SPAIN.sourceName)
        assertNull(PriceRegion.SPAIN.profile.source.license)
        assertEquals("Europe/Lisbon", PriceRegion.PORTUGAL.timeZone.id)
        assertEquals("Europe/Madrid", PriceRegion.GERMANY.timeZone.id)
        assertEquals("DE-LU", PriceRegion.GERMANY.energyChartsZone)
        assertEquals("IT-North", PriceRegion.ITALY_NORTH.energyChartsZone)
        for (region in PriceRegion.entries.filter { it != PriceRegion.SPAIN }) {
            assertFalse(region.isRetailTariff)
            assertNull(region.tariffName)
            assertEquals("CC BY 4.0", region.profile.source.license)
            assertNotNull(region.energyChartsZone)
        }
        assertEquals("🇪🇸", PriceRegion.SPAIN.flag)
    }

    @Test
    fun pricePoint() {
        val start = Instant.parse("2026-06-01T10:00:00Z")
        val point = PricePoint(start, 3600, 123.0)
        assertEquals(Instant.parse("2026-06-01T11:00:00Z"), point.end)
        assertEquals(0.123, point.eurPerKWh, 1e-9)
        assertTrue(start in point)
        assertFalse(point.end in point)
        assertFalse(start.minusSeconds(1) in point)
        assertEquals(60.minutes, point.duration)
    }

    @Test
    fun constants() {
        assertEquals("Europe/Madrid", Constants.madrid.id)
        assertEquals(45.minutes, Constants.staleAfter)
        assertEquals(15.minutes, Constants.refreshInterval)
    }

    // --- Settings and plants

    private val roof = SolarPlant(
        id = "B8E3C1F2-0000-4000-8000-000000000001", name = "Roof", provider = SolarProvider.SOLAR_EDGE,
        siteID = "1234567", apiKey = EDGE_KEY,
    )

    private fun twoPlants() = SunWattSettings(
        kioskURL = KIOSK, showTomorrowPrices = false, primaryPlantName = "Home", additionalPlants = listOf(roof),
    )

    @Test
    fun settingsKeepTheFirstPlantInPlace() {
        var settings = twoPlants()
        assertEquals(listOf(SolarPlant.PRIMARY_ID, roof.id), settings.plants.map { it.id })
        assertTrue(settings.primaryPlant.kioskURL == KIOSK && settings.primaryPlant.name == "Home")
        assertTrue(settings.hasSeveralPlants)
        val barn = roof.copy(name = "Barn")
        settings = settings.updatingPlant(barn)
        assertEquals("Barn", settings.plant(roof.id)?.name)
        // The first plant is edited through the original fields.
        settings = settings.updatingPlant(settings.primaryPlant.copy(provider = SolarProvider.PV_OUTPUT))
        assertEquals(SolarProvider.PV_OUTPUT, settings.solarProvider)
        // Focusing puts a plant in the first place, for code written for one plant.
        val focused = settings.focused(barn)
        assertTrue(focused.solarProvider == SolarProvider.SOLAR_EDGE && focused.solarSiteID == "1234567")
        assertEquals(barn.copy(id = SolarPlant.PRIMARY_ID), focused.primaryPlant)
        // An unknown id changes nothing.
        assertEquals(settings, settings.updatingPlant(SolarPlant(id = "nope")))
    }

    @Test
    fun singlePlantHasNoOthers() {
        val single = SunWattSettings(kioskURL = KIOSK)
        assertTrue(single.additionalPlants.isEmpty() && !single.hasSeveralPlants && single.primaryPlantName.isEmpty())
        assertEquals(1, single.plants.size)
        assertTrue(single.primaryPlant.isPrimary)
    }

    @Test
    fun plantDisplayName() {
        assertEquals("Roof", roof.displayName("Station"))
        assertEquals("Station", roof.copy(name = "  ").displayName("Station"))
        assertEquals("SolarEdge", roof.copy(name = "").displayName(null))
        assertEquals("SolarEdge", roof.copy(name = "").displayName(""))
    }

    @Test
    fun newPlantsGetUniqueIds() {
        assertTrue(SolarPlant().id != SolarPlant().id)
    }

    @Test
    fun descriptionsHideSecrets() {
        val plant = SolarPlant(provider = SolarProvider.FUSION_SOLAR, kioskURL = KIOSK, apiKey = EDGE_KEY)
        val settings = twoPlants().copy(solarAPIKey = EDGE_KEY)
        for (text in listOf(plant.toString(), roof.toString(), settings.toString(), "$settings")) {
            assertFalse(text.contains(EDGE_KEY), text)
            assertFalse(text.contains("SECRETKIOSKTOKEN"), text)
            assertFalse(text.contains("kk="), text)
        }
        assertTrue(plant.toString().contains("***"))
    }
}
