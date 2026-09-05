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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val DOUBLE_TAP_WINDOW_MS = 1000L

/**
 * Single tap opens the app; only a confirmed double tap starts/stops work — a lone tap has no
 * side effect besides opening the app, so there's no way to accidentally toggle the timer.
 *
 * Each tap is its own ActionCallback invocation, so distinguishing "this is a genuine single tap"
 * from "this is the first half of a double tap" is done by waiting out the window: if a confirming
 * second tap hasn't shown up by the time this tap's own delay elapses, prefs.lastWidgetTapTime
 * still equals the timestamp this call itself recorded (nothing else touched it), so it opens the
 * app. If a second tap DID arrive in the meantime, that second call's own check sees itself within
 * the window, toggles, and consumes the timestamp (-1L) — so the first call's delayed check no
 * longer matches and it correctly does nothing.
 *
 * The read-then-write of lastWidgetTapTime that decides this must be atomic: two rapid taps launch
 * two independent coroutines, and without a lock both can read the old timestamp before either
 * writes the new one. tapMutex serializes just that decision (a JVM-wide singleton via companion
 * object, matching the same-process scope of the bug).
 *
 * onAction itself must return immediately after that decision — it must NOT suspend on the
 * "maybe single tap" delay before returning. Observed symptom that led here: double-tapping opened
 * the app (the single-tap outcome) but never toggled the timer, which only makes sense if the
 * widget host processes one click's ActionCallback to completion before starting the next one — in
 * that case the first tap's own delay(1000ms) blocks its onAction from returning, so the second
 * tap's onAction can't even begin until the window has already elapsed, and it's then treated as a
 * fresh, unrelated single tap. Running the delayed "open app" check on a detached scope instead
 * lets onAction return right away, so a genuinely fast second tap gets a chance to run within the
 * window regardless of how the host schedules callback execution.
 */
class ToggleTimerAction : ActionCallback {
    companion object {
        private val tapMutex = Mutex()
        private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val prefs = PrefsManager(context)
        val now = System.currentTimeMillis()

        val isConfirmedDoubleTap = tapMutex.withLock {
            val sinceLastTap = now - prefs.lastWidgetTapTime
            if (sinceLastTap in 0..DOUBLE_TAP_WINDOW_MS) {
                // Confirmed second tap — consume the pending timestamp so the first tap's
                // delayed check (below) sees it changed and does nothing.
                prefs.lastWidgetTapTime = -1L
                true
            } else {
                prefs.lastWidgetTapTime = now
                false
            }
        }

        if (isConfirmedDoubleTap) {
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

        // Possibly a lone single tap — wait out the window before deciding, but don't make this
        // invocation's own return wait on it (see class doc: a blocking delay here would risk
        // starving a fast follow-up tap of its own chance to run within the window).
        actionScope.launch {
            delay(DOUBLE_TAP_WINDOW_MS)
            if (prefs.lastWidgetTapTime == now) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                launchIntent?.let { context.startActivity(it) }
            }
        }
    }
}
