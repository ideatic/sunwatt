package com.ideatic.sunwatt.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ideatic.sunwatt.ui.LocalAppViewModel
import com.ideatic.sunwatt.ui.theme.AppBackground
import com.ideatic.sunwatt.ui.theme.Spacing
import com.ideatic.sunwatt.ui.theme.appPalette
import kotlinx.coroutines.launch

/**
 * The dashboard: solar, price, widget status and footer, with pull to refresh (an explicit refresh). While it is on
 * screen the clock moves `now` forward every quarter hour and an inverter on the home network is read every few seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenWidgetGuide: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenKioskHelp: () -> Unit = {},
) {
    val vm = LocalAppViewModel.current
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(vm, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { vm.runClock() }
            launch { vm.runLive() }
        }
    }
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf(false) }
    val actions = remember(vm, onOpenSettings, onOpenWidgetGuide, onOpenKioskHelp) {
        DashboardActions(
            onOpenSettings = onOpenSettings,
            onOpenWidgetGuide = onOpenWidgetGuide,
            onRefresh = { scope.launch { vm.refresh() } },
            onDismissKioskNotice = vm::dismissKioskExpiryNotice,
            onOpenKioskHelp = onOpenKioskHelp,
        )
    }
    AppBackground(modifier) {
        CompositionLocalProvider(LocalContentColor provides appPalette.textPrimary) {
            PullToRefreshBox(
                isRefreshing = pulling,
                onRefresh = { scope.launch { pulling = true; try { vm.refresh() } finally { pulling = false } } },
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            ) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    DashboardContent(
                        state, actions,
                        Modifier.widthIn(max = 1100.dp).fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m),
                        vm.demo?.preselection ?: com.ideatic.sunwatt.ui.model.DashboardPreselection(),
                    )
                }
            }
        }
    }
}
