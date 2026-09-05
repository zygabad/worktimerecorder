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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zygabad.worktimerecorder.data.PrefsManager
import com.zygabad.worktimerecorder.data.WorkDatabase
import com.zygabad.worktimerecorder.util.formatMinutes
import com.zygabad.worktimerecorder.util.getDayMinutes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Uses the default SizeMode.Single. Queries the DB for today's total only while stopped (to show
 * "worked so far today"); while working, no DB read is needed since the live counter comes from
 * PrefsManager alone — kept minimal since a prior bigger-widget variant that queried the DB
 * unconditionally was suspected (wrongly, in hindsight — the delay persisted even after removing
 * it) of causing a ~1 minute lag before the widget visually reflected a toggle.
 */
class WorkTimerGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = PrefsManager(context)
        val todayMinutes = if (!prefs.isWorking) {
            val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val today = LocalDate.now().format(fmt)
            val sessions = WorkDatabase.getDatabase(context).workDao().getSessionsForDateOnce(today)
            getDayMinutes(today, sessions)
        } else 0
        provideContent { WidgetContent(prefs, todayMinutes) }
    }
}

@Composable
private fun WidgetContent(prefs: PrefsManager, todayMinutes: Int) {
    val isWorking = prefs.isWorking
    val startTime = prefs.currentSessionStart
    val targetMs = prefs.targetWorkMinutes * 60_000L
    val elapsedMs = if (isWorking && startTime > 0) System.currentTimeMillis() - startTime else 0L
    val remainingMs = targetMs - elapsedMs
    val isOvertime = isWorking && remainingMs <= 0

    val elapsedMinutes = (elapsedMs / 60_000).toInt()
    val bgColor = when {
        !isWorking -> Color(0xFF263238) // gray — stopped, no color-coded urgency
        elapsedMinutes >= prefs.redThresholdMinutes -> Color(0xFFC62828) // red — past configured threshold
        elapsedMinutes >= prefs.orangeThresholdMinutes -> Color(0xFFEF6C00) // orange — past configured threshold
        elapsedMinutes >= prefs.yellowThresholdMinutes -> Color(0xFFF9A825) // yellow — past configured threshold
        elapsedMinutes >= prefs.blueThresholdMinutes -> Color(0xFF1565C0) // blue — configured halfway-ish threshold
        else -> Color(0xFF1B5E20) // green — normal
    }

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
                    text = if (isOvertime) "+${formatRemaining(-remainingMs)}" else formatRemaining(remainingMs),
                    style = TextStyle(
                        color = if (isOvertime) ColorProvider(Color(0xFFFFB74D)) else ColorProvider(Color.White),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Text(
                    text = if (isOvertime) "nadgodziny" else "do końca",
                    style = TextStyle(color = ColorProvider(Color(0xFF90A4AE)), fontSize = 9.sp)
                )
            } else {
                Text(
                    text = formatMinutes(todayMinutes),
                    style = TextStyle(
                        color = ColorProvider(Color(0xFFB0BEC5)),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
                Text(
                    text = "dziś",
                    style = TextStyle(color = ColorProvider(Color(0xFF78909C)), fontSize = 9.sp)
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
