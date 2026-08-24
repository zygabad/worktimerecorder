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

    var lastWidgetTapTime: Long
        get() = prefs.getLong("last_widget_tap_time", -1L)
        set(value) = prefs.edit { putLong("last_widget_tap_time", value) }

    // Widget background color thresholds, all in minutes.
    var yellowThresholdMinutes: Int
        get() = prefs.getInt("yellow_threshold_minutes", 90)
        set(value) = prefs.edit { putInt("yellow_threshold_minutes", value) }

    var orangeThresholdMinutes: Int
        get() = prefs.getInt("orange_threshold_minutes", 8 * 60)
        set(value) = prefs.edit { putInt("orange_threshold_minutes", value) }

    var redThresholdMinutes: Int
        get() = prefs.getInt("red_threshold_minutes", 8 * 60 + 30)
        set(value) = prefs.edit { putInt("red_threshold_minutes", value) }
}
