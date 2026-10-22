package com.zygabad.worktimerecorder.testutil

import com.zygabad.worktimerecorder.data.DayLocation
import com.zygabad.worktimerecorder.data.DayLocationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory stand-in for the Room-generated DayLocationDao — see FakeWorkDao for the same pattern. */
class FakeDayLocationDao : DayLocationDao {
    val locations = mutableMapOf<String, DayLocation>()

    override suspend fun getForDate(date: String): DayLocation? = locations[date]

    override fun observeForDate(date: String): Flow<DayLocation?> = flowOf(locations[date])

    override fun observeRange(fromDate: String, toDate: String): Flow<List<DayLocation>> =
        flowOf(locations.values.filter { it.date >= fromDate && it.date <= toDate })

    override suspend fun upsert(location: DayLocation) {
        locations[location.date] = location
    }
}
