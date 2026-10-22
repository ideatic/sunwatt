package com.ideatic.sunwatt.wear.demo

import android.content.Context
import androidx.core.content.edit
import com.ideatic.sunwatt.core.data.SampleData
import com.ideatic.sunwatt.core.data.SunWattData
import java.time.Instant

/**
 * Debug builds only: after `SeedActivity` ran, the watch shows the sample data of one [SampleData.Scenario] and its clock
 * stands at that scenario's moment (the sample day is a fixed past date, so with the real clock every solar page would
 * read as "yesterday"). Nothing is fetched while it is on. `SeedActivity` with `--es scenario off` switches it off.
 */
object DemoClock {
    private const val PREFS = "demo"
    private const val KEY = "scenario"
    const val UNCONFIGURED = "unconfigured"

    private fun scenario(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    private fun entry(context: Context) = when (val name = scenario(context)) {
        null -> null
        UNCONFIGURED -> SampleData.notConfiguredEntry()
        else -> SampleData.Scenario.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { SampleData.entry(it) }
    }

    fun now(context: Context): Instant? = entry(context)?.date

    fun data(context: Context): SunWattData? = entry(context)?.data

    /** [name]: a scenario name, [UNCONFIGURED], or null to leave demo mode. */
    fun pin(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { if (name == null) remove(KEY) else putString(KEY, name) }
    }
}
