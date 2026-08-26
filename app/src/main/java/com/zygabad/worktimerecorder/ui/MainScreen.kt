package com.zygabad.worktimerecorder.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.zygabad.worktimerecorder.data.WorkSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun formatClock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFmt)

private data class EditingTime(val session: WorkSession, val isStart: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTimeDialog(initialEpochMs: Long?, onConfirm: (hour: Int, minute: Int) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val initial = initialEpochMs?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: java.time.ZonedDateTime.now(zone)
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wybierz godzinę") },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(navController: NavController, vm: MainViewModel = viewModel()) {
    val isWorking by vm.isWorking.collectAsState()
    val elapsed by vm.elapsedSeconds.collectAsState()
    val todaySessions by vm.todaySessions.collectAsState()
    val weekSessions by vm.weekSessions.collectAsState()
    val weekStart by vm.weekStart.collectAsState()
    val monthSessions by vm.monthSessions.collectAsState()
    val monthStart by vm.monthStart.collectAsState()
    val todayMinutes = vm.getTodayTotalMinutes(todaySessions, isWorking, elapsed)
    val targetMinutes = vm.prefs.targetWorkMinutes
    val isOvertime = todayMinutes > targetMinutes
    val remainingMinutes = (targetMinutes - todayMinutes).coerceAtLeast(0)
    val overtimeMinutes = (todayMinutes - targetMinutes).coerceAtLeast(0)

    // Same green/yellow/orange/red thresholds as the widget, applied to today's total instead of
    // just the current session so a second session picks up where the first left off.
    val (statusContainer, statusContent) = when {
        !isWorking -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        todayMinutes >= vm.prefs.redThresholdMinutes -> Color(0xFFC62828) to Color.White
        todayMinutes >= vm.prefs.orangeThresholdMinutes -> Color(0xFFEF6C00) to Color.White
        remainingMinutes < vm.prefs.yellowThresholdMinutes -> Color(0xFFF9A825) to Color(0xFF3E2E00)
        todayMinutes >= vm.prefs.blueThresholdMinutes -> Color(0xFF1565C0) to Color.White
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
    }
    val statusContentMuted = statusContent.copy(alpha = 0.85f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Work Timer", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Ustawienia")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = statusContainer,
                        contentColor = statusContent
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isWorking) "Pracujesz" else "Wolny",
                            style = MaterialTheme.typography.labelLarge,
                            color = statusContent
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isWorking) vm.formatSeconds(elapsed) else "00:00",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = statusContent
                        )
                        if (isWorking) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (isOvertime) "Nadgodziny: +${vm.formatMinutes(overtimeMinutes)}"
                                       else "Pozostało: ${vm.formatMinutes(remainingMinutes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = statusContentMuted,
                                fontWeight = if (isOvertime) FontWeight.Bold else FontWeight.Normal
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (todayMinutes.toFloat() / targetMinutes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = statusContent
                            )
                            Spacer(Modifier.height(12.dp))
                            val nowMs = System.currentTimeMillis()
                            fun finishClock(targetMin: Int): String =
                                formatClock(nowMs + (targetMin - todayMinutes).coerceAtLeast(0) * 60_000L)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Koniec (8h)", style = MaterialTheme.typography.bodySmall,
                                        color = statusContentMuted)
                                    Text(finishClock(480), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (targetMinutes == 480) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace,
                                        color = statusContent)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Koniec (8h30m)", style = MaterialTheme.typography.bodySmall,
                                        color = statusContentMuted)
                                    Text(finishClock(510), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (targetMinutes == 510) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace,
                                        color = statusContent)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { vm.toggleWork() },
                            modifier = Modifier.size(80.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isWorking) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                imageVector = if (isWorking) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isWorking) "Stop" else "Start",
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isWorking) "Kliknij aby zatrzymać" else "Kliknij aby rozpocząć",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusContentMuted
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Dziś", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Przepracowano", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(vm.formatMinutes(todayMinutes),
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Cel", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(vm.formatMinutes(targetMinutes),
                                    style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (todaySessions.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            SessionsTable(todaySessions, vm)
                        }
                    }
                }
            }

            item {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = { vm.previousWeek() }) {
                                Icon(Icons.Default.ChevronLeft, "Poprzedni tydzień")
                            }
                            Text(
                                text = vm.getWeekLabel(weekStart),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                                textAlign = TextAlign.Center
                            )
                            IconButton(onClick = { vm.nextWeek() }) {
                                Icon(Icons.Default.ChevronRight, "Następny tydzień")
                            }
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Rozwiń/zwiń")
                            }
                        }
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            WeekSessionsTable(weekStart, weekSessions, vm)
                        }
                    }
                }
            }

            item {
                var expanded by rememberSaveable { mutableStateOf(false) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = { vm.previousMonth() }) {
                                Icon(Icons.Default.ChevronLeft, "Poprzedni miesiąc")
                            }
                            Text(
                                text = vm.getMonthLabel(monthStart).replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
                                textAlign = TextAlign.Center
                            )
                            IconButton(onClick = { vm.nextMonth() }) {
                                Icon(Icons.Default.ChevronRight, "Następny miesiąc")
                            }
                            val context = LocalContext.current
                            IconButton(onClick = {
                                val csv = buildMonthCsv(monthStart, monthSessions, vm)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_SUBJECT, "WorkTimeRecorder ${vm.getMonthLabel(monthStart)}")
                                    putExtra(Intent.EXTRA_TEXT, csv)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Udostępnij dane miesiąca"))
                            }) {
                                Icon(Icons.Default.Share, "Eksportuj CSV")
                            }
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Rozwiń/zwiń")
                            }
                        }
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            MonthlyTable(monthStart, monthSessions, vm)
                        }
                    }
                }
            }
        }
    }
}

