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
import com.zygabad.worktimerecorder.widget.WorkTimerGlanceWidget
import androidx.glance.appwidget.updateAll
import com.zygabad.worktimerecorder.util.combineDateTime
import com.zygabad.worktimerecorder.util.computeNextMonthStart
import com.zygabad.worktimerecorder.util.computeNextWeekStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as WorkTimerApplication).database
    private val repo = WorkRepository(db.workDao())
    val prefs = PrefsManager(app)

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
            WorkTimerGlanceWidget().updateAll(app)
        }
    }

    fun deleteSession(session: WorkSession) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteSession(session) }
            if (session.endTime == null) {
                // The row backing the currently-tracked session is gone — stop tracking it too.
                prefs.isWorking = false
                prefs.currentSessionStart = -1L
                prefs.currentSessionId = -1L
                isWorking.value = false
                sessionStartTime.value = -1L
                elapsedSeconds.value = 0L
                val app = getApplication<Application>()
                app.stopService(Intent(app, WorkTimerService::class.java))
                WorkTimerGlanceWidget().updateAll(app)
            }
        }
    }

    fun updateSessionStart(session: WorkSession, hour: Int, minute: Int) {
        viewModelScope.launch {
            val newStart = combineDateTime(session.date, hour, minute)
            withContext(Dispatchers.IO) { repo.updateSessionTimes(session, newStart, session.endTime) }
            if (session.endTime == null) {
                // There is only ever one open session — this IS the live one regardless of
                // whether prefs.currentSessionId already agreed, so resync unconditionally.
                // Previously this only updated the on-screen elapsed counter when the id
                // already matched prefs; any mismatch left the counter frozen at the old value.
                prefs.isWorking = true
                prefs.currentSessionId = session.id.toLong()
                prefs.currentSessionStart = newStart
                isWorking.value = true
                sessionStartTime.value = newStart
                elapsedSeconds.value = calcElapsed()
            }
            WorkTimerGlanceWidget().updateAll(getApplication<Application>())
        }
    }

    fun updateSessionEnd(session: WorkSession, hour: Int, minute: Int) {
        viewModelScope.launch {
            val newEnd = combineDateTime(session.date, hour, minute)
            withContext(Dispatchers.IO) { repo.updateSessionTimes(session, session.startTime, newEnd) }
            if (session.endTime == null) {
                // Giving the (only) open session an end time closes it — stop tracking/counting it.
                prefs.isWorking = false
                prefs.currentSessionStart = -1L
                prefs.currentSessionId = -1L
                isWorking.value = false
                sessionStartTime.value = -1L
                elapsedSeconds.value = 0L
                val app = getApplication<Application>()
                app.stopService(Intent(app, WorkTimerService::class.java))
            }
            WorkTimerGlanceWidget().updateAll(getApplication<Application>())
        }
    }

    fun previousWeek() { _weekStart.value = _weekStart.value.minusWeeks(1) }

    fun nextWeek() { _weekStart.value = computeNextWeekStart(_weekStart.value) }

    fun getTodayTotalMinutes(sessions: List<WorkSession>): Int =
        com.zygabad.worktimerecorder.util.getTodayTotalMinutes(sessions, isWorking.value, elapsedSeconds.value)

    fun getDayMinutes(date: String, sessions: List<WorkSession>): Int =
        com.zygabad.worktimerecorder.util.getDayMinutes(date, sessions)

    fun sessionMinutes(session: WorkSession): Int =
        com.zygabad.worktimerecorder.util.sessionMinutes(session, System.currentTimeMillis())

    fun formatMinutes(min: Int): String = com.zygabad.worktimerecorder.util.formatMinutes(min)

    fun formatSeconds(sec: Long): String = com.zygabad.worktimerecorder.util.formatSeconds(sec)

    fun getWeekLabel(weekStart: LocalDate): String = com.zygabad.worktimerecorder.util.getWeekLabel(weekStart)

    fun previousMonth() { _monthStart.value = _monthStart.value.minusMonths(1) }

    fun nextMonth() { _monthStart.value = computeNextMonthStart(_monthStart.value) }

    fun getMonthLabel(monthStart: LocalDate): String = com.zygabad.worktimerecorder.util.getMonthLabel(monthStart)
}
