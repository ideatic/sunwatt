package com.ideatic.sunwatt.core.data

import com.ideatic.sunwatt.core.models.SolarPlant
import com.ideatic.sunwatt.core.models.SunWattJson
import com.ideatic.sunwatt.core.services.SunWattError
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Several plants in a [SunWattData]: ported from the data parts of the Swift MultiPlantTests and WatchPagesTests. */
class SunWattDataTest {
    private val now = madrid(2026, 6, 1, 12, 30)

    private fun snapshot(station: String, kW: Double, kWh: Double) =
        sampleSolar(fetchedAt = now).copy(stationName = station, currentPowerKW = kW, dailyEnergyKWh = kWh)

    private fun twoPlants(
        roofStatus: SourceStatus = SourceStatus.Fresh,
        roofSolar: com.ideatic.sunwatt.core.models.SolarSnapshot? = snapshot("Roof site", 1.5, 4.0),
    ): SunWattData = SunWattData(null, samplePrices(), SourceStatus.Fresh, SourceStatus.Fresh).withPlants(
        listOf(
            PlantSolarData(SolarPlant.PRIMARY_ID, "Home", snapshot("Calle", 3.0, 10.0), SourceStatus.Fresh),
            PlantSolarData(ROOF_ID, "Roof", roofSolar, roofStatus),
        ),
    )

    @Test
    fun withPlantsKeepsAPlantNameOnlyWithSeveral() {
        val data = twoPlants()
        assertEquals(SolarPlant.PRIMARY_ID, data.plantID)
        assertEquals("Home", data.plantName)
        assertEquals(3.0, data.solar?.currentPowerKW)
        assertEquals(listOf("Roof"), data.otherPlants.map { it.name })
        assertTrue(data.hasSeveralPlants)
        val one = SunWattData(null, null, SourceStatus.NotConfigured, SourceStatus.Fresh)
            .withPlants(listOf(PlantSolarData(SolarPlant.PRIMARY_ID, "Home", null, SourceStatus.NotConfigured)))
        assertNull(one.plantName)
        assertFalse(one.hasSeveralPlants)
        // No plants: unchanged.
        assertEquals(one, one.withPlants(emptyList()))
    }

    @Test
    fun firstPlantFirstWithSeveral() {
        val data = SunWattData(null, null, SourceStatus.NotConfigured, SourceStatus.Fresh).withPlants(
            listOf(
                PlantSolarData(SolarPlant.PRIMARY_ID, "Home", null, SourceStatus.NotConfigured),
                PlantSolarData("second", "Barn", null, SourceStatus.Fresh),
            ),
        )
        assertEquals(listOf(SolarPlant.PRIMARY_ID, "second"), data.allPlants.map { it.id })
    }

    @Test
    fun onePlantReadsAsBefore() {
        val data = SunWattData(snapshot("Calle", 3.0, 10.0), null, SourceStatus.Fresh, SourceStatus.Fresh)
        assertNull(data.plantName)
        assertTrue(data.otherPlants.isEmpty())
        assertFalse(data.hasSeveralPlants)
        assertEquals(data, data.showingPlant(1))
        assertEquals(data, SunWattEntry(now, data).combinedPlants.data)
    }

    @Test
    fun showingAnotherPlant() {
        val data = twoPlants()
        val second = data.showingPlant(1)
        assertEquals(ROOF_ID, second.plantID)
        assertEquals("Roof", second.plantName)
        assertEquals(1.5, second.solar?.currentPowerKW)
        assertEquals(listOf(SolarPlant.PRIMARY_ID), second.otherPlants.map { it.id })
        assertEquals(data.showingPlant(0), data.showingPlant(2))
        assertEquals(data.showingPlant(1), data.showingPlant(-1))
        assertEquals(second, data.showingPlant(ROOF_ID))
        assertSame(data, data.showingPlant("missing"))
        // Prices and totals do not change with the plant shown.
        assertEquals(data.prices, second.prices)
        assertEquals(SunWattEntry(now, data).combinedSolarForDisplay, SunWattEntry(now, second).combinedSolarForDisplay)
    }

