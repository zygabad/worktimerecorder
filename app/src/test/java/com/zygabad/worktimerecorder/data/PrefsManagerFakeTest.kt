package com.zygabad.worktimerecorder.data

import com.zygabad.worktimerecorder.testutil.testPrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.zygabad.worktimerecorder.util.WatchPushHistoryEntry

/**
 * Sanity check for the fake-SharedPreferences-backed PrefsManager test double itself: if this
 * fails, every WorkRepository test relying on testPrefsManager() would be suspect too.
 */
class PrefsManagerFakeTest {

    @Test
    fun `defaults match the real PrefsManager defaults`() {
        val prefs = testPrefsManager()
        assertEquals(510, prefs.targetWorkMinutes)
        assertFalse(prefs.isWorking)
        assertEquals(-1L, prefs.currentSessionStart)
        assertEquals(-1L, prefs.currentSessionId)
    }

    /**
     * The watch diagnostic has to survive the app process being killed — the push that produced it
     * usually runs from the foreground service, and Zygmunt reads the line much later, after the UI
     * process has been recreated. So it lives in SharedPreferences, not in memory.
     */
    @Test
    fun `last watch push status defaults to empty and round-trips`() {
        val prefs = testPrefsManager()
        assertEquals("", prefs.lastWatchPushStatus)

        prefs.lastWatchPushStatus = "14:32 — wyslano do Forerunner 955 (SUCCESS)"
        assertEquals("14:32 — wyslano do Forerunner 955 (SUCCESS)", prefs.lastWatchPushStatus)

        // A later attempt overwrites: only the newest outcome is of any use.
        prefs.lastWatchPushStatus = "14:47 — Forerunner 955: NOT_CONNECTED"
        assertEquals("14:47 — Forerunner 955: NOT_CONNECTED", prefs.lastWatchPushStatus)
    }

    /**
     * The push history has to survive the process being killed for the same reason as the status
     * line above: pushes run from the service/widget, the history is read later in Ustawienia.
     */
    @Test
    fun `watch push history defaults to empty and round-trips through storage`() {
        val prefs = testPrefsManager()
        assertEquals(emptyList<WatchPushHistoryEntry>(), prefs.watchPushHistory)

        val history = listOf(
            WatchPushHistoryEntry("08:07:31", "start", "wyslano do fenix 7 (SUCCESS) | praca: tak", "261116080731"),
            WatchPushHistoryEntry("22:01:00", "reczne", "fenix 7: NOT_CONNECTED", null)
        )
        prefs.watchPushHistory = history
        assertEquals(history, prefs.watchPushHistory)
    }

    @Test
    fun `writes are readable back through the same instance`() {
        val prefs = testPrefsManager()
        prefs.isWorking = true
        prefs.currentSessionStart = 12345L
        prefs.currentSessionId = 7L
        prefs.targetWorkMinutes = 480

        assertEquals(true, prefs.isWorking)
        assertEquals(12345L, prefs.currentSessionStart)
        assertEquals(7L, prefs.currentSessionId)
        assertEquals(480, prefs.targetWorkMinutes)
    }
}
