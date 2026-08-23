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
