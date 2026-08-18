package com.zygabad.worktimerecorder.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.service.WorkTimerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = PrefsManager(context)

        withContext(Dispatchers.IO) {
            val repo = WorkRepository(WorkDatabase.getDatabase(context).workDao())

            // Flip the flag the widget renders FIRST. Everything after this is
            // best-effort persistence/service work and must never revert it —
            // a swallowed exception here used to flip isWorking back to false
            // even after the foreground service had already started, leaving
            // the widget stuck showing "START" while a session was running.
            if (prefs.isWorking) {
                val id = prefs.currentSessionId
                val start = prefs.currentSessionStart
                prefs.isWorking = false
                prefs.currentSessionStart = -1L
                prefs.currentSessionId = -1L
                try {
                    context.stopService(Intent(context, WorkTimerService::class.java))
                } catch (_: Exception) {}
                try {
                    if (id > 0 && start > 0) repo.stopSession(id, start) else Unit
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Session end time won't be logged, but widget/service state is already correct.
                }
            } else {
                prefs.isWorking = true
                prefs.currentSessionStart = System.currentTimeMillis()
                try {
                    prefs.currentSessionId = repo.startSession()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No DB row for this session, but timer/widget/service still start correctly.
                }
                try {
                    context.startForegroundService(Intent(context, WorkTimerService::class.java))
                } catch (_: Exception) {}
            }
        }

        WorkTimerGlanceWidget().updateAll(context)
    }
}
