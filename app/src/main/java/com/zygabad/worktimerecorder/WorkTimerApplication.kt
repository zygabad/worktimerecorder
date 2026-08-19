package com.zygabad.worktimerecorder

import android.app.Application
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.repository.WorkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WorkTimerApplication : Application() {
    val database: WorkDatabase by lazy { WorkDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            WorkRepository(database.workDao()).cleanupCorruptedSessions(PrefsManager(this@WorkTimerApplication))
        }
    }
}
