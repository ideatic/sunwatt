package com.ideatic.sunwatt.wear.sync

import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.ideatic.sunwatt.wear.WearApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Receives the settings the phone sends, also when the watch app is not running. */
class WearSettingsListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // Runs on a worker thread of the Wearable service: saving (a Keystore call and a file write) is fine here.
        val inbox = (application as WearApplication).graph.inbox
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != WearSettingsSync.PATH) continue
            inbox.apply(DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray(WearSettingsSync.SETTINGS_KEY))
        }
    }

    companion object {
        /**
         * Reads the item the phone sent while the app was not installed or not reachable (the Data Layer keeps the last
         * one) and applies it. Does nothing without Google Play services or a paired phone.
         */
        suspend fun pull(application: WearApplication) {
            try {
                val uri = Uri.Builder().scheme("wear").path(WearSettingsSync.PATH).build()
                val items = Wearable.getDataClient(application).getDataItems(uri).await()
                try {
                    for (item in items) {
                        application.graph.inbox.apply(DataMapItem.fromDataItem(item).dataMap.getByteArray(WearSettingsSync.SETTINGS_KEY))
                    }
                } finally {
                    items.release()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No Play services or no phone: the stored settings stay.
            }
        }
    }
}
