package com.ideatic.sunwatt.wear.demo

import android.app.Activity
import android.os.Bundle

/**
 * Debug only: puts the app in demo mode with sample data. Extra `scenario`: `day`, `night`, `stale`, `error`,
 * `unconfigured`, or `off` to leave demo mode. Stop the app (`am force-stop`) and start it again to see the change.
 */
class SeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent.getStringExtra("scenario") ?: "day"
        DemoClock.pin(this, if (scenario == "off") null else scenario)
        finish()
    }
}
