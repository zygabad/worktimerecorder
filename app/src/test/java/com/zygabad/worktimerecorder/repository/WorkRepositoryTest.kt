package com.zygabad.worktimerecorder.repository

import com.zygabad.worktimerecorder.data.WorkSession
import com.zygabad.worktimerecorder.testutil.FakeWorkDao
import com.zygabad.worktimerecorder.testutil.testPrefsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Regression tests for the historical bugs described in
 * Knowledge/Business/06_technical_lessons_learned.md and the WorkTimeRecorder bug history:
 * duplicate "open" sessions summing into impossible daily totals, and the various self-heal /
 * safety-net mechanisms added to prevent and clean that up.
 */
class WorkRepositoryTest {

    private lateinit var dao: FakeWorkDao
    private lateinit var repo: WorkRepository

    @Before
    fun setUp() {
        dao = FakeWorkDao()
        repo = WorkRepository(dao)
    }

    // --- Bug #1: toggleWork must never leave more than one open session ---

    @Test
    fun `toggleWork start then stop leaves zero open sessions`() = runTest {
        val prefs = testPrefsManager()

        repo.toggleWork(prefs) // start
        assertEquals(1, dao.sessions.size)
        assertNull("session should be open right after starting", dao.sessions.single().endTime)

        repo.toggleWork(prefs) // stop
        assertEquals(0, dao.getOpenSessions().size)
        assertEquals(1, dao.sessions.size)
        assertTrue("stopped session must have an endTime", dao.sessions.single().endTime != null)
        assertFalse(prefs.isWorking)
        assertEquals(-1L, prefs.currentSessionId)
    }

    @Test
    fun `third toggleWork after stop-start does not create a duplicate open session`() = runTest {
        val prefs = testPrefsManager()

        repo.toggleWork(prefs) // start #1
        repo.toggleWork(prefs) // stop #1
        repo.toggleWork(prefs) // start #2

        val open = dao.getOpenSessions()
        assertEquals("exactly one open session after start-stop-start", 1, open.size)
        assertEquals(2, dao.sessions.size) // one closed, one open
        assertTrue(prefs.isWorking)
        assertEquals(open.single().id.toLong(), prefs.currentSessionId)
    }

    @Test
    fun `toggleWork closes the session the DB says is open even if prefs disagree`() = runTest {
        // Simulates the widget/app divergence bug: prefs thinks nothing is running, but the DB
        // has an open row (e.g. widget started it). toggleWork must trust the DB, not prefs.
        val prefs = testPrefsManager()
        dao.insert(WorkSession(date = today(), startTime = System.currentTimeMillis() - 60_000))
        assertFalse(prefs.isWorking) // prefs never got told

        repo.toggleWork(prefs)

        assertEquals(0, dao.getOpenSessions().size)
        assertFalse(prefs.isWorking)
    }

    // --- Bug #3: autoStopIfStale safety net ---

    @Test
    fun `autoStopIfStale closes a 21h session at exactly AUTO_STOP_MINUTES duration`() = runTest {
        val prefs = testPrefsManager()
        val start = System.currentTimeMillis() - 21 * 60 * 60 * 1000L // 21h ago
        dao.insert(WorkSession(date = today(), startTime = start))

        val closed = repo.autoStopIfStale(prefs)

        assertTrue(closed)
        val session = dao.sessions.single()
        assertEquals(AUTO_STOP_MINUTES, session.durationMinutes)
        assertEquals(start + AUTO_STOP_MINUTES * 60_000L, session.endTime)
        assertEquals(0, dao.getOpenSessions().size)
    }

    @Test
    fun `autoStopIfStale does nothing for a session younger than AUTO_STOP_MINUTES`() = runTest {
        val prefs = testPrefsManager()
        val start = System.currentTimeMillis() - 60 * 60 * 1000L // 1h ago
        dao.insert(WorkSession(date = today(), startTime = start))

        val closed = repo.autoStopIfStale(prefs)

        assertFalse(closed)
        assertNull(dao.sessions.single().endTime)
    }

    // --- Bug #2 / cleanupCorruptedSessions self-heal ---

