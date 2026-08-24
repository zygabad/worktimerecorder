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
import com.zygabad.worktimerecorder.util.formatMinutes
import com.zygabad.worktimerecorder.util.formatSeconds
import com.zygabad.worktimerecorder.util.getDayMinutes
import com.zygabad.worktimerecorder.util.getMonthLabel
import com.zygabad.worktimerecorder.util.getTodayTotalMinutes
import com.zygabad.worktimerecorder.util.getWeekLabel
import com.zygabad.worktimerecorder.util.sessionMinutes
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

    /**
     * Source of truth for "is work in progress", read live from Room instead of cached in a
     * ViewModel-owned StateFlow. The old design (isWorking/sessionStartTime as MutableStateFlow,
     * set once at construction and only updated inside this ViewModel's own toggle/edit methods)
     * went stale whenever the widget's ToggleTimerAction changed prefs+DB from outside — e.g.
     * background the app, toggle from the widget, reopen the app: the same MainViewModel
     * instance was still alive with its old cached isWorking=false, showing a frozen "0:00"
     * even though the session table (already backed by a live Flow) correctly showed a new
     * running session. Deriving from the DB directly means there is nothing to go out of sync.
     */
    val openSession: StateFlow<WorkSession?> = repo.observeOpenSession()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isWorking: StateFlow<Boolean> = openSession
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefs.isWorking)

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
                elapsedSeconds.value = calcElapsed()
                delay(1_000)
            }
        }
    }

    private fun calcElapsed(): Long {
        val start = openSession.value?.startTime
        return if (start != null) (System.currentTimeMillis() - start) / 1_000 else 0L
    }

    fun toggleWork() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.toggleWork(prefs) }
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
            WorkTimerGlanceWidget().updateAll(getApplication<Application>())
        }
    }

    fun updateSessionEnd(session: WorkSession, hour: Int, minute: Int) {
        viewModelScope.launch {
            val newEnd = combineDateTime(session.date, hour, minute)
            withContext(Dispatchers.IO) { repo.updateSessionTimes(session, session.startTime, newEnd) }
            if (session.endTime == null) {
                val app = getApplication<Application>()
                app.stopService(Intent(app, WorkTimerService::class.java))
            }
            WorkTimerGlanceWidget().updateAll(getApplication<Application>())
        }
    }

    fun previousWeek() { _weekStart.value = _weekStart.value.minusWeeks(1) }

    fun nextWeek() { _weekStart.value = computeNextWeekStart(_weekStart.value) }

    fun getTodayTotalMinutes(sessions: List<WorkSession>, isWorking: Boolean, elapsedSeconds: Long): Int =
        com.zygabad.worktimerecorder.util.getTodayTotalMinutes(sessions, isWorking, elapsedSeconds)

    fun getDayMinutes(date: String, sessions: List<WorkSession>): Int =
        com.zygabad.worktimerecorder.util.getDayMinutes(date, sessions, System.currentTimeMillis())

    fun sessionMinutes(session: WorkSession): Int =
        com.zygabad.worktimerecorder.util.sessionMinutes(session, System.currentTimeMillis())

    fun formatMinutes(min: Int): String = com.zygabad.worktimerecorder.util.formatMinutes(min)

    fun formatSeconds(sec: Long): String = com.zygabad.worktimerecorder.util.formatSeconds(sec)

    fun getWeekLabel(weekStart: LocalDate): String = com.zygabad.worktimerecorder.util.getWeekLabel(weekStart)

    fun previousMonth() { _monthStart.value = _monthStart.value.minusMonths(1) }

    fun nextMonth() { _monthStart.value = computeNextMonthStart(_monthStart.value) }

    fun getMonthLabel(monthStart: LocalDate): String = com.zygabad.worktimerecorder.util.getMonthLabel(monthStart)
}
