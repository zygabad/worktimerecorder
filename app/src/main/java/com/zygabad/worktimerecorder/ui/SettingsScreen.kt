package com.zygabad.worktimerecorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: MainViewModel = viewModel()) {
    val initialTarget = vm.prefs.targetWorkMinutes
    var hours by remember { mutableStateOf((initialTarget / 60).toString()) }
    var minutes by remember { mutableStateOf((initialTarget % 60).toString()) }
    var saved by remember { mutableStateOf(false) }
    val currentTarget = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
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
                "Widżet pokazuje czas pozostały do końca pracy i pozwala start/stop tapnięciem.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            var requireDoubleTap by remember { mutableStateOf(vm.prefs.requireDoubleTap) }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Wymagaj dwóch tapnięć", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Zabezpieczenie przed przypadkowym uruchomieniem — drugie tapnięcie musi nastąpić w ciągu ok. pół sekundy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = requireDoubleTap,
                    onCheckedChange = { requireDoubleTap = it; vm.prefs.requireDoubleTap = it }
                )
            }
        }
    }
}
