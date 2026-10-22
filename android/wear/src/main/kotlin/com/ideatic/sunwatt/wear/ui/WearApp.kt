package com.ideatic.sunwatt.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.pager.VerticalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.VerticalPagerScaffold
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.wear.WearGraph
import com.ideatic.sunwatt.wear.pages.WatchPage
import com.ideatic.sunwatt.wear.pages.WatchPages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** The watch app: a solar page per plant, then the price page, paged vertically with the crown or a swipe (DECISIONS D29). */
@Composable
fun WearApp(graph: WearGraph) {
    val data by graph.data.collectAsStateWithLifecycle()
    // Refresh when the app comes on screen, then every refresh interval while it stays there.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(graph) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                graph.refresh()
                delay(com.ideatic.sunwatt.core.models.Constants.refreshInterval)
            }
        }
    }
    MaterialTheme {
        AppScaffold(timeText = { TimeText() }) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                data?.let { WatchRoot(it) { now -> graph.entry(it, now) } }
            }
        }
    }
}

/**
 * The pager sits at the root and is never rebuilt by the clock: each page has its own minute clock. With the whole pager
 * inside a ticking state the crown and swipes stopped working on a real watch (DECISIONS D29).
 */
@Composable
fun WatchRoot(data: SunWattData, entryAt: (Instant) -> SunWattEntry) {
    val pages = WatchPages.pages(data)
    val first = WatchPages.firstIndex(data)
    val pagerState = rememberPagerState(initialPage = first) { pages.size }
    // Until the user pages, the first page follows the data (prices only, then solar once it is set up).
    var userPaged by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }.filter { it }.first()
        userPaged = true
    }
    LaunchedEffect(first, pages.size) {
        if (!userPaged && pagerState.currentPage != first) pagerState.scrollToPage(first)
    }
    VerticalPagerScaffold(pagerState) {
        VerticalPager(state = pagerState, key = { pages.getOrNull(it)?.key ?: it }) { index ->
            when (val page = pages.getOrNull(index)) {
                is WatchPage.Solar -> Timed(entryAt) { entry -> WatchSolarPage(SunWattEntry(entry.date, entry.data.showingPlant(page.plantID))) }
                WatchPage.Price -> Timed(entryAt) { entry -> WatchPricePage(entry) }
                null -> Unit
            }
        }
    }
}

/** [content] with the entry for now, redrawn at every minute. */
@Composable
private fun Timed(entryAt: (Instant) -> SunWattEntry, content: @Composable (SunWattEntry) -> Unit) {
    val now by rememberMinuteClock()
    content(entryAt(now))
}

@Composable
private fun rememberMinuteClock(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        val millis = System.currentTimeMillis()
        value = Instant.ofEpochMilli(millis)
        delay(60_000 - millis % 60_000 + 50)
    }
}
