package com.ideatic.sunwatt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ideatic.sunwatt.debug.DemoLaunch
import com.ideatic.sunwatt.ui.AppViewModel
import com.ideatic.sunwatt.core.models.SunWattLink
import com.ideatic.sunwatt.ui.AppRoot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val refreshChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * One item per `sunwatt://refresh` link (DECISIONS D25: a widget tap opens the app and refreshes). Buffered until
     * the dashboard collects it, then delivered once. Several links in a row count as one.
     */
    val refreshRequests: Flow<Unit> = refreshChannel.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as SunWattApplication).graph
        if (savedInstanceState == null) DemoLaunch.seed(applicationContext, graph, intent)
        val viewModel by viewModels<AppViewModel> { AppViewModel.factory(graph, this, DemoLaunch.from(intent)) }
        // A link that started the activity; not again after a rotation (the saved state means it was handled).
        if (savedInstanceState == null) handleLink(intent)
        setContent { AppRoot(vm = viewModel, refreshRequests = refreshRequests) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (SunWattLink.parse(uri) == SunWattLink.REFRESH) refreshChannel.trySend(Unit)
    }
}
