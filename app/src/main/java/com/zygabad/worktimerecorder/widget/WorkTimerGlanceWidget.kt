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
import com.zygabad.worktimerecorder.repository.WorkRepository
import com.zygabad.worktimerecorder.util.formatMinutes

/**
 * Uses the default SizeMode.Single. Always reads today's cumulative total via
 * WorkRepository.getTodayMinutes() — the same single source of truth the app screen uses (see
 * technical lesson #96/#97) — rather than the current session's own elapsed time alone, which
 * used to make "do końca" wildly overstate remaining time after a break (ignoring sessions
 * already closed today). A prior bigger-widget variant that queried the DB unconditionally was
 * suspected (wrongly, in hindsight — a reported lag persisted even after removing it) of causing
 * a ~1 minute lag before the widget visually reflected a toggle, but a plain DB read here is not
 * that variant and is required for correctness.
 */
class WorkTimerGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = PrefsManager(context)
        val repo = WorkRepository(WorkDatabase.getDatabase(context).workDao())
        val todayMinutes = repo.getTodayMinutes()
        provideContent { WidgetContent(prefs, todayMinutes) }
    }
}

@Composable
private fun WidgetContent(prefs: PrefsManager, todayMinutes: Int) {
    val isWorking = prefs.isWorking
    val targetMinutes = prefs.targetWorkMinutes
    val remainingMs = (targetMinutes - todayMinutes).coerceAtLeast(0) * 60_000L
    val overtimeMs = (todayMinutes - targetMinutes).coerceAtLeast(0) * 60_000L
    val isOvertime = isWorking && todayMinutes >= targetMinutes

    val bgColor = when {
        !isWorking -> Color(0xFF263238) // gray — stopped, no color-coded urgency
        todayMinutes >= prefs.redThresholdMinutes -> Color(0xFFC62828) // red — past configured threshold
        todayMinutes >= prefs.orangeThresholdMinutes -> Color(0xFFEF6C00) // orange — past configured threshold
        todayMinutes >= prefs.yellowThresholdMinutes -> Color(0xFFF9A825) // yellow — past configured threshold
        todayMinutes >= prefs.blueThresholdMinutes -> Color(0xFF1565C0) // blue — configured halfway-ish threshold
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
                    text = if (isOvertime) "+${formatRemaining(overtimeMs)}" else formatRemaining(remainingMs),
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
