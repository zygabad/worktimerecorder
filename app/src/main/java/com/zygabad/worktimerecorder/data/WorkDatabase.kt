package com.zygabad.worktimerecorder.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [WorkSession::class, DayLocation::class], version = 2, exportSchema = false)
abstract class WorkDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun dayLocationDao(): DayLocationDao

    companion object {
        @Volatile private var INSTANCE: WorkDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_location` (`date` TEXT NOT NULL, `type` TEXT NOT NULL, PRIMARY KEY(`date`))"
                )
            }
        }

        fun getDatabase(context: Context): WorkDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, WorkDatabase::class.java, "work_timer_db")
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
