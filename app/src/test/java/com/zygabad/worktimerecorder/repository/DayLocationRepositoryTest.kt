package com.zygabad.worktimerecorder.repository

import com.zygabad.worktimerecorder.data.DayLocationType
import com.zygabad.worktimerecorder.testutil.FakeDayLocationDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers the day-location (Remote/Office/Holiday) feature: a day's work-location tag is set once
 * (via a prompt when the day's first session starts, or manually) and can be looked up for a
 * single date or a date range (for the week/month tables).
 */
class DayLocationRepositoryTest {

    private lateinit var dao: FakeDayLocationDao
    private lateinit var repo: DayLocationRepository

    @Before
    fun setUp() {
        dao = FakeDayLocationDao()
        repo = DayLocationRepository(dao)
    }

    @Test
    fun `getLocation returns null for a date with nothing recorded`() = runTest {
        assertNull(repo.getLocation("2026-10-22"))
    }

    @Test
    fun `setLocation then getLocation round-trips the chosen type`() = runTest {
        repo.setLocation("2026-10-22", DayLocationType.REMOTE)
        assertEquals(DayLocationType.REMOTE, repo.getLocation("2026-10-22"))
    }

    @Test
    fun `setLocation overwrites a previously chosen type for the same date`() = runTest {
        repo.setLocation("2026-10-22", DayLocationType.OFFICE)
        repo.setLocation("2026-10-22", DayLocationType.HOLIDAY)
        assertEquals(DayLocationType.HOLIDAY, repo.getLocation("2026-10-22"))
    }

    @Test
    fun `observeRange as a map only includes dates within range and keys by date`() = runTest {
        repo.setLocation("2026-10-19", DayLocationType.OFFICE) // before range
        repo.setLocation("2026-10-20", DayLocationType.REMOTE) // in range
        repo.setLocation("2026-10-24", DayLocationType.HOLIDAY) // in range
        repo.setLocation("2026-10-27", DayLocationType.OFFICE) // after range

        val map = repo.observeLocationsMap("2026-10-20", "2026-10-26").first()

        assertEquals(mapOf("2026-10-20" to DayLocationType.REMOTE, "2026-10-24" to DayLocationType.HOLIDAY), map)
    }
}
