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

class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = PrefsManager(context)

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
