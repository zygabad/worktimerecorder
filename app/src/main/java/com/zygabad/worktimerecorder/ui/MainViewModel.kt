package com.zygabad.worktimerecorder.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zygabad.worktimerecorder.WorkTimerApplication
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkSession
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.service.WorkTimerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as WorkTimerApplication).database
    private val repo = WorkRepository(db.workDao())
    val prefs = PrefsManager(app)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    val isWorking = MutableStateFlow(prefs.isWorking)
    val sessionStartTime = MutableStateFlow(prefs.currentSessionStart)
    val elapsedSeconds = MutableStateFlow(calcElapsed())

    private val _weekStart = MutableStateFlow(
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    )
    val weekStart: StateFlow<LocalDate> = _weekStart

    private val _monthStart = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    val monthStart: StateFlow<LocalDate> = _monthStart

    val todaySessions = repo.getTodaySessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val weekSessions: StateFlow<List<WorkSession>> = _weekStart.flatMapLatest {
        repo.getWeekSessions(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val monthSessions: StateFlow<List<WorkSession>> = _monthStart.flatMapLatest {
        repo.getMonthSessions(it)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            while (true) {
                if (isWorking.value) elapsedSeconds.value = calcElapsed()
                delay(1_000)
            }
        }
    }

    private fun calcElapsed(): Long {
        val start = prefs.currentSessionStart
        return if (prefs.isWorking && start > 0) (System.currentTimeMillis() - start) / 1_000 else 0L
    }

    fun toggleWork() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.toggleWork(prefs) }
            isWorking.value = prefs.isWorking
            sessionStartTime.value = prefs.currentSessionStart
            elapsedSeconds.value = calcElapsed()
            val app = getApplication<Application>()
            if (prefs.isWorking) {
                app.startService(Intent(app, WorkTimerService::class.java))
            } else {
                app.stopService(Intent(app, WorkTimerService::class.java))
            }
        }
    }

    fun previousWeek() { _weekStart.value = _weekStart.value.minusWeeks(1) }

    fun nextWeek() {
        val next = _weekStart.value.plusWeeks(1)
        val thisWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (!next.isAfter(thisWeek)) _weekStart.value = next
    }

    fun getTodayTotalMinutes(sessions: List<WorkSession>): Int {
        val done = sessions.filter { it.endTime != null }.sumOf { it.durationMinutes }
        val ongoing = if (isWorking.value) (elapsedSeconds.value / 60).toInt() else 0
        return done + ongoing
    }

    fun getDayMinutes(date: String, sessions: List<WorkSession>): Int =
        sessions.filter { it.date == date }.sumOf { sessionMinutes(it) }

    fun sessionMinutes(session: WorkSession): Int =
        session.durationMinutes.takeIf { it > 0 }
            ?: (((session.endTime ?: System.currentTimeMillis()) - session.startTime) / 60_000).toInt()

    fun formatMinutes(min: Int): String {
        val h = min / 60; val m = min % 60
        return "${h}h ${m.toString().padStart(2, '0')}m"
    }

    fun formatSeconds(sec: Long): String {
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
        else "${m}:${s.toString().padStart(2, '0')}"
    }

    fun getWeekLabel(weekStart: LocalDate): String {
        val end = weekStart.plusDays(6)
        val fmtShort = DateTimeFormatter.ofPattern("d MMM", Locale("pl"))
        return "${weekStart.format(fmtShort)} – ${end.format(fmtShort)}"
    }

    fun previousMonth() { _monthStart.value = _monthStart.value.minusMonths(1) }

    fun nextMonth() {
        val next = _monthStart.value.plusMonths(1)
        val thisMonth = LocalDate.now().withDayOfMonth(1)
        if (!next.isAfter(thisMonth)) _monthStart.value = next
    }

    fun getMonthLabel(monthStart: LocalDate): String {
        val fmtMonth = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("pl"))
        return monthStart.format(fmtMonth)
    }
}
