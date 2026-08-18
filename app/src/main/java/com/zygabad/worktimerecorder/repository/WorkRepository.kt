package com.zygabad.worktimerecorder.repository

import com.zygabad.worktimerecorder.data.WorkDao
import com.zygabad.worktimerecorder.data.WorkSession
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WorkRepository(private val dao: WorkDao) {
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun getTodaySessions(): Flow<List<WorkSession>> =
        dao.getSessionsForDate(LocalDate.now().format(fmt))

    fun getWeekSessions(weekStart: LocalDate): Flow<List<WorkSession>> =
        dao.getSessionsForWeek(weekStart.format(fmt), weekStart.plusDays(6).format(fmt))

    suspend fun startSession(): Long {
        val session = WorkSession(
            date = LocalDate.now().format(fmt),
            startTime = System.currentTimeMillis()
        )
        return dao.insert(session)
    }

    suspend fun stopSession(sessionId: Long, startTime: Long) {
        val end = System.currentTimeMillis()
        dao.update(
            WorkSession(
                id = sessionId.toInt(),
                date = LocalDate.now().format(fmt),
                startTime = startTime,
                endTime = end,
                durationMinutes = ((end - startTime) / 60_000).toInt()
            )
        )
    }
}
