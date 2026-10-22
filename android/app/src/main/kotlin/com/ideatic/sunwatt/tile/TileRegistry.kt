package com.ideatic.sunwatt.tile

import android.content.Context

/** Which of the SunWatt Quick Settings tiles the user has added; the refresh work runs only while a tile or widget exists. */
object TileRegistry {
    private const val FILE = "sunwatt_tiles"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun set(context: Context, tile: String, added: Boolean) {
        prefs(context).edit().putBoolean(tile, added).apply()
    }

    fun count(context: Context): Int = prefs(context).all.values.count { it == true }
}
