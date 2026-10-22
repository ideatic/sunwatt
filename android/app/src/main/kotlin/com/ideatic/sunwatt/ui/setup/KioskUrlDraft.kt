package com.ideatic.sunwatt.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The text of the kiosk link field, kept apart from the saved settings. Saving writes the store, pushes to the watch and
 * reloads the widgets, so it must not run per keystroke. The text is committed (once, only when it differs from what is
 * already committed):
 * - when the user submits, leaves the field or the screen goes away ([commitNow]),
 * - after the user pastes ([paste]),
 * - [debounceMs] after the last keystroke, when the text is a valid kiosk link.
 * Invalid text and the empty field wait for one of the first two events. The validation message reads [text].
 */
class KioskUrlDraft(
    saved: String,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val commit: (String) -> Unit,
) {
    /** What the field shows. */
    var text by mutableStateOf(saved)
        private set

    /** Last value passed to `commit` (or the initial saved value). */
    var committed: String = normalized(saved)
        private set

    private var pending: Job? = null

    val hasPending: Boolean get() = pending != null

    val inputState: KioskInputState get() = kioskInputState(text)

    /** The user typed or edited. Restarts the debounce when the text is a valid link. */
    fun update(newText: String) {
        if (newText == text) return
        text = newText
        pending?.cancel()
        pending = null
        if (!kioskInputState(newText).isValid || normalized(newText) == committed) return
        pending = scope.launch {
            delay(debounceMs)
            pending = null
            commitNow()
        }
    }

    /** The user pasted a link: it replaces the text and is saved at once. */
    fun paste(pasted: String) {
        pending?.cancel()
        pending = null
        text = normalized(pasted)
        commitNow()
    }

    /** Saves the text now (submit, focus loss, screen going away). No-op when it is already saved. */
    fun commitNow() {
        pending?.cancel()
        pending = null
        val value = normalized(text)
        if (value == committed) return
        committed = value
        commit(value)
    }

    private fun normalized(string: String) = string.trim()

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 800L
    }
}
