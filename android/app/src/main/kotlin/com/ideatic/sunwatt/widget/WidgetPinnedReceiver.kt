package com.ideatic.sunwatt.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Called by the launcher when a widget asked for with [WidgetPinning.pin] is on the home screen. Some launchers
 * (MIUI) place it on a new page with no dialog, so without this message the tap seems to do nothing. MIUI does not
 * call this at all; see [WidgetPinning.confirmPinned].
 */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetPinning.confirmPinned(context)
    }
}
