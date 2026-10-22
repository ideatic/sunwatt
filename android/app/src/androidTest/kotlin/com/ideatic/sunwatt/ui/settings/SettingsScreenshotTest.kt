package com.ideatic.sunwatt.ui.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ideatic.sunwatt.SunWattApplication
import com.ideatic.sunwatt.core.models.SolarProvider
import com.ideatic.sunwatt.core.models.SolarSnapshot
import com.ideatic.sunwatt.debug.DemoScenarios
import com.ideatic.sunwatt.ui.AppViewModel
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.guide.WidgetGuideContent
import com.ideatic.sunwatt.ui.help.KioskHelpContent
import com.ideatic.sunwatt.ui.model.DemoSetup
import com.ideatic.sunwatt.ui.onboarding.OnboardingPage
import com.ideatic.sunwatt.ui.onboarding.OnboardingScreen
import com.ideatic.sunwatt.ui.setup.SolarTestState
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.SunWattTheme
import com.ideatic.sunwatt.ui.theme.appPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.Locale

/**
 * Renders onboarding (every page), settings (per provider, several plants, invalid input, a test result), the kiosk help
 * and the widget guide, light and dark, in English, German and Spanish, to PNGs in `getExternalFilesDir("screens")`:
 * `adb -s emulator-5554 pull /sdcard/Android/data/com.ideatic.sunwatt/files/screens`. Files that exist are skipped:
 * delete the folder to render again. Run one language at a time:
 * `am instrument -w -e class com.ideatic.sunwatt.ui.settings.SettingsScreenshotTest#renderLightEnglish ...`.
 */
class SettingsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private class Case(
        val name: String,
        val setup: DemoSetup,
        /** Density factor: tall pages are drawn smaller so all of them is on screen (the capture copies window pixels). */
        val scale: Float,
        val fullScreen: Boolean = false,
        val content: @Composable () -> Unit,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = context.getExternalFilesDir("screens")!!

    private val kiosk = DemoScenarios.DEMO_KIOSK_URL
    private val day = DemoScenarios.setup("day")
    private val noSolar = DemoScenarios.setup("noSolar")
    private val key123 = "ABCD1234EFGH5678"
    private val connected = SolarTestState.Success(
        SolarSnapshot(stationName = "Demo plant", currentPowerKW = 5.3, dailyEnergyKWh = 16.2, fetchedAt = Instant.parse("2026-10-17T11:40:00Z"), dataAsOf = Instant.parse("2026-10-17T11:30:00Z")),
    )

    private fun withSettings(base: DemoSetup, change: (com.ideatic.sunwatt.core.models.SunWattSettings) -> com.ideatic.sunwatt.core.models.SunWattSettings) =
        base.copy(settings = change(base.settings))

    private val settingsScale = 0.36f

    private fun cases(): List<Case> = listOf(
        Case("onb-1-welcome", noSolar, 1f, true) { OnboardingScreen({}, startPage = OnboardingPage.WELCOME) },
        Case("onb-2-country", noSolar, 1f, true) { OnboardingScreen({}, startPage = OnboardingPage.COUNTRY) },
        Case("onb-3-solar", noSolar, 1f, true) { OnboardingScreen({}, startPage = OnboardingPage.SOLAR) },
        Case("onb-3-solar-solaredge", withSettings(noSolar) { it.copy(solarProvider = SolarProvider.SOLAR_EDGE) }, 1f, true) {
            OnboardingScreen({}, startPage = OnboardingPage.SOLAR)
        },
        Case("onb-4-widget", noSolar, 1f, true) { OnboardingScreen({}, startPage = OnboardingPage.WIDGET) },
        Case("onb-5-done", day, 1f, true) { OnboardingScreen({}, startPage = OnboardingPage.DONE) },
        Case("settings-fusionsolar", day, settingsScale) { SettingsContent({}, {}) },
        Case("settings-invalid", withSettings(day) { it.copy(kioskURL = "https://example.com/not-a-kiosk", huaweiLocalHost = "not an address") }, settingsScale) { SettingsContent({}, {}) },
        Case("settings-test", day, settingsScale) { SettingsContent({}, {}, initialTest = connected) },
        Case("settings-solaredge", withSettings(day) { it.copy(solarProvider = SolarProvider.SOLAR_EDGE, solarSiteID = "1234567", solarAPIKey = key123) }, settingsScale) {
            SettingsContent({}, {}, initialTest = connected)
        },
        Case("settings-pvoutput-empty", withSettings(day) { it.copy(solarProvider = SolarProvider.PV_OUTPUT, solarSiteID = "", solarAPIKey = "") }, settingsScale) { SettingsContent({}, {}) },
        Case("settings-fusionsolar-api", withSettings(day) { it.copy(solarProvider = SolarProvider.FUSION_SOLAR_API, solarSiteID = "api_user", solarAPIKey = "secret-code") }, settingsScale) { SettingsContent({}, {}) },
        Case("settings-fronius", withSettings(day) { it.copy(solarProvider = SolarProvider.FRONIUS, froniusHost = "192.168.1.30") }, settingsScale) { SettingsContent({}, {}) },
        Case("settings-plants", DemoScenarios.setup("plants", widgets = 1), settingsScale) { SettingsContent({}, {}) },
        Case("settings-plants-beach", DemoScenarios.setup("plants"), settingsScale) { SettingsContent({}, {}, initialPlant = "demo-beach") },
        Case("kiosk-help", day, 0.5f) { KioskHelpContent() },
        Case("widget-guide", DemoScenarios.setup("widgetAdded"), 0.5f) { WidgetGuideContent() },
    )

    @Test fun renderLightEnglish() = render(dark = false, language = "en")

    @Test fun renderDarkEnglish() = render(dark = true, language = "en")

    @Test fun renderLightGerman() = render(dark = false, language = "de")

    @Test fun renderDarkGerman() = render(dark = true, language = "de")

    @Test fun renderLightSpanish() = render(dark = false, language = "es")

    @Test fun renderDarkSpanish() = render(dark = true, language = "es")

    private fun render(dark: Boolean, language: String) {
        val cases = cases()
        var index by mutableIntStateOf(0)
        val graph = (context.applicationContext as SunWattApplication).graph
        rule.setContent {
            key(index) {
                val case = cases[index]
                val locale = Locale.forLanguageTag(language)
                val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
                val localized = LocalContext.current.createConfigurationContext(configuration)
                val system = LocalDensity.current
                val vm = remember { AppViewModel.factory(graph, context, case.setup).create(AppViewModel::class.java) }
                CompositionLocalProvider(
                    LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(system.density * case.scale, system.fontScale),
                    LocalAppViewModel provides vm,
                ) {
                    SunWattTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalContentColor provides appPalette.textPrimary) {
                            if (case.fullScreen) {
                                Box(Modifier.testTag("frame").size(412.dp, 860.dp)) { case.content() }
                            } else {
                                Box(Modifier.testTag("frame").width(412.dp).background(appPalette.backgroundGradient).padding(16.dp)) { case.content() }
                            }
                        }
                    }
                }
            }
        }
        for ((i, case) in cases.withIndex()) {
            val file = File(dir, "${case.name}-${if (dark) "dark" else "light"}-$language.png")
            if (file.length() > 0) continue
            index = i
            rule.waitForIdle()
            val image = rule.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
            FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            assertTrue(file.length() > 0)
        }
    }
}
