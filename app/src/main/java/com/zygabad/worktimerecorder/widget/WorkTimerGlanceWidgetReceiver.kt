package com.zygabad.worktimerecorder.widget

import androidx.glance.appwidget.GlanceAppWidgetReceiver

class WorkTimerGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = WorkTimerGlanceWidget()
}
