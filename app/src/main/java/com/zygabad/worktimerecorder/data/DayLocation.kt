package com.zygabad.worktimerecorder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Where a given day's work happened: Remote, Office, or Holiday (a day off, not actually worked). */
object DayLocationType {
    const val REMOTE = "R"
    const val OFFICE = "O"
    const val HOLIDAY = "H"
}

@Entity(tableName = "day_location")
data class DayLocation(
    @PrimaryKey val date: String, // "YYYY-MM-DD"
    val type: String              // one of DayLocationType
)
