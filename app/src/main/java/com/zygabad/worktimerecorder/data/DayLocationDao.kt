package com.zygabad.worktimerecorder.data

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DayLocationDao {
    @Query("SELECT * FROM day_location WHERE date = :date LIMIT 1")
    suspend fun getForDate(date: String): DayLocation?

    @Query("SELECT * FROM day_location WHERE date = :date LIMIT 1")
    fun observeForDate(date: String): Flow<DayLocation?>

    @Query("SELECT * FROM day_location WHERE date >= :fromDate AND date <= :toDate")
    fun observeRange(fromDate: String, toDate: String): Flow<List<DayLocation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: DayLocation)
}