    @Test
    fun `cleanupCorruptedSessions removes duplicate open sessions keeping only the one prefs points to`() = runTest {
        val prefs = testPrefsManager()
        val now = System.currentTimeMillis()
        val keptId = dao.insert(WorkSession(date = today(), startTime = now - 5 * 60_000))
        dao.insert(WorkSession(date = today(), startTime = now - 3 * 60_000)) // rogue duplicate #1
        dao.insert(WorkSession(date = today(), startTime = now - 1 * 60_000)) // rogue duplicate #2
        prefs.isWorking = true
        prefs.currentSessionId = keptId
        prefs.currentSessionStart = now - 5 * 60_000

        repo.cleanupCorruptedSessions(prefs)

        val remainingOpen = dao.getOpenSessions()
        assertEquals(1, remainingOpen.size)
        assertEquals(keptId, remainingOpen.single().id.toLong())
        assertTrue(prefs.isWorking)
    }

    @Test
    fun `cleanupCorruptedSessions discards an unowned open session when prefs thinks nothing is running`() = runTest {
        val prefs = testPrefsManager() // isWorking = false (default)
        dao.insert(WorkSession(date = today(), startTime = System.currentTimeMillis() - 60_000))

        repo.cleanupCorruptedSessions(prefs)

        assertEquals(0, dao.getOpenSessions().size)
        assertFalse(prefs.isWorking)
    }

    @Test
    fun `cleanupCorruptedSessions deletes a closed session with an impossibly long duration`() = runTest {
        val prefs = testPrefsManager()
        val start = System.currentTimeMillis() - 30L * 60 * 60 * 1000 // 30h span, way past MAX_SESSION_MINUTES
        dao.insert(
            WorkSession(
                date = today(),
                startTime = start,
                endTime = start + 30L * 60 * 60 * 1000,
                durationMinutes = 30 * 60
            )
        )

        repo.cleanupCorruptedSessions(prefs)

        assertTrue("corrupted session should be deleted, not kept with a bogus duration", dao.sessions.isEmpty())
    }

    @Test
    fun `cleanupCorruptedSessions recomputes a stale durationMinutes for a legitimately closed session`() = runTest {
        val prefs = testPrefsManager()
        val start = System.currentTimeMillis() - 60 * 60_000 // 60 min real span
        dao.insert(
            WorkSession(date = today(), startTime = start, endTime = start + 60 * 60_000, durationMinutes = 999)
        )

        repo.cleanupCorruptedSessions(prefs)

        assertEquals(60, dao.sessions.single().durationMinutes)
    }

    // --- updateSessionTimes (manual edit) ---

    @Test
    fun `updateSessionTimes recomputes duration from the new start and end`() = runTest {
        val id = dao.insert(WorkSession(date = today(), startTime = 0L, endTime = 30 * 60_000L, durationMinutes = 30))
        val session = dao.sessions.single { it.id.toLong() == id }

        repo.updateSessionTimes(session, newStart = 0L, newEnd = 90 * 60_000L)

        assertEquals(90, dao.sessions.single().durationMinutes)
    }

    @Test
    fun `updateSessionTimes coerces a negative span to zero instead of a negative duration`() = runTest {
        val id = dao.insert(WorkSession(date = today(), startTime = 0L, endTime = 60_000L, durationMinutes = 1))
        val session = dao.sessions.single { it.id.toLong() == id }

        // Edited end ends up before the (unchanged) start — shouldn't produce a negative duration.
        repo.updateSessionTimes(session, newStart = 60 * 60_000L, newEnd = 0L)

        assertEquals(0, dao.sessions.single().durationMinutes)
    }

    // --- Week/month range queries (date-range edge cases) ---

    @Test
    fun `getWeekSessions includes the full Monday-to-Sunday range and excludes days outside it`() = runTest {
        val monday = LocalDate.of(2026, 8, 17) // a real Monday
        dao.sessions.add(WorkSession(id = 1, date = "2026-08-17", startTime = 0L)) // Monday: in range
        dao.sessions.add(WorkSession(id = 2, date = "2026-08-23", startTime = 0L)) // Sunday: in range
        dao.sessions.add(WorkSession(id = 3, date = "2026-08-16", startTime = 0L)) // prior Sunday: out
        dao.sessions.add(WorkSession(id = 4, date = "2026-08-24", startTime = 0L)) // next Monday: out

        val result = repo.getWeekSessions(monday).first()

        assertEquals(setOf(1, 2), result.map { it.id }.toSet())
    }

    private fun today(): String = LocalDate.now().toString()
}