    @Test
    fun combinedSolarAddsUpThePlants() {
        val total = SunWattEntry(now, twoPlants()).combinedSolarForDisplay
        assertEquals(4.5, total?.currentPowerKW)
        assertEquals(14.0, total?.dailyEnergyKWh)
        assertTrue(total?.curve?.isEmpty() == true)
        assertNull(total?.monthEnergyKWh)
        // No plant with data: null.
        val none = twoPlants(roofSolar = null).withPlants(
            listOf(PlantSolarData("a", "A", null, SourceStatus.NotConfigured), PlantSolarData("b", "B", null, SourceStatus.NotConfigured)),
        )
        assertNull(SunWattEntry(now, none).combinedSolarForDisplay)
    }

    @Test
    fun complicationsShowTheTotal() {
        val combined = SunWattEntry(now, twoPlants()).combinedPlants
        assertFalse(combined.data.hasSeveralPlants)
        assertNull(combined.data.plantName)
        assertEquals(4.5, combined.data.solar?.currentPowerKW)
        assertEquals(SourceStatus.Fresh, combined.data.solarStatus)
        // Stale when any plant is, since the oldest.
        val stale = SunWattEntry(now, twoPlants(roofStatus = SourceStatus.Stale(now.minusSeconds(600)))).combinedPlants
        assertEquals(SourceStatus.Stale(now.minusSeconds(600)), stale.data.solarStatus)
    }

    @Test
    fun combinedWithoutDataIsNotConfiguredOnlyWhenNoPlantIs() {
        fun data(a: SourceStatus, b: SourceStatus) = twoPlants().withPlants(
            listOf(PlantSolarData("a", "A", null, a), PlantSolarData("b", "B", null, b)),
        )
        assertEquals(
            SourceStatus.NotConfigured,
            SunWattEntry(now, data(SourceStatus.NotConfigured, SourceStatus.NotConfigured)).combinedPlants.data.solarStatus,
        )
        assertEquals(
            SourceStatus.Error.of(SunWattError.Unavailable),
            SunWattEntry(now, data(SourceStatus.NotConfigured, SourceStatus.Error("x"))).combinedPlants.data.solarStatus,
        )
    }

    @Test
    fun plantsForDisplayHideYesterdayPerPlant() {
        val data = twoPlants()
        val nextDay = SunWattEntry(madrid(2026, 6, 2, 0, 10), data)
        assertTrue(nextDay.plantsForDisplay.all { it.solar?.curve?.isEmpty() == true && it.solar?.currentPowerKW == null })
        assertEquals(listOf(SolarPlant.PRIMARY_ID, ROOF_ID), nextDay.plantsForDisplay.map { it.id })
    }

    @Test
    fun lastUpdatedIsTheNewestFetch() {
        val data = SunWattData(
            sampleSolar(madrid(2026, 6, 1, 12)), samplePrices(fetchedAt = madrid(2026, 6, 1, 13)),
            SourceStatus.Fresh, SourceStatus.Fresh,
        )
        assertEquals(madrid(2026, 6, 1, 13), data.lastUpdated)
        assertNull(SunWattData(null, null, SourceStatus.NotConfigured, SourceStatus.NotConfigured).lastUpdated)
    }

    @Test
    fun dataRoundTripsThroughJson() {
        val data = twoPlants(roofStatus = SourceStatus.Stale(Instant.parse("2026-06-01T10:00:00Z"))).copy(solarLinkExpired = true)
        val text = SunWattJson.encodeToString(data)
        assertEquals(data, SunWattJson.decodeFromString<SunWattData>(text))
    }

    private companion object {
        const val ROOF_ID = "B8E3C1F2-0000-4000-8000-000000000001"
    }
}
