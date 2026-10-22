package com.ideatic.sunwatt.debug

import android.content.Context
import android.content.Intent
import com.ideatic.sunwatt.AppGraph
import com.ideatic.sunwatt.ui.model.DemoSetup

/** Release builds have no demo mode: the debug source set holds the real one (same name, same functions). */
object DemoLaunch {
    @Suppress("UNUSED_PARAMETER")
    fun from(intent: Intent?): DemoSetup? = null

    @Suppress("UNUSED_PARAMETER")
    fun seed(context: Context, graph: AppGraph, intent: Intent?) = Unit
}
