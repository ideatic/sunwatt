package com.ideatic.sunwatt.ui.dashboard

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.ideatic.sunwatt.debug.DemoScenarios
import com.ideatic.sunwatt.ui.model.toUiState
import com.ideatic.sunwatt.ui.theme.SunWattTheme
import com.ideatic.sunwatt.ui.theme.appPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Renders the dashboard of every demo scenario, light and dark, in English, German and Spanish, to PNGs in
 * `getExternalFilesDir("screens")`: `adb pull /sdcard/Android/data/com.ideatic.sunwatt/files/screens`. A phone width
 * (412 dp) for every scenario and a tablet width (900 dp) for a few.
 */
class DashboardScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private data class Case(val scenario: String, val dark: Boolean, val language: String, val widthDp: Int)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = context.getExternalFilesDir("screens")!!

    private val actions = DashboardActions({}, {}, {}, {})

    @Test fun renderLightEnglish() = render(dark = false, language = "en")

    @Test fun renderDarkEnglish() = render(dark = true, language = "en")

    @Test fun renderLightGerman() = render(dark = false, language = "de")

    @Test fun renderDarkGerman() = render(dark = true, language = "de")

    @Test fun renderLightSpanish() = render(dark = false, language = "es")

    @Test fun renderDarkSpanish() = render(dark = true, language = "es")

    private fun render(dark: Boolean, language: String) {
        val cases = buildList {
            for (scenario in DemoScenarios.scenarios) add(Case(scenario, dark, language, 412))
            if (language == "en") for (scenario in listOf("day", "plants", "home")) add(Case(scenario, dark, language, 900))
        }
        var current by mutableStateOf(cases.first())
        rule.setContent {
            key(current) {
                val locale = Locale.forLanguageTag(current.language)
                val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
                val localized = LocalContext.current.createConfigurationContext(configuration)
                // The page is taller than the window and the capture copies window pixels: draw it smaller (lower
                // density, same layout in dp) so all of it is on screen.
                val system = LocalDensity.current
                val scale = if (current.widthDp == 412) 0.55f else 0.45f
                CompositionLocalProvider(
                    LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalDensity provides Density(system.density * scale, system.fontScale),
                ) {
                    SunWattTheme(darkTheme = current.dark) {
                        val setup = DemoScenarios.setup(current.scenario)
                        CompositionLocalProvider(LocalContentColor provides appPalette.textPrimary) {
                            Box(
                                Modifier.testTag("frame").width(current.widthDp.dp).background(appPalette.backgroundGradient)
                                    .padding(16.dp),
                            ) {
                                DashboardContent(setup.toUiState(), actions, preselection = setup.preselection)
                            }
                        }
                    }
                }
            }
        }
        for (case in cases) {
            val width = if (case.widthDp == 412) "" else "-w${case.widthDp}"
            val file = File(dir, "${case.scenario}-${if (case.dark) "dark" else "light"}-${case.language}$width.png")
            // Already rendered (a run on a busy emulator can be cut short): delete the folder to render again.
            if (file.length() > 0) continue
            current = case
            rule.waitForIdle()
            val image = rule.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
            FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            assertTrue(file.length() > 0)
        }
    }
}
