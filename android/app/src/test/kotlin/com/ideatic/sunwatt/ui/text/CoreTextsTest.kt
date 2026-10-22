package com.ideatic.sunwatt.ui.text

import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.services.SunWattError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CoreTextsTest {
    private val everyError: List<SunWattError> = listOf(
        SunWattError.InvalidKioskURL,
        SunWattError.KioskLinkRejected,
        SunWattError.InvalidInverterAddress,
        SunWattError.InverterUnreachable,
        SunWattError.InvalidSolarCredentials,
        SunWattError.SolarCredentialsRejected,
        SunWattError.InvalidNorthboundAccount,
        SunWattError.NorthboundAccountRejected,
        SunWattError.RateLimited,
        SunWattError.NotConfigured,
        SunWattError.Http(503),
        SunWattError.BadResponse("detail"),
        SunWattError.Unavailable,
        SunWattError.Network("detail"),
    )

    @Test
    fun `the list above covers every SunWattError case`() {
        assertEquals(SunWattError::class.sealedSubclasses.size, everyError.map { it::class }.distinct().size)
    }

    @Test
    fun `every error maps to a resource id`() {
        for (error in everyError) {
            assertNotEquals(0, error.textRes().id, "no text for $error")
        }
    }

    @Test
    fun `formatted errors carry their argument`() {
        assertEquals(listOf<Any>(503), SunWattError.Http(503).textRes().args)
        assertEquals(listOf<Any>("x"), SunWattError.BadResponse("x").textRes().args)
        assertEquals(listOf<Any>("y"), SunWattError.Network("y").textRes().args)
    }

    @Test
    fun `every region and price level has a resource id`() {
        for (region in PriceRegion.entries) assertNotEquals(0, region.displayNameRes())
        for (level in PriceLevel.entries) assertNotEquals(0, level.labelRes())
    }
}
