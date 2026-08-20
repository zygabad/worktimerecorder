package com.zygabad.worktimerecorder.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
    val todayMinutes = vm.getTodayTotalMinutes(todaySessions)
    val targetMinutes = vm.prefs.targetWorkMinutes
    val remainingMinutes = (targetMinutes - todayMinutes).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Work Timer", fontWeight = FontWeight.Bold) },
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
                        containerColor = if (isWorking) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isWorking) "Pracujesz" else "Wolny",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isWorking) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isWorking) vm.formatSeconds(elapsed) else "00:00",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isWorking) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Pozostało: ${vm.formatMinutes(remainingMinutes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { (todayMinutes.toFloat() / targetMinutes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                            )
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { vm.nextWeek() }) {
                                Icon(Icons.Default.ChevronRight, "Następny tydzień")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        WeekSessionsTable(weekStart, weekSessions, vm)
                    }
                }
            }

            item {
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
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { vm.nextMonth() }) {
                                Icon(Icons.Default.ChevronRight, "Następny miesiąc")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        MonthlyTable(monthStart, monthSessions, vm)
                    }
                }
            }
        }
    }
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
        rows.forEach { (date, minutes) ->
            val isToday = date == today
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("dd.MM")),
                    fontSize = 13.sp,
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
                Text(formatClock(session.startTime), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, true) })
                Text(session.endTime?.let { formatClock(it) } ?: "…", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, false) })
                Text(vm.formatMinutes(vm.sessionMinutes(session)), fontSize = 13.sp, modifier = Modifier.weight(1f))
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
        (0..6).forEach { i ->
            val date = weekStart.plusDays(i.toLong())
            if (date.isAfter(today)) return@forEach
            val daySessions = byDate[date.format(fmt)]?.sortedBy { it.startTime } ?: emptyList()
            if (daySessions.isEmpty()) return@forEach

            val dayTotal = daySessions.sumOf { vm.sessionMinutes(it) }
            weekTotal += dayTotal

            Text(
                text = "${dayNames[date.dayOfWeek.value - 1]} ${date.format(dateFmt)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            daySessions.forEach { session ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatClock(session.startTime), fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, true) })
                    Text(session.endTime?.let { formatClock(it) } ?: "…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable { editingTime = EditingTime(session, false) })
                    Text(vm.formatMinutes(vm.sessionMinutes(session)), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { pendingDelete = session }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Usuń", modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
                Spacer(Modifier.weight(2f))
                Text(vm.formatMinutes(dayTotal), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(bottom = 8.dp))
        }
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
