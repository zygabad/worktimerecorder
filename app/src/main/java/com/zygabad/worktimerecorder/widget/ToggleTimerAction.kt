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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val DOUBLE_TAP_WINDOW_MS = 1000L

/**
 * Single tap opens the app; only a confirmed double tap starts/stops work — a lone tap has no
 * side effect besides opening the app, so there's no way to accidentally toggle the timer.
 *
 * Each tap is its own ActionCallback invocation, so distinguishing "this is a genuine single tap"
 * from "this is the first half of a double tap" can only be done by waiting out the window: if a
 * confirming second tap hasn't shown up by the time this tap's own delay elapses, prefs.lastWidgetTapTime
 * still equals the timestamp this call itself recorded (nothing else touched it), so it opens the
 * app. If a second tap DID arrive in the meantime, that second call's own check sees itself within
 * the window, toggles, and consumes the timestamp (-1L) — so the first call's delayed check no
 * longer matches and it correctly does nothing.
 */
class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = PrefsManager(context)
        val now = System.currentTimeMillis()
        val sinceLastTap = now - prefs.lastWidgetTapTime
        prefs.lastWidgetTapTime = now

        if (sinceLastTap <= DOUBLE_TAP_WINDOW_MS) {
            // Confirmed second tap — toggle work, consume the pending timestamp.
            prefs.lastWidgetTapTime = -1L

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
            return
        }

        // Possibly a lone single tap — wait out the window before deciding.
        delay(DOUBLE_TAP_WINDOW_MS)
        if (prefs.lastWidgetTapTime == now) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            launchIntent?.let { context.startActivity(it) }
        }
    }
}
