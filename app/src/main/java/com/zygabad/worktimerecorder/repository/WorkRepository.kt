package com.zygabad.worktimerecorder.repository

import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDao
import com.zygabad.worktimerecorder.data.WorkSession
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

const val AUTO_STOP_MINUTES = 20 * 60 // safety net: a session left running this long auto-closes, e.g. forgot to tap stop
private const val MAX_SESSION_MINUTES = AUTO_STOP_MINUTES + 60 // longer than that (with margin) is corrupted data, not real work

class WorkRepository(private val dao: WorkDao) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun getTodaySessions(): Flow<List<WorkSession>> =
        dao.getSessionsForDate(LocalDate.now().format(fmt))

    fun getWeekSessions(weekStart: LocalDate): Flow<List<WorkSession>> =
        dao.getSessionsForWeek(weekStart.format(fmt), weekStart.plusDays(6).format(fmt))

    fun getMonthSessions(monthStart: LocalDate): Flow<List<WorkSession>> =
        dao.getSessionsForWeek(monthStart.format(fmt), monthStart.plusMonths(1).minusDays(1).format(fmt))

    /**
     * Live view of whichever session is currently open, straight from Room. Anything that reads
     * this (rather than a ViewModel-cached copy of prefs.isWorking) can't go stale when the
     * widget's ToggleTimerAction changes the DB from outside the app's own process lifecycle —
     * the widget and an already-running MainViewModel used to disagree about whether work was
     * in progress because the ViewModel only ever synced its isWorking/sessionStartTime state
     * inside its own toggle/edit methods, never in response to an external DB change.
     */
    fun observeOpenSession(): Flow<WorkSession?> = dao.observeOpenSession()

    /**
     * Single source of truth for starting/stopping work: always checks the DB for an
     * already-open session instead of trusting SharedPreferences, so the widget action
     * and the in-app button can never diverge into two simultaneously "open" sessions.
     */
    suspend fun toggleWork(prefs: PrefsManager) {
        val open = dao.getOpenSession()
        if (open != null) {
            val end = System.currentTimeMillis()
            dao.update(
                open.copy(
                    endTime = end,
                    durationMinutes = ((end - open.startTime) / 60_000).toInt().coerceAtLeast(0)
                )
            )
            prefs.isWorking = false
            prefs.currentSessionStart = -1L
            prefs.currentSessionId = -1L
        } else {
            val start = System.currentTimeMillis()
            val id = dao.insert(WorkSession(date = LocalDate.now().format(fmt), startTime = start))
            prefs.isWorking = true
            prefs.currentSessionStart = start
            prefs.currentSessionId = id
        }
    }

    suspend fun deleteSession(session: WorkSession) = dao.delete(session)

    suspend fun updateSessionTimes(session: WorkSession, newStart: Long, newEnd: Long?) {
        val duration = newEnd?.let { ((it - newStart) / 60_000).toInt().coerceAtLeast(0) } ?: 0
        dao.update(session.copy(startTime = newStart, endTime = newEnd, durationMinutes = duration))
    }

    /**
     * Safety net for forgetting to stop the timer: a session left open this long closes itself
     * at the AUTO_STOP_MINUTES mark. Returns true if it closed something.
     */
    suspend fun autoStopIfStale(prefs: PrefsManager): Boolean {
        val open = dao.getOpenSession() ?: return false
        val elapsed = ((System.currentTimeMillis() - open.startTime) / 60_000).toInt()
        if (elapsed < AUTO_STOP_MINUTES) return false

        val end = open.startTime + AUTO_STOP_MINUTES * 60_000L
        dao.update(open.copy(endTime = end, durationMinutes = AUTO_STOP_MINUTES))
        if (prefs.isWorking) {
            prefs.isWorking = false
            prefs.currentSessionStart = -1L
            prefs.currentSessionId = -1L
        }
        return true
    }

    /**
     * One-time self-heal for sessions left behind by earlier bugs: multiple simultaneously
     * "open" (endTime == null) rows each counted live elapsed time and got summed, producing
     * impossible daily totals (tens of hours in a single day). None of that is real work time,
     * so it's discarded rather than guessed at.
     */
    suspend fun cleanupCorruptedSessions(prefs: PrefsManager) {
        autoStopIfStale(prefs)

        val legitOpenId = if (prefs.isWorking) prefs.currentSessionId else -1L

        dao.getOpenSessions().forEach { session ->
            if (session.id.toLong() != legitOpenId) dao.delete(session)
        }

        dao.getAllSessions().forEach { session ->
            val end = session.endTime ?: return@forEach
            val recomputed = ((end - session.startTime) / 60_000).toInt()
            if (session.startTime <= 0 || end < session.startTime || recomputed > MAX_SESSION_MINUTES) {
                dao.delete(session)
            } else if (recomputed != session.durationMinutes) {
                dao.update(session.copy(durationMinutes = recomputed))
            }
        }

        if (prefs.isWorking && dao.getOpenSession() == null) {
            // The session prefs pointed to got cleaned up above; nothing left running.
            prefs.isWorking = false
            prefs.currentSessionStart = -1L
            prefs.currentSessionId = -1L
        }
    }
}
