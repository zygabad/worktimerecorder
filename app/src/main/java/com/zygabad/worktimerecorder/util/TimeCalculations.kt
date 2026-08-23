package com.zygabad.worktimerecorder.util

import com.zygabad.worktimerecorder.data.WorkSession
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Pure, Android-free versions of the calculation/formatting logic used by MainViewModel.
 * Extracted so they can be unit-tested on the plain JVM without an Application/Context
 * (AndroidViewModel + viewModelScope aren't practical to instantiate in a JVM unit test —
 * see app/src/test/.../MainViewModelLogicTest.kt for why). MainViewModel delegates to these
 * rather than duplicating the logic, so a test here actually exercises what runs in the app.
 */

/** Regression guard for the historical bug where "8h 33m" rendered as "8h" via integer division. */
fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return "${h}h ${m.toString().padStart(2, '0')}m"
}

fun formatSeconds(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m}:${s.toString().padStart(2, '0')}"
}

/**
 * Prefers the persisted durationMinutes (set once a session closes); falls back to a live
 * recompute from start/end (or start/now for an still-open session) only when duration hasn't
 * been recorded yet.
 */
fun sessionMinutes(session: WorkSession, nowMillis: Long = System.currentTimeMillis()): Int =
    session.durationMinutes.takeIf { it > 0 }
        ?: (((session.endTime ?: nowMillis) - session.startTime) / 60_000).toInt()

fun getDayMinutes(date: String, sessions: List<WorkSession>, nowMillis: Long = System.currentTimeMillis()): Int =
    sessions.filter { it.date == date }.sumOf { sessionMinutes(it, nowMillis) }

fun getTodayTotalMinutes(sessions: List<WorkSession>, isWorking: Boolean, elapsedSeconds: Long): Int {
    val done = sessions.filter { it.endTime != null }.sumOf { it.durationMinutes }
    val ongoing = if (isWorking) (elapsedSeconds / 60).toInt() else 0
    return done + ongoing
}

fun combineDateTime(dateStr: String, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    return LocalDate.parse(dateStr, fmt).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}

/** Mirrors MainViewModel.nextWeek(): never navigate into a future week. */
fun computeNextWeekStart(current: LocalDate, today: LocalDate = LocalDate.now()): LocalDate {
    val next = current.plusWeeks(1)
    val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return if (!next.isAfter(thisWeek)) next else current
}

/** Mirrors MainViewModel.nextMonth(): never navigate into a future month. */
fun computeNextMonthStart(current: LocalDate, today: LocalDate = LocalDate.now()): LocalDate {
    val next = current.plusMonths(1)
    val thisMonth = today.withDayOfMonth(1)
    return if (!next.isAfter(thisMonth)) next else current
}

fun getWeekLabel(weekStart: LocalDate): String {
    val end = weekStart.plusDays(6)
    val fmtShort = DateTimeFormatter.ofPattern("d MMM", Locale("pl"))
    return "${weekStart.format(fmtShort)} – ${end.format(fmtShort)}"
}

fun getMonthLabel(monthStart: LocalDate): String {
    val fmtMonth = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("pl"))
    return monthStart.format(fmtMonth)
}
