package com.zygabad.worktimerecorder.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zygabad.worktimerecorder.data.PrefsManager

class WorkTimerGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = PrefsManager(context)
        provideContent { WidgetContent(prefs) }
    }
}

@Composable
private fun WidgetContent(prefs: PrefsManager) {
    val isWorking = prefs.isWorking
    val startTime = prefs.currentSessionStart
    val targetMs = prefs.targetWorkMinutes * 60_000L
    val elapsedMs = if (isWorking && startTime > 0) System.currentTimeMillis() - startTime else 0L
    val remainingMs = targetMs - elapsedMs

    val bgColor = if (isWorking) Color(0xFF1B5E20) else Color(0xFF263238)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bgColor))
            .clickable(actionRunCallback<ToggleTimerAction>()),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (isWorking) {
                Text(
                    text = if (remainingMs > 0) formatRemaining(remainingMs) else "DONE!",
                    style = TextStyle(
                        color = if (remainingMs > 0) ColorProvider(Color.White)
                        else ColorProvider(Color(0xFF66BB6A)),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = "do końca",
                    style = TextStyle(color = ColorProvider(Color(0xFF90A4AE)), fontSize = 9.sp)
                )
            } else {
                Text(
                    text = "▶",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 22.sp)
                )
                Text(
                    text = "START",
                    style = TextStyle(color = ColorProvider(Color(0xFF90A4AE)), fontSize = 9.sp)
                )
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}" else "${m}m"
}
