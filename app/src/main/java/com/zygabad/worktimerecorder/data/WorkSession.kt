package com.zygabad.worktimerecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "work_sessions")
data class WorkSession(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: String,           // "YYYY-MM-DD"
    val startTime: Long,        // epoch ms
    val endTime: Long? = null,
    val durationMinutes: Int = 0
)
