package com.zygabad.worktimerecorder.testutil

import com.zygabad.worktimerecorder.data.WorkDao
import com.zygabad.worktimerecorder.data.WorkSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory stand-in for the Room-generated WorkDao, so WorkRepository can be unit-tested on the
 * plain JVM without an emulator/Robolectric. Deliberately simple: ids auto-increment starting at
 * 1 and are never reused, matching Room's SQLite AUTOINCREMENT-free rowid behavior closely enough
 * for these tests (it is NOT a full SQLite semantics replica).
 */
class FakeWorkDao : WorkDao {
    val sessions = mutableListOf<WorkSession>()
    private var nextId = 1

    override fun getSessionsForDate(date: String): Flow<List<WorkSession>> =
        flowOf(sessions.filter { it.date == date }.sortedBy { it.startTime })

    override fun getSessionsForWeek(fromDate: String, toDate: String): Flow<List<WorkSession>> =
        flowOf(
            sessions.filter { it.date >= fromDate && it.date <= toDate }
                .sortedWith(compareBy({ it.date }, { it.startTime }))
        )

    override suspend fun getOpenSession(): WorkSession? = sessions.firstOrNull { it.endTime == null }

    override suspend fun getOpenSessions(): List<WorkSession> = sessions.filter { it.endTime == null }

    override suspend fun getAllSessions(): List<WorkSession> = sessions.toList()

    override suspend fun insert(session: WorkSession): Long {
        val id = nextId++
        sessions.add(session.copy(id = id))
        return id.toLong()
    }

    override suspend fun update(session: WorkSession) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        check(idx >= 0) { "update() called for a session id (${session.id}) not present in the fake dao" }
        sessions[idx] = session
    }

    override suspend fun delete(session: WorkSession) {
        sessions.removeAll { it.id == session.id }
    }
}
