package com.zygabad.worktimerecorder.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.updateAll
import com.zygabad.worktimerecorder.MainActivity
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.garmin.GarminBridge
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.util.WatchPushReason
import com.zygabad.worktimerecorder.widget.WorkTimerGlanceWidget
import kotlinx.coroutines.*

class WorkTimerService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs by lazy { PrefsManager(this) }
    private val repo by lazy { WorkRepository(WorkDatabase.getDatabase(this).workDao()) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Czas pracy jest liczony..."))
        // Redundant safety-net refresh: the loop below only refreshes the widget every 60s, and
        // its FIRST refresh only fires after the first delay(60_000) — so if the widget's own
        // updateAll() from ToggleTimerAction's tap handler doesn't visually land for any reason,
        // the widget picture was stuck showing the old state for up to a full minute with no
        // faster fallback. This makes the fallback immediate instead of 60s-delayed.
        scope.launch { WorkTimerGlanceWidget().updateAll(this@WorkTimerService) }
        // No watch push here (removed in v1.30). onStartCommand also runs when the system
        // restarts this START_STICKY service after killing it — no state change at all. Every
        // path that actually starts the timer (MainViewModel.toggleWork, the widget's
        // ToggleTimerAction) already pushes by itself. See GarminBridge's KDoc, point 6.
        scope.launch {
            while (isActive) {
                delay(60_000)

                if (repo.autoStopIfStale(prefs)) {
                    WorkTimerGlanceWidget().updateAll(this@WorkTimerService)
                    // The 20h safety net stopped the timer without any user action — the watch
                    // would otherwise keep counting down a session that no longer exists.
                    GarminBridge.pushCurrentState(this@WorkTimerService, WatchPushReason.AUTO_STOP)
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_ID, buildNotification("Zatrzymano automatycznie po 20h — zapomniałeś wyłączyć?"))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                    return@launch
                }

                val target = prefs.targetWorkMinutes
                val todayMinutes = repo.getTodayMinutes()
                val remaining = (target - todayMinutes).coerceAtLeast(0)
                val h = remaining / 60; val m = remaining % 60
                val text = if (remaining > 0) "Pozostało: ${h}h ${m.toString().padStart(2, '0')}m"
                           else "Cel osiągnięty! 🎉"
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, buildNotification(text))
                WorkTimerGlanceWidget().updateAll(this@WorkTimerService)
                // No periodic watch push (the 15-minute re-push was removed in v1.30): the watch
                // counts down on its own clock, and the phone now pushes only when something
                // changes. A watch that was out of range catches up on the next change or via
                // the manual button in Ustawienia. See GarminBridge's KDoc, point 6.
            }
        }
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Work Timer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Work Timer", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Aktywna sesja pracy"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "work_timer_channel"
    }
}
