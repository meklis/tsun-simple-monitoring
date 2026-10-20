package com.meklis.tsunmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meklis.tsunmonitor.protocol.Period

private const val MAX_CHARGE_W = 2500

/**
 * Charge power and SOC limits of the active period. Min SOC is editable only on the
 * info tab ([editableMinSoc]); elsewhere every write sets it to [Period.MIN_SOC].
 */
@Composable
fun InverterControlsCard(state: UiState, vm: MainViewModel, editableMinSoc: Boolean = false) {
    val period = state.snapshot?.activePeriod
    // Re-seed the fields whenever the device value changes.
    var power by remember(period?.raw()) { mutableStateOf(period?.powerW?.takeIf { it < 0 }?.let { (-it).toString() } ?: "") }
    var maxSoc by remember(period?.raw()) { mutableStateOf(period?.maxSoc?.toString() ?: "") }
    var minSoc by remember(period?.raw()) { mutableStateOf(period?.minSoc?.toString() ?: "") }
    var confirm by remember { mutableStateOf(false) }

    val p = power.toIntOrNull()
    val mx = maxSoc.toIntOrNull()
    val mn = if (editableMinSoc) minSoc.toIntOrNull() else Period.MIN_SOC
    val error = when {
        period == null -> "Немає даних розкладу: спочатку отримайте дані з інвертора"
        power.isNotEmpty() && (p == null || p !in 0..MAX_CHARGE_W) -> "Потужність заряду: 0–$MAX_CHARGE_W Вт"
        mn == null || mn !in 0..99 -> "Мінімальний SOC: 0–99 %"
        mx == null || mx !in 1..100 -> "Максимальний SOC: 1–100 %"
        mn >= mx -> "Мінімальний SOC має бути меншим за максимальний"
        else -> null
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Інвертор", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = power, onValueChange = { power = it.filter(Char::isDigit) },
                label = { Text("Максимальна потужність заряду, Вт") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = maxSoc, onValueChange = { maxSoc = it.filter(Char::isDigit) },
                label = { Text("Максимальний SOC, %") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (editableMinSoc) {
                OutlinedTextField(
                    value = minSoc, onValueChange = { minSoc = it.filter(Char::isDigit) },
                    label = { Text("Мінімальний SOC, %") },
                    supportingText = { Text("Розетки Off-Grid його не враховують; у «Налаштування» завжди ${Period.MIN_SOC} %") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            period?.let {
                Text(
                    "Активний період ${it.register - 3002}: ${it.start}–${it.end}, ${it.powerW} Вт",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.writeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { confirm = true }, enabled = error == null && !state.writing) {
                Text(if (state.writing) "Запис…" else "Записати в інвертор")
            }
        }
    }

    if (confirm && period != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Записати в інвертор?") },
            text = {
                Text(
                    "Період ${period.register - 3002}:\n" +
                        "потужність заряду ${if (power.isEmpty()) "без змін" else "$p Вт"}\n" +
                        (if (editableMinSoc) "SOC $mn–$mx % (зараз ${period.minSoc}–${period.maxSoc} %)"
                        else "максимальний SOC $mx % (зараз ${period.maxSoc} %)"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.writeSettings(p.takeIf { power.isNotEmpty() }, mx, mn!!)
                }) { Text("Записати") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Скасувати") } },
        )
    }
}
