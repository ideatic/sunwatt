package com.ideatic.sunwatt

import android.app.Application

class SunWattApplication : Application() {
    /** Lazy singletons; nothing here runs at start-up (cold start). */
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        com.ideatic.sunwatt.wearsync.WearSettingsSender.start(this) // Wear OS: launches a background coroutine, returns at once.
    }
}
