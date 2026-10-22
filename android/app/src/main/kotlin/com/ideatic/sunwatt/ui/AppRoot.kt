package com.ideatic.sunwatt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ideatic.sunwatt.core.models.SunWattLink
import com.ideatic.sunwatt.ui.dashboard.DashboardScreen
import com.ideatic.sunwatt.ui.guide.WidgetGuideScreen
import com.ideatic.sunwatt.ui.help.KioskHelpScreen
import com.ideatic.sunwatt.ui.onboarding.OnboardingScreen
import com.ideatic.sunwatt.ui.settings.SettingsScreen
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.SunWattTheme
import kotlinx.coroutines.flow.Flow

/** The screens of the app. Onboarding is the root until it is completed; the others are pushed on a back stack. */
sealed interface Screen {
    data object Dashboard : Screen
    data object Settings : Screen
    data object Onboarding : Screen
    data object WidgetGuide : Screen
    data object KioskHelp : Screen

    companion object {
        fun fromName(name: String): Screen? = listOf(Dashboard, Settings, Onboarding, WidgetGuide, KioskHelp).firstOrNull { it.toString() == name }
    }
}

private val StackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map { it.toString() } },
    restore = { names -> mutableStateListOf<Screen>().apply { names.mapNotNullTo(this) { Screen.fromName(it) } } },
)

/**
 * Onboarding or dashboard (from the saved settings) plus the screens pushed above it. Nothing is drawn but the page
 * background until the settings are loaded. Provides [LocalAppViewModel] to every screen.
 *
 * [refreshRequests]: one item per `sunwatt://refresh` link; each is an explicit refresh (DECISIONS D25).
 */
@Composable
fun AppRoot(
    vm: AppViewModel,
    refreshRequests: Flow<Unit>,
    modifier: Modifier = Modifier,
) {
    SunWattTheme {
        CompositionLocalProvider(LocalAppViewModel provides vm) {
            val owner = LocalLifecycleOwner.current
            LaunchedEffect(vm) { vm.start() }
            // Each time the app comes to the front: widgets count, and data older than 5 minutes.
            LaunchedEffect(vm, owner) { owner.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.onResume() } }
            LaunchedEffect(vm, refreshRequests) { refreshRequests.collect { vm.open(SunWattLink.REFRESH) } }

            val state by vm.state.collectAsStateWithLifecycle()
            if (!state.isReady) AppBackground(modifier) {} else Screens(vm, state.showsOnboarding, modifier)
        }
    }
}

@Composable
private fun Screens(vm: AppViewModel, showsOnboarding: Boolean, modifier: Modifier) {
    val pushed = rememberSaveable(saver = StackSaver) {
        mutableStateListOf<Screen>().apply { vm.demo?.screen?.takeIf { it != Screen.Dashboard && it != Screen.Onboarding }?.let(::add) }
    }
    val root = if (showsOnboarding) Screen.Onboarding else Screen.Dashboard
    val top = pushed.lastOrNull() ?: root
    BackHandler(enabled = pushed.isNotEmpty()) { pushed.removeAt(pushed.lastIndex) }

    when (top) {
        Screen.Dashboard -> DashboardScreen(
            onOpenSettings = { pushed.add(Screen.Settings) },
            onOpenWidgetGuide = { pushed.add(Screen.WidgetGuide) },
            onOpenKioskHelp = { pushed.add(Screen.KioskHelp) },
            modifier = modifier,
        )
        Screen.Settings -> SettingsScreen(
            onBack = { pushed.removeAt(pushed.lastIndex) },
            onOpenWidgetGuide = { pushed.add(Screen.WidgetGuide) },
            modifier = modifier,
        )
        Screen.Onboarding -> OnboardingScreen(
            onFinished = {
                pushed.clear()
                vm.completeOnboarding()
            },
            modifier = modifier,
        )
        Screen.KioskHelp -> KioskHelpScreen(onBack = { pushed.removeAt(pushed.lastIndex) }, modifier = modifier)
        Screen.WidgetGuide -> WidgetGuideScreen(onBack = { pushed.removeAt(pushed.lastIndex) }, modifier = modifier)
    }
}
