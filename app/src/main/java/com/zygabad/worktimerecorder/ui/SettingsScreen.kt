package com.zygabad.worktimerecorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@Composable
private fun HourMinuteFields(
    hours: String,
    onHoursChange: (String) -> Unit,
    minutes: String,
    onMinutesChange: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = hours,
            onValueChange = { if (it.length <= 2) onHoursChange(it) },
            label = { Text("Godziny") },
            suffix = { Text("h") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = minutes,
            onValueChange = { if (it.length <= 2) onMinutesChange(it) },
            label = { Text("Minuty") },
            suffix = { Text("min") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: MainViewModel = viewModel()) {
    val initialTarget = vm.prefs.targetWorkMinutes
    var hours by remember { mutableStateOf((initialTarget / 60).toString()) }
    var minutes by remember { mutableStateOf((initialTarget % 60).toString()) }
    var saved by remember { mutableStateOf(false) }
    val currentTarget = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)

    val initialYellow = vm.prefs.yellowThresholdMinutes
    var yellowH by remember { mutableStateOf((initialYellow / 60).toString()) }
    var yellowM by remember { mutableStateOf((initialYellow % 60).toString()) }
    val initialOrange = vm.prefs.orangeThresholdMinutes
    var orangeH by remember { mutableStateOf((initialOrange / 60).toString()) }
    var orangeM by remember { mutableStateOf((initialOrange % 60).toString()) }
    val initialRed = vm.prefs.redThresholdMinutes
    var redH by remember { mutableStateOf((initialRed / 60).toString()) }
    var redM by remember { mutableStateOf((initialRed % 60).toString()) }
    var colorsSaved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Wróć")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Docelowy czas pracy", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ustaw ile godzin i minut traktujesz jako pełny dzień pracy. Domyślnie 8h 30min.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = currentTarget == 480,
                    onClick = { hours = "8"; minutes = "0"; vm.prefs.targetWorkMinutes = 480; saved = true },
                    label = { Text("8h") }
                )
                FilterChip(
                    selected = currentTarget == 510,
                    onClick = { hours = "8"; minutes = "30"; vm.prefs.targetWorkMinutes = 510; saved = true },
                    label = { Text("8h 30m") }
                )
            }
            Text(
                "albo wpisz własną wartość:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hours,
                    onValueChange = { if (it.length <= 2) { hours = it; saved = false } },
                    label = { Text("Godziny") },
                    suffix = { Text("h") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { if (it.length <= 2) { minutes = it; saved = false } },
                    label = { Text("Minuty") },
                    suffix = { Text("min") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    val h = hours.toIntOrNull() ?: 8
                    val m = minutes.toIntOrNull() ?: 30
                    vm.prefs.targetWorkMinutes = h * 60 + m
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz") }

            if (saved) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        "✓ Zapisano: ${hours}h ${minutes}min",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            HorizontalDivider()
            Text("Widżet na ekranie głównym", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dodaj widżet 1×1 WorkTimer do ekranu głównego:\n" +
                "1. Naciśnij długo na pulpicie\n" +
                "2. Wybierz Widżety\n" +
                "3. Znajdź WorkTimeRecorder\n" +
                "4. Przeciągnij na ekran\n\n" +
                "Jedno tapnięcie otwiera aplikację. Dwa szybkie tapnięcia (w ciągu ok. pół sekundy) " +
                "startują/zatrzymują licznik bez otwierania aplikacji.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            Text("Kolory widgetu", style = MaterialTheme.typography.titleMedium)
            Text(
                "Zielony to normalny stan pracy, niebieski pojawia się automatycznie w połowie ustawionego celu. " +
                "Poniższe progi zmieniają tło widgetu na żółty/pomarańczowy/czerwony.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Żółty, gdy do celu zostanie mniej niż:", style = MaterialTheme.typography.bodySmall)
            HourMinuteFields(
                hours = yellowH, onHoursChange = { yellowH = it; colorsSaved = false },
                minutes = yellowM, onMinutesChange = { yellowM = it; colorsSaved = false }
            )

            Text("Pomarańczowy po przekroczeniu:", style = MaterialTheme.typography.bodySmall)
            HourMinuteFields(
                hours = orangeH, onHoursChange = { orangeH = it; colorsSaved = false },
                minutes = orangeM, onMinutesChange = { orangeM = it; colorsSaved = false }
            )

            Text("Czerwony po przekroczeniu:", style = MaterialTheme.typography.bodySmall)
            HourMinuteFields(
                hours = redH, onHoursChange = { redH = it; colorsSaved = false },
                minutes = redM, onMinutesChange = { redM = it; colorsSaved = false }
            )

            Button(
                onClick = {
                    vm.prefs.yellowThresholdMinutes = (yellowH.toIntOrNull() ?: 1) * 60 + (yellowM.toIntOrNull() ?: 30)
                    vm.prefs.orangeThresholdMinutes = (orangeH.toIntOrNull() ?: 8) * 60 + (orangeM.toIntOrNull() ?: 0)
                    vm.prefs.redThresholdMinutes = (redH.toIntOrNull() ?: 8) * 60 + (redM.toIntOrNull() ?: 30)
                    colorsSaved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Zapisz progi kolorów") }

            if (colorsSaved) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(
                        "✓ Zapisano progi kolorów",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            HorizontalDivider()
            val context = LocalContext.current
            val versionLabel = remember {
                runCatching {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
                    "${info.versionName} (build $code)"
                }.getOrDefault("—")
            }
            Text(
                "Wersja aplikacji: $versionLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
