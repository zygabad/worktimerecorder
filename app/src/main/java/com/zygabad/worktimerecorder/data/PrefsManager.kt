package com.zygabad.worktimerecorder.data

import android.content.Context
import androidx.core.content.edit

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("work_timer_prefs", Context.MODE_PRIVATE)

    // 8h 30min = 510 min default
    var targetWorkMinutes: Int
        get() = prefs.getInt("target_work_minutes", 510)
        set(value) = prefs.edit { putInt("target_work_minutes", value) }

    var isWorking: Boolean
        get() = prefs.getBoolean("is_working", false)
        set(value) = prefs.edit { putBoolean("is_working", value) }

    var currentSessionStart: Long
        get() = prefs.getLong("session_start", -1L)
        set(value) = prefs.edit { putLong("session_start", value) }

    var currentSessionId: Long
        get() = prefs.getLong("session_id", -1L)
        set(value) = prefs.edit { putLong("session_id", value) }
}
