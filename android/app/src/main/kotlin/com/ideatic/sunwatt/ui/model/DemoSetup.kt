package com.ideatic.sunwatt.ui.model

import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.ui.Screen
import java.time.Instant

/**
 * A fixed app state for demos, screenshots and emulator checks: no network, nothing read from or written to the
 * store. Built only by debug code (`app/src/debug`); release builds never get one.
 */
data class DemoSetup(
    val settings: SunWattSettings,
    val data: SunWattData?,
    val now: Instant,
    val widgetCount: Int? = 0,
    /** The dashboard shows placeholders and the progress marker. */
    val isLoading: Boolean = false,
    val showsOnboarding: Boolean = false,
    /** Screen pushed above the dashboard at launch. */
    val screen: Screen? = null,
    val preselection: DashboardPreselection = DashboardPreselection(),
)

/** Chart positions and day chosen before the user touches anything (demo "selection" and "tomorrow"). */
data class DashboardPreselection(
    val solar: Instant? = null,
    val price: Instant? = null,
    val startsOnTomorrow: Boolean = false,
)

/** The app state of a demo: ready at once, nothing loaded from anywhere. */
fun DemoSetup.toUiState() = AppUiState(
    isReady = true, settings = settings, data = if (isLoading) null else data, now = now, isLoading = isLoading,
    hasLoaded = !isLoading, widgetCount = widgetCount, showsOnboarding = showsOnboarding, isDemo = true,
)
