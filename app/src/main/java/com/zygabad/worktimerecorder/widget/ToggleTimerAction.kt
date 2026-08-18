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
        val repo = WorkRepository(WorkDatabase.getDatabase(context).workDao())

        withContext(Dispatchers.IO) {
            if (prefs.isWorking) {
                val id = prefs.currentSessionId
                val start = prefs.currentSessionStart
                if (id > 0 && start > 0) repo.stopSession(id, start)
                prefs.isWorking = false
                prefs.currentSessionStart = -1L
                prefs.currentSessionId = -1L
                context.stopService(Intent(context, WorkTimerService::class.java))
            } else {
                val id = repo.startSession()
                prefs.isWorking = true
                prefs.currentSessionStart = System.currentTimeMillis()
                prefs.currentSessionId = id
                context.startService(Intent(context, WorkTimerService::class.java))
            }
        }

        WorkTimerGlanceWidget().updateAll(context)
    }
}
