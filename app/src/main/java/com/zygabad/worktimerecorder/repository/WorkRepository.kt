package com.zygabad.worktimerecorder.repository

import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDao
import com.zygabad.worktimerecorder.data.WorkSession
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val MAX_SESSION_MINUTES = 16 * 60 // a single work session longer than this is corrupted data, not real work

class WorkRepository(private val dao: WorkDao) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun getTodaySessions(): Flow<List<WorkSession>> =
        dao.getSessionsForDate(LocalDate.now().format(fmt))

    fun getWeekSessions(weekStart: LocalDate): Flow<List<WorkSession>> =
        dao.getSessionsForWeek(weekStart.format(fmt), weekStart.plusDays(6).format(fmt))

    fun getMonthSessions(monthStart: LocalDate): Flow<List<WorkSession>> =
        dao.getSessionsForWeek(monthStart.format(fmt), monthStart.plusMonths(1).minusDays(1).format(fmt))

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

    /**
     * One-time self-heal for sessions left behind by earlier bugs: multiple simultaneously
     * "open" (endTime == null) rows each counted live elapsed time and got summed, producing
     * impossible daily totals (tens of hours in a single day). None of that is real work time,
     * so it's discarded rather than guessed at.
     */
    suspend fun cleanupCorruptedSessions(prefs: PrefsManager) {
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
