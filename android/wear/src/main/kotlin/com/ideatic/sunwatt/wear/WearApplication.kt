package com.ideatic.sunwatt.wear

import android.app.Application

class WearApplication : Application() {
    /** Lazy singletons; nothing here runs at start-up. */
    val graph: WearGraph by lazy { WearGraph(this) }
}
