package com.zygabad.worktimerecorder

import android.app.Application
import com.zygabad.worktimerecorder.data.WorkDatabase

class WorkTimerApplication : Application() {
    val database: WorkDatabase by lazy { WorkDatabase.getDatabase(this) }
}
