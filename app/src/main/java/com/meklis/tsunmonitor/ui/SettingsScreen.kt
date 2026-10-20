package com.meklis.tsunmonitor.ui

import com.meklis.tsunmonitor.protocol.Period
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val inv = state.current ?: return
    var capacity by remember(inv.id) { mutableStateOf((inv.capacityWh / 1000).toString()) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InverterControlsCard(state, vm)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Застосунок", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = capacity, onValueChange = { capacity = it.replace(',', '.') },
                    label = { Text("Ємність батареї, кВт·год") },
                    supportingText = { Text("Для розрахунку часу заряду/розряду. MAU5000 — 5,12 на блок") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { capacity.toDoubleOrNull()?.let { vm.saveCapacity(it * 1000) } },
                    enabled = capacity.toDoubleOrNull()?.let { it > 0 } == true,
                ) { Text("Зберегти ємність") }
                HorizontalDivider()
                MonitorSwitch(state, vm)
            }
        }
    }
}

@Composable
private fun MonitorSwitch(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setMonitor(true)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Фоновий моніторинг", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Перевірка всіх збережених інверторів кожні 5 хв: сповіщення про вимкнення/увімкнення мережі " +
                    "та заряд 20 % і 10 %, статус — коли мережі немає або батарея не заряджена до максимуму",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.monitorEnabled,
            onCheckedChange = { on ->
                val needsPermission = on && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                if (needsPermission) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setMonitor(on)
            },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Завжди показувати статус", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Закріплене сповіщення із зарядом і станом мережі, оновлюється при кожній перевірці",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.alwaysShowStatus,
            onCheckedChange = vm::setAlwaysShowStatus,
            enabled = state.monitorEnabled,
        )
    }
    if (state.monitorEnabled) {
        Text(
            state.lastCheck?.let { (t, r) ->
                "Остання фонова перевірка: " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(t)) + " — " + r
            } ?: "Фонова перевірка ще не виконувалась",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Text(
                "Щоб система не зупиняла моніторинг, дозвольте роботу у фоні без обмежень.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) { Text("Зняти обмеження батареї") }
        }
    }
}
