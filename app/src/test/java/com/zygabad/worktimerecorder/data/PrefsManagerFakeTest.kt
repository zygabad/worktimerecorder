package com.zygabad.worktimerecorder.data

import com.zygabad.worktimerecorder.testutil.testPrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
