package com.zygabad.worktimerecorder.util

import com.zygabad.worktimerecorder.data.WorkSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * These exercise the exact functions MainViewModel delegates to (see MainViewModel.kt) — not a
 * duplicate reimplementation — so a regression here is a regression in the running app.
 */
class TimeCalculationsTest {

    // --- Bug #4: "8h 33m" must not round down to "8h" ---

    @Test
    fun `formatMinutes does not truncate the minutes remainder`() {
        assertEquals("8h 33m", formatMinutes(8 * 60 + 33))
    }

    @Test
    fun `formatMinutes pads single-digit minutes with a leading zero`() {
        assertEquals("8h 05m", formatMinutes(8 * 60 + 5))
    }

    @Test
    fun `formatMinutes handles exactly zero`() {
        assertEquals("0h 00m", formatMinutes(0))
    }

    @Test
    fun `formatSeconds shows h m s once past an hour and m s below it`() {
        assertEquals("1:00:05", formatSeconds(3605))
        assertEquals("5:09", formatSeconds(309))
    }

    // --- sessionMinutes: persisted duration takes priority; live recompute is the fallback ---

    @Test
    fun `sessionMinutes uses persisted durationMinutes when present`() {
        val session = WorkSession(date = "2026-08-20", startTime = 0L, endTime = 999_999L, durationMinutes = 42)
        assertEquals(42, sessionMinutes(session, nowMillis = 0L))
    }

    @Test
    fun `sessionMinutes recomputes from start-end when durationMinutes is not yet set`() {
        val session = WorkSession(date = "2026-08-20", startTime = 0L, endTime = 30 * 60_000L, durationMinutes = 0)
        assertEquals(30, sessionMinutes(session, nowMillis = 0L))
    }

    @Test
    fun `sessionMinutes for a still-open session uses now instead of endTime`() {
        val session = WorkSession(date = "2026-08-20", startTime = 0L, endTime = null, durationMinutes = 0)
        assertEquals(15, sessionMinutes(session, nowMillis = 15 * 60_000L))
    }

    @Test
    fun `getDayMinutes sums only sessions matching the given date`() {
        val sessions = listOf(
            WorkSession(id = 1, date = "2026-08-20", startTime = 0L, durationMinutes = 30),
            WorkSession(id = 2, date = "2026-08-20", startTime = 0L, durationMinutes = 45),
            WorkSession(id = 3, date = "2026-08-21", startTime = 0L, durationMinutes = 999),
        )
        assertEquals(75, getDayMinutes("2026-08-20", sessions))
    }

    @Test
    fun `getTodayTotalMinutes adds the live elapsed time only while working`() {
        val closed = listOf(WorkSession(date = "2026-08-20", startTime = 0L, endTime = 1L, durationMinutes = 100))
        assertEquals(100, getTodayTotalMinutes(closed, isWorking = false, elapsedSeconds = 999))
        assertEquals(110, getTodayTotalMinutes(closed, isWorking = true, elapsedSeconds = 10 * 60))
    }

    // --- combineDateTime: manual edit dialog picks an hour/minute for an existing date ---

    @Test
    fun `combineDateTime combines the session date with the picked hour and minute`() {
        val zone = java.time.ZoneId.of("Europe/Warsaw")
        val millis = combineDateTime("2026-08-20", 14, 30, zone)
        val back = java.time.Instant.ofEpochMilli(millis).atZone(zone)
        assertEquals(LocalDate.of(2026, 8, 20), back.toLocalDate())
        assertEquals(14, back.hour)
        assertEquals(30, back.minute)
    }

    // --- week/month navigation must never allow paging into the future ---

    @Test
    fun `computeNextWeekStart advances when the next week is not in the future`() {
        val today = LocalDate.of(2026, 8, 24) // a Monday
        val current = today.minusWeeks(1)
        assertEquals(today, computeNextWeekStart(current, today))
    }

    @Test
    fun `computeNextWeekStart refuses to advance into a future week`() {
        val today = LocalDate.of(2026, 8, 24) // a Monday
        assertEquals(today, computeNextWeekStart(today, today)) // already on current week: no-op
    }

    @Test
    fun `computeNextMonthStart refuses to advance into a future month`() {
        val today = LocalDate.of(2026, 8, 15)
        val currentMonth = today.withDayOfMonth(1)
        assertEquals(currentMonth, computeNextMonthStart(currentMonth, today))
    }

    @Test
    fun `computeNextMonthStart advances when the next month is not in the future`() {
        val today = LocalDate.of(2026, 8, 15)
        val lastMonth = today.withDayOfMonth(1).minusMonths(1)
        assertEquals(today.withDayOfMonth(1), computeNextMonthStart(lastMonth, today))
    }
}
