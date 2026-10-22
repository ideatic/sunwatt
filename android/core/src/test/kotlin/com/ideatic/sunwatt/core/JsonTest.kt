package com.ideatic.sunwatt.core

import com.ideatic.sunwatt.core.models.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonTest {
    private val json = SunWattJson

    private fun settings(text: String) = json.decodeFromString<SunWattSettings>(text)

    @Test
    fun settingsRoundTrip() {
        val original = SunWattSettings(
            kioskURL = "https://x.test/?kk=ABC", showTomorrowPrices = false, priceRegion = PriceRegion.ITALY_NORTH,
            onboardingCompleted = true, solarProvider = SolarProvider.FUSION_SOLAR_API, solarSiteID = "user",
            solarAPIKey = "code", kioskLinkSavedAt = Instant.parse("2026-06-01T10:30:00Z"),
            kioskExpiryNoticeDismissed = true, huaweiLocalHost = "192.168.1.50", huaweiLocalUnitID = 0,
            sunSpecHost = "192.168.1.40:1502", sunSpecUnitID = 126, froniusHost = "192.168.1.30",
            northboundHost = "eu1.fusionsolar.huawei.com", primaryPlantName = "Home",
            additionalPlants = listOf(
                SolarPlant(id = "p2", name = "Roof", provider = SolarProvider.PV_OUTPUT, siteID = "42", apiKey = "ABCDEFGH12345678"),
            ),
        )
        val text = json.encodeToString(original)
        assertTrue(text.contains("\"priceRegion\":\"italyNorth\""), text)
        assertTrue(text.contains("\"solarProvider\":\"fusionSolarAPI\""), text)
        assertTrue(text.contains("\"kioskLinkSavedAt\":\"2026-06-01T10:30:00Z\""), text)
        assertEquals(original, settings(text))
    }

    @Test
    fun defaultsAreWritten() {
        val text = json.encodeToString(SunWattSettings())
        assertTrue(text.contains("\"showTomorrowPrices\":true"), text)
        assertEquals(SunWattSettings(), settings(text))
    }

    @Test
    fun unknownRegionFallsBackToSpainKeepingTheRest() {
        val s = settings("""{"kioskURL":"x","priceRegion":"atlantis","showTomorrowPrices":false,"solarSiteID":"7"}""")
        assertEquals(PriceRegion.SPAIN, s.priceRegion)
        assertEquals("x", s.kioskURL)
        assertEquals(false, s.showTomorrowPrices)
        assertEquals("7", s.solarSiteID)
    }

    @Test
    fun unknownProviderFallsBackToFusionSolarKeepingTheRest() {
        val s = settings("""{"solarProvider":"sma","priceRegion":"france","primaryPlantName":"Home"}""")
        assertEquals(SolarProvider.FUSION_SOLAR, s.solarProvider)
        assertEquals(PriceRegion.FRANCE, s.priceRegion)
        assertEquals("Home", s.primaryPlantName)
    }

    @Test
    fun oldSettingsWithoutNewerKeysDecode() {
        val old = settings("""{"kioskURL":"x"}""")
        assertEquals("x", old.kioskURL)
        assertTrue(old.additionalPlants.isEmpty() && !old.hasSeveralPlants && old.primaryPlantName.isEmpty())
        assertEquals(SunWattSettings(kioskURL = "x"), old)
        assertEquals(SunWattSettings(), settings("{}"))
    }

    @Test
    fun unknownKeysAndNullsAreTolerated() {
        val s = settings("""{"kioskURL":null,"priceRegion":null,"future":{"a":1},"huaweiLocalUnitID":null}""")
        assertEquals(SunWattSettings(), s)
    }

    @Test
    fun brokenPlantListIsDroppedAsAWhole() {
        val s = settings(
            """{"kioskURL":"x","additionalPlants":[{"id":"a","name":"Ok"},{"id":"b","huaweiLocalUnitID":"not a number"}]}""",
        )
        assertTrue(s.additionalPlants.isEmpty())
        assertEquals("x", s.kioskURL)
        assertEquals(SunWattSettings(kioskURL = "x"), settings("""{"kioskURL":"x","additionalPlants":"nope"}"""))
    }

    @Test
    fun plantWithUnknownProviderReadsAsFusionSolar() {
        val s = settings("""{"additionalPlants":[{"id":"a","name":"Ok","provider":"future"}]}""")
        assertEquals(1, s.additionalPlants.size)
        assertEquals(SolarProvider.FUSION_SOLAR, s.additionalPlants[0].provider)
        assertEquals("Ok", s.additionalPlants[0].name)
    }

    @Test
    fun plantWithoutIdGetsOne() {
        val s = settings("""{"additionalPlants":[{"name":"No id"}]}""")
        assertTrue(s.additionalPlants[0].id.isNotEmpty())
    }

    @Test
    fun strictEnumsRejectUnknownValues() {
        assertFailsWith<SerializationException> { json.decodeFromString<PriceSeries>(
            """{"points":[],"granularity":"hourly","fetchedAt":"2026-06-01T10:00:00Z","region":"atlantis"}""",
        ) }
        assertFailsWith<SerializationException> { json.decodeFromString<PriceGranularity>("\"weekly\"") }
        assertEquals(WidgetContent.PRICE_ONLY, json.decodeFromString<WidgetContent>("\"priceOnly\""))
        assertEquals(PriceWindow.AROUND, json.decodeFromString<PriceWindow>("\"around\""))
        assertEquals("\"low\"", json.encodeToString(PriceLevel.LOW))
    }

    @Test
    fun priceSeriesRoundTripAndMissingRegionIsSpain() {
        val start = Instant.parse("2026-06-01T10:00:00Z")
        val series = PriceSeries(
            points = listOf(PricePoint(start, 900, 55.5), PricePoint(start.plusSeconds(900), 900, -3.0)),
            granularity = PriceGranularity.QUARTER_HOURLY, fetchedAt = start,
            sourceUpdatedAt = start.minusSeconds(60), region = PriceRegion.GERMANY,
        )
        assertEquals(series, json.decodeFromString<PriceSeries>(json.encodeToString(series)))
        val old = json.decodeFromString<PriceSeries>(
            """{"points":[{"start":"2026-06-01T10:00:00Z","durationSeconds":3600,"eurPerMWh":100}],""" +
                """"granularity":"hourly","fetchedAt":"2026-06-01T10:00:00Z"}""",
        )
        assertEquals(PriceRegion.SPAIN, old.region)
        assertEquals(null, old.sourceUpdatedAt)
    }

    @Test
    fun snapshotRoundTripAndPartialJson() {
        val t = Instant.parse("2026-06-01T10:00:00Z")
        val snapshot = SolarSnapshot(
            stationName = "Demo", currentPowerKW = 2.5, dailyEnergyKWh = 10.0, curve = listOf(CurvePoint(t, 1.0), CurvePoint(t.plusSeconds(300), null)),
            fetchedAt = t, dataAsOf = t, endpointID = "abc", isLive = true, cloudFetchedAt = t.minusSeconds(30),
            home = HomeEnergy(consumptionKW = 1.0, dailyConsumptionKWh = 8.0, dailyImportKWh = 2.0, curve = listOf(CurvePoint(t, 0.5))),
        )
        assertEquals(snapshot, json.decodeFromString<SolarSnapshot>(json.encodeToString(snapshot)))
        val minimal = json.decodeFromString<SolarSnapshot>("""{"fetchedAt":"2026-06-01T12:00:00+02:00"}""")
        assertEquals(t, minimal.fetchedAt)
        assertTrue(minimal.curve.isEmpty() && minimal.home == null && minimal.isLive == null)
    }
}
