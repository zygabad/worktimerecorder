package com.zygabad.worktimerecorder.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {
    @Query("SELECT * FROM work_sessions WHERE date = :date ORDER BY startTime")
    fun getSessionsForDate(date: String): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE date >= :fromDate AND date <= :toDate ORDER BY date, startTime")
    fun getSessionsForWeek(fromDate: String, toDate: String): Flow<List<WorkSession>>

    @Query("SELECT * FROM work_sessions WHERE endTime IS NULL LIMIT 1")
    suspend fun getOpenSession(): WorkSession?

    @Query("SELECT * FROM work_sessions WHERE endTime IS NULL")
    suspend fun getOpenSessions(): List<WorkSession>

    @Query("SELECT * FROM work_sessions")
    suspend fun getAllSessions(): List<WorkSession>

    @Insert
    suspend fun insert(session: WorkSession): Long

    @Update
    suspend fun update(session: WorkSession)

    @Delete
    suspend fun delete(session: WorkSession)
}
