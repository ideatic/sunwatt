package com.ideatic.sunwatt.ui.setup

import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SunWattSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupRulesTest {
    private val kiosk = "https://eu5.fusionsolar.huawei.com/pvmswebsite/nologin/assets/build/cloud.html#/kiosk?kk=DEMO0000"

    @Test fun `kiosk input is empty, valid with its host, or invalid`() {
        assertEquals(KioskInputState.Empty, kioskInputState("  \n"))
        assertEquals(KioskInputState.Valid("eu5.fusionsolar.huawei.com"), kioskInputState(" $kiosk "))
        assertEquals(KioskInputState.Invalid, kioskInputState("http://example.com/?kk=1"))
        assertEquals(KioskInputState.Invalid, kioskInputState("https://example.com/kiosk"))
    }

    @Test fun `API input needs a numeric ID and a letters and digits key`() {
        assertEquals(CredentialInput.EMPTY, apiInputState(" ", ""))
        assertEquals(CredentialInput.VALID, apiInputState("123456", "ABCD1234efgh"))
        assertEquals(CredentialInput.INVALID, apiInputState("12ab", "ABCD1234efgh"))
        assertEquals(CredentialInput.INVALID, apiInputState("123456", "short"))
        assertEquals(CredentialInput.INVALID, apiInputState("", "ABCD1234efgh"))
    }

    @Test fun `account input only accepts a Huawei server`() {
        assertEquals(CredentialInput.EMPTY, northboundInputState("", "", ""))
        assertEquals(CredentialInput.VALID, northboundInputState("", "api_user", "secret"))
        assertEquals(CredentialInput.VALID, northboundInputState("https://eu5.fusionsolar.huawei.com/", "api_user", "secret"))
        assertEquals(CredentialInput.INVALID, northboundInputState("evil.example.com", "api_user", "secret"))
        assertEquals(CredentialInput.INVALID, northboundInputState("", "api_user", ""))
    }

    private val withCredentials = SunWattSettings(solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "123456", solarAPIKey = "ABCD1234efgh")

    @Test fun `choosing another credential provider clears the ID and the key`() {
        val pv = withCredentials.choosingProvider(SolarProvider.PV_OUTPUT)
        assertEquals(SolarProvider.PV_OUTPUT, pv.solarProvider)
        assertEquals("", pv.solarSiteID)
        assertEquals("", pv.solarAPIKey)
        // Also through the FusionSolar API account, which keeps its user name and code in the same fields.
        assertEquals("", withCredentials.choosingProvider(SolarProvider.FUSION_SOLAR_API).solarSiteID)
    }

    @Test fun `choosing a provider without credentials keeps the fields, choosing the same one changes nothing`() {
        assertEquals("123456", withCredentials.choosingProvider(SolarProvider.FUSION_SOLAR).solarSiteID)
        assertEquals("123456", withCredentials.choosingProvider(SolarProvider.FRONIUS).solarSiteID)
        assertEquals(withCredentials, withCredentials.choosingProvider(SolarProvider.SOLAR_EDGE))
    }

    @Test fun `every provider has its sections, the inverter only where it is wanted`() {
        assertEquals(listOf(SetupSection.KIOSK_EDITOR, SetupSection.KIOSK_HELP_BUTTON, SetupSection.INVERTER), setupSections(SolarProvider.FUSION_SOLAR, true))
        assertEquals(listOf(SetupSection.KIOSK_EDITOR, SetupSection.KIOSK_HELP_BUTTON), setupSections(SolarProvider.FUSION_SOLAR, false))
        assertEquals(listOf(SetupSection.NORTHBOUND_EDITOR, SetupSection.NORTHBOUND_HELP, SetupSection.INVERTER), setupSections(SolarProvider.FUSION_SOLAR_API, true))
        assertEquals(listOf(SetupSection.API_EDITOR, SetupSection.PROVIDER_HELP, SetupSection.INVERTER), setupSections(SolarProvider.SOLAR_EDGE, true))
        assertEquals(listOf(SetupSection.API_EDITOR, SetupSection.PROVIDER_HELP), setupSections(SolarProvider.PV_OUTPUT, false))
        // Fronius: the inverter is the whole setup, also in onboarding.
        assertEquals(listOf(SetupSection.INVERTER, SetupSection.PROVIDER_HELP), setupSections(SolarProvider.FRONIUS, false))
        assertEquals(listOf(SetupSection.INVERTER, SetupSection.PROVIDER_HELP), setupSections(SolarProvider.FRONIUS, true))
    }

    @Test fun `the picker has four buttons and FusionSolar groups its API account`() {
        assertEquals(listOf(SolarProvider.FUSION_SOLAR, SolarProvider.SOLAR_EDGE, SolarProvider.PV_OUTPUT, SolarProvider.FRONIUS), SolarProvider.pickerCases)
        assertEquals(SolarProvider.FUSION_SOLAR, SolarProvider.FUSION_SOLAR_API.pickerGroup)
    }

    @Test fun `the region list has the eight regions, Spain first`() {
        assertEquals(8, priceRegionChoices.size)
        assertEquals(PriceRegion.SPAIN, priceRegionChoices.first())
        assertTrue(priceRegionChoices.all { it.flag.isNotEmpty() })
    }
}
