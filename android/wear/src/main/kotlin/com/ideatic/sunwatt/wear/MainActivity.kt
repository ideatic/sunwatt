package com.ideatic.sunwatt.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ideatic.sunwatt.wear.sync.WearSettingsListenerService
import com.ideatic.sunwatt.wear.ui.WearApp
import com.ideatic.sunwatt.wear.work.WearRefreshWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as WearApplication
        val graph = application.graph
        lifecycleScope.launch {
            graph.loadStored()
            // Settings the phone sent while the watch app was not reachable.
            WearSettingsListenerService.pull(application)
        }
        WearRefreshWorker.schedule(this)
        setContent { WearApp(graph) }
    }
}