private fun buildMonthCsv(monthStart: LocalDate, sessions: List<WorkSession>, vm: MainViewModel): String {
    val sb = StringBuilder()
    sb.appendLine("Data,Poczatek,Koniec,Suma (min)")
    sessions.sortedWith(compareBy({ it.date }, { it.startTime })).forEach { s ->
        val start = formatClock(s.startTime)
        val end = s.endTime?.let { formatClock(it) } ?: ""
        sb.appendLine("${s.date},${start},${end},${vm.sessionMinutes(s)}")
    }
    val total = sessions.sumOf { vm.sessionMinutes(it) }
    sb.appendLine("Suma,,,${total}")
    return sb.toString()
}

@Composable
fun MonthlyTable(monthStart: LocalDate, sessions: List<WorkSession>, vm: MainViewModel) {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dayNames = listOf("Pon", "Wto", "Śro", "Czw", "Pią", "Sob", "Nie")
    val today = LocalDate.now()
    val daysInMonth = monthStart.lengthOfMonth()

    val rows = (0 until daysInMonth).map { i ->
        val date = monthStart.plusDays(i.toLong())
        date to vm.getDayMinutes(date.format(fmt), sessions)
    }.filter { (date, minutes) -> minutes > 0 || !date.isAfter(today) }

    val workedDays = rows.count { it.second > 0 }
    val totalMinutes = rows.sumOf { it.second }
    val avgMinutes = if (workedDays > 0) totalMinutes / workedDays else 0

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Data", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1.4f))
            Text("Dzień", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text("Suma", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        rows.forEachIndexed { index, (date, minutes) ->
            val isToday = date == today
            Row(
                Modifier.fillMaxWidth()
                    .background(if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent)
                    .padding(vertical = 3.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("dd.MM")),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.4f)
                )
                Text(
                    text = dayNames[date.dayOfWeek.value - 1],
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (minutes > 0) vm.formatMinutes(minutes) else "—",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Suma", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vm.formatMinutes(totalMinutes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dni przepracowane", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$workedDays", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Średnio", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vm.formatMinutes(avgMinutes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SessionsTable(sessions: List<WorkSession>, vm: MainViewModel) {
    val sorted = sessions.sortedBy { it.startTime }
    val total = sorted.sumOf { vm.sessionMinutes(it) }
    var pendingDelete by remember { mutableStateOf<WorkSession?>(null) }
    var editingTime by remember { mutableStateOf<EditingTime?>(null) }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Pocz.", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Koniec", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Suma", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(40.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        sorted.forEach { session ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(session.startTime), fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, true) })
                Text(session.endTime?.let { formatClock(it) } ?: "…", fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    textDecoration = TextDecoration.Underline,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, false) })
                Text(vm.formatMinutes(vm.sessionMinutes(session)), fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                IconButton(onClick = { pendingDelete = session }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Usuń", modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Suma", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vm.formatMinutes(total), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }

    pendingDelete?.let { session ->
        DeleteSessionDialog(session, onConfirm = { vm.deleteSession(session); pendingDelete = null }, onDismiss = { pendingDelete = null })
    }
    editingTime?.let { et ->
        EditTimeDialog(
            initialEpochMs = if (et.isStart) et.session.startTime else et.session.endTime,
            onConfirm = { h, m ->
                if (et.isStart) vm.updateSessionStart(et.session, h, m) else vm.updateSessionEnd(et.session, h, m)
                editingTime = null
            },
            onDismiss = { editingTime = null }
        )
    }
}

@Composable
fun DeleteSessionDialog(session: WorkSession, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usunąć wpis?") },
        text = {
            Text("${formatClock(session.startTime)} – ${session.endTime?.let { formatClock(it) } ?: "trwa"}")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Usuń") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anuluj") }
        }
    )
}

@Composable
fun WeekSessionsTable(weekStart: LocalDate, sessions: List<WorkSession>, vm: MainViewModel) {
    val dayNames = listOf("Pon", "Wto", "Śro", "Czw", "Pią", "Sob", "Nie")
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val dateFmt = DateTimeFormatter.ofPattern("dd.MM")
    val today = LocalDate.now()
    val byDate = sessions.groupBy { it.date }
    var weekTotal = 0
    var pendingDelete by remember { mutableStateOf<WorkSession?>(null) }
    var editingTime by remember { mutableStateOf<EditingTime?>(null) }

    Column {
        var renderedDayIndex = 0
        (0..6).forEach { i ->
            val date = weekStart.plusDays(i.toLong())
            if (date.isAfter(today)) return@forEach
            val daySessions = byDate[date.format(fmt)]?.sortedBy { it.startTime } ?: emptyList()
            if (daySessions.isEmpty()) return@forEach

            val dayTotal = daySessions.sumOf { vm.sessionMinutes(it) }
            weekTotal += dayTotal
            val stripe = if (renderedDayIndex % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent
            renderedDayIndex++

            Row(Modifier.fillMaxWidth().background(stripe).padding(top = 4.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${dayNames[date.dayOfWeek.value - 1]} ${date.format(dateFmt)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(vm.formatMinutes(dayTotal), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            daySessions.forEach { session ->
                Row(Modifier.fillMaxWidth().background(stripe).padding(vertical = 0.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(session.startTime), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        textDecoration = TextDecoration.Underline,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, true) })
                    Text(session.endTime?.let { formatClock(it) } ?: "…", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        textDecoration = TextDecoration.Underline,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, false) })
                    Text(vm.formatMinutes(vm.sessionMinutes(session)), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { pendingDelete = session }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Usuń", modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Suma", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(vm.formatMinutes(weekTotal), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }

    pendingDelete?.let { session ->
        DeleteSessionDialog(session, onConfirm = { vm.deleteSession(session); pendingDelete = null }, onDismiss = { pendingDelete = null })
    }
    editingTime?.let { et ->
        EditTimeDialog(
            initialEpochMs = if (et.isStart) et.session.startTime else et.session.endTime,
            onConfirm = { h, m ->
                if (et.isStart) vm.updateSessionStart(et.session, h, m) else vm.updateSessionEnd(et.session, h, m)
                editingTime = null
            },
            onDismiss = { editingTime = null }
        )
    }
}
