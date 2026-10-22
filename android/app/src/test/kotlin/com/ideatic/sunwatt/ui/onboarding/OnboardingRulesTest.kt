package com.ideatic.sunwatt.ui.onboarding

import com.ideatic.sunwatt.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingRulesTest {
    @Test fun `the solar page offers to skip until something is typed`() {
        assertEquals(R.string.app_skip_prices_only, primaryLabel(OnboardingPage.SOLAR, hasSolarInput = false))
        assertEquals(R.string.app_continue, primaryLabel(OnboardingPage.SOLAR, hasSolarInput = true))
    }

    @Test fun `the first and the last page have their own button text`() {
        assertEquals(R.string.app_get_started, primaryLabel(OnboardingPage.WELCOME, false))
        assertEquals(R.string.app_open_sunwatt, primaryLabel(OnboardingPage.DONE, false))
        assertEquals(R.string.app_continue, primaryLabel(OnboardingPage.COUNTRY, false))
        assertEquals(R.string.app_continue, primaryLabel(OnboardingPage.WIDGET, false))
    }

    @Test fun `pages go welcome, country, solar, widget, done and Back is hidden on the ends`() {
        assertEquals(listOf("WELCOME", "COUNTRY", "SOLAR", "WIDGET", "DONE"), OnboardingPage.entries.map { it.name })
        assertNull(OnboardingPage.DONE.next)
        assertNull(OnboardingPage.WELCOME.previous)
        assertFalse(OnboardingPage.WELCOME.hasBack)
        assertFalse(OnboardingPage.DONE.hasBack)
        assertTrue(OnboardingPage.SOLAR.hasBack)
    }
}
