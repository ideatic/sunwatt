package com.ideatic.sunwatt.data

import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.store.SunWattStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The settings as an observable value on top of [SunWattStore] (whose calls are synchronous disk work). */
class SettingsRepository(
    private val store: SunWattStore,
    /** Where the disk work runs; tests pass their own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<SunWattSettings?>(null)
    private val mutex = Mutex()

    /** Null until [load] has read the saved settings. */
    val state: StateFlow<SunWattSettings?> = _state.asStateFlow()

    suspend fun load() {
        if (_state.value != null) return
        mutex.withLock {
            if (_state.value == null) _state.value = withContext(io) { store.loadSettings() }
        }
    }

    /** Applies [change] to the current settings, saves the result and publishes it. */
    suspend fun update(change: (SunWattSettings) -> SunWattSettings) {
        mutex.withLock {
            withContext(io) {
                val updated = change(_state.value ?: store.loadSettings())
                store.saveSettings(updated)
                _state.value = updated
            }
        }
    }
}
