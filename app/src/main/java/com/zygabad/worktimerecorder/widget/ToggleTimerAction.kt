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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val DOUBLE_TAP_WINDOW_MS = 600L

class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = PrefsManager(context)

        if (prefs.requireDoubleTap) {
            val now = System.currentTimeMillis()
            val sinceLastTap = now - prefs.lastWidgetTapTime
            prefs.lastWidgetTapTime = now
            if (sinceLastTap > DOUBLE_TAP_WINDOW_MS) {
                // First tap of a pair — just record it and wait for the confirming second tap.
                return
            }
            prefs.lastWidgetTapTime = -1L // consumed, so a stray third tap doesn't chain into a new pair
        }

        withContext(Dispatchers.IO) {
            val repo = WorkRepository(WorkDatabase.getDatabase(context).workDao())
            repo.toggleWork(prefs)
        }

        try {
            if (prefs.isWorking) {
                context.startForegroundService(Intent(context, WorkTimerService::class.java))
            } else {
                context.stopService(Intent(context, WorkTimerService::class.java))
            }
        } catch (_: Exception) {}

        WorkTimerGlanceWidget().updateAll(context)
    }
}
