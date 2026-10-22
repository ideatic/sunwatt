package com.ideatic.sunwatt.ui.setup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KioskUrlDraftTest {
    private val link = "https://eu5.fusionsolar.huawei.com/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=DEMO0000"
    private val saved = mutableListOf<String>()

    private fun draft(scope: TestScope, initial: String = "") = KioskUrlDraft(initial, scope, debounceMs = 800) { saved += it }

    @Test fun `typing does not save per keystroke, a valid link saves after the pause`() {
        val scope = TestScope()
        val draft = draft(scope)
        draft.update("https://")
        draft.update(link.take(40))
        scope.advanceUntilIdle()
        assertTrue(saved.isEmpty(), "invalid text waits for submit or leaving")
        draft.update(link)
        scope.advanceTimeBy(799)
        scope.runCurrent()
        assertTrue(saved.isEmpty())
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(listOf(link), saved)
    }

    @Test fun `a new keystroke restarts the pause and only the last text is saved`() {
        val scope = TestScope()
        val draft = draft(scope)
        draft.update(link)
        scope.advanceTimeBy(500)
        draft.update("$link ")
        scope.advanceTimeBy(500)
        scope.runCurrent()
        assertTrue(saved.isEmpty())
        scope.advanceUntilIdle()
        assertEquals(listOf(link), saved)
    }

    @Test fun `paste saves at once and trims`() {
        val scope = TestScope()
        val draft = draft(scope)
        draft.paste("  $link\n")
        assertEquals(listOf(link), saved)
        assertEquals(link, draft.text)
        assertFalse(draft.hasPending)
    }

    @Test fun `leaving saves invalid or empty text once, and never twice`() {
        val scope = TestScope()
        val draft = draft(scope, initial = link)
        draft.update("")
        draft.commitNow()
        draft.commitNow()
        assertEquals(listOf(""), saved)
        draft.update("not a link")
        draft.commitNow()
        assertEquals(listOf("", "not a link"), saved)
    }

    @Test fun `unchanged text is not saved`() {
        val scope = TestScope()
        val draft = draft(scope, initial = link)
        draft.update("$link ")
        scope.advanceUntilIdle()
        draft.commitNow()
        assertTrue(saved.isEmpty())
    }
}
