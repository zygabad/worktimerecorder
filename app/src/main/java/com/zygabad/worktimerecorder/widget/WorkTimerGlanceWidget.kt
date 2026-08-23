package com.zygabad.worktimerecorder.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val COMPACT_SIZE = DpSize(40.dp, 40.dp)
private val WIDE_SIZE = DpSize(110.dp, 40.dp)
private val TALL_SIZE = DpSize(110.dp, 110.dp)

class WorkTimerGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT_SIZE, WIDE_SIZE, TALL_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = PrefsManager(context)
        val todayStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val todaySessions = WorkDatabase.getDatabase(context).workDao().getSessionsForDateOnce(todayStr)
        val doneMinutes = todaySessions.filter { it.endTime != null }.sumOf { it.durationMinutes }
        val liveMinutes = if (prefs.isWorking && prefs.currentSessionStart > 0)
            ((System.currentTimeMillis() - prefs.currentSessionStart) / 60_000).toInt() else 0
        val todayMinutes = doneMinutes + liveMinutes

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

    val bgColor = if (isWorking) Color(0xFF1B5E20) else Color(0xFF263238)
    val isWide = LocalSize.current.width >= WIDE_SIZE.width

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bgColor))
            .clickable(actionRunCallback<ToggleTimerAction>()),
        contentAlignment = Alignment.Center
    ) {
        if (isWide) {
            Row(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CountdownColumn(isWorking, isOvertime, remainingMs, modifier = GlanceModifier.defaultWeight())
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    Text(
                        text = "Dziś",
                        style = TextStyle(color = ColorProvider(Color(0xFF90A4AE)), fontSize = 9.sp)
                    )
                    Text(
                        text = formatMinutes(todayMinutes),
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Text(
                        text = "cel ${formatMinutes(prefs.targetWorkMinutes)}",
                        style = TextStyle(color = ColorProvider(Color(0xFF90A4AE)), fontSize = 8.sp)
                    )
                }
            }
        } else {
            CountdownColumn(isWorking, isOvertime, remainingMs)
        }
    }
}

@Composable
private fun CountdownColumn(
    isWorking: Boolean,
    isOvertime: Boolean,
    remainingMs: Long,
    modifier: GlanceModifier = GlanceModifier
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
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

private fun formatRemaining(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}" else "${m}m"
}
