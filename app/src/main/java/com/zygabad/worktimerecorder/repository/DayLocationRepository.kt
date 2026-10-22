package com.zygabad.worktimerecorder.repository

import com.zygabad.worktimerecorder.data.DayLocation
import com.zygabad.worktimerecorder.data.DayLocationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where each day's work happened (Remote/Office/Holiday) — see DayLocationType. */
class DayLocationRepository(private val dao: DayLocationDao) {

    suspend fun getLocation(date: String): String? = dao.getForDate(date)?.type

    fun observeLocation(date: String): Flow<String?> = dao.observeForDate(date).map { it?.type }

    suspend fun setLocation(date: String, type: String) = dao.upsert(DayLocation(date, type))

    /** Keyed by date, for the week/month tables to look a single day's tag up by String key. */
    fun observeLocationsMap(fromDate: String, toDate: String): Flow<Map<String, String>> =
        dao.observeRange(fromDate, toDate).map { list -> list.associate { it.date to it.type } }
}
