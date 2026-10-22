package com.ideatic.sunwatt.wear.demo

import android.content.Context
import com.ideatic.sunwatt.core.data.SunWattData
import java.time.Instant

/** Release builds have no demo mode: the clock is the device clock and the data come from the loader. */
@Suppress("UNUSED_PARAMETER")
object DemoClock {
    fun now(context: Context): Instant? = null

    fun data(context: Context): SunWattData? = null
}
