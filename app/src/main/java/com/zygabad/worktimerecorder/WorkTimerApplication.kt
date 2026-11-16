package com.zygabad.worktimerecorder

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.garmin.GarminBridge
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.util.WatchPushReason
import com.zygabad.worktimerecorder.widget.WorkTimerGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WorkTimerApplication : Application() {
    val database: WorkDatabase by lazy { WorkDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val changed = WorkRepository(database.workDao())
                .cleanupCorruptedSessions(PrefsManager(this@WorkTimerApplication))
            WorkTimerGlanceWidget().updateAll(this@WorkTimerApplication)
            // Push ONLY if the self-heal actually changed something (e.g. it auto-stopped a
            // session forgotten while the service was dead). This used to push on every cold
            // process start — which Android does at night for the widget — i.e. with no state
            // change at all. See GarminBridge's KDoc, point 6.
            if (changed) {
                GarminBridge.pushCurrentState(this@WorkTimerApplication, WatchPushReason.SELF_HEAL)
            }
        }
    }
}
