package com.zygabad.worktimerecorder.data

import android.content.Context
import androidx.room.*

@Database(entities = [WorkSession::class], version = 1, exportSchema = false)
abstract class WorkDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao

    companion object {
        @Volatile private var INSTANCE: WorkDatabase? = null
        fun getDatabase(context: Context): WorkDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, WorkDatabase::class.java, "work_timer_db")
                    .build().also { INSTANCE = it }
            }
    }
}
