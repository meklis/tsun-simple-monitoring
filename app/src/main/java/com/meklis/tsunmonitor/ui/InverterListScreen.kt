package com.meklis.tsunmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InverterListScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    var ip by rememberSaveable { mutableStateOf("") }
    val saved = state.inverters
    fun savedFor(f: com.meklis.tsunmonitor.discovery.Found) =
        saved.firstOrNull { (f.serial != null && it.id == f.serial) || (it.host == f.host && it.port == f.port) }
    val found = state.discovered
    val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it.trim() },
                        label = { Text("IP інвертора") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { vm.connect(ip) }, enabled = ip.isNotBlank() && state.connecting == null) {
                        Text("Відкрити")
                    }
                }
            }
        }
        state.connecting?.let { item { Text("Підключення до $it…") } }
        state.addError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }

        if (saved.isNotEmpty()) {
            item { Header("Мої інвертори") }
            items(saved, key = { "saved-" + it.id }) { inv ->
                Card(Modifier.fillMaxWidth().clickable { vm.open(inv) }) {
                    ListItem(
                        headlineContent = { Text(inv.name) },
                        supportingContent = {
                            Text("${inv.host}:${inv.port}" + if (inv.lastOpenedMs > 0) " · ${fmt.format(Date(inv.lastOpenedMs))}" else "")
                        },
                        trailingContent = {
                            IconButton(onClick = { vm.remove(inv) }) { Icon(Icons.Filled.Delete, "Видалити") }
                        },
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Header("Знайдені в мережі", Modifier.weight(1f))
                if (state.scanning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = vm::startScan) { Icon(Icons.Filled.Refresh, "Шукати знову") }
                }
            }
        }
        item {
            Text(
                state.scanNetwork?.let { "Телефон: $it" } ?: "Телефон не підключений до мережі",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (found.isEmpty()) {
            item {
                Text(
                    if (state.scanning) "Пошук…" else "Інверторів не знайдено",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(found, key = { "found-" + it.host + ":" + it.port }) { f ->
            val known = savedFor(f)
            Card(Modifier.fillMaxWidth().clickable { vm.connect("${f.host}:${f.port}", f.port, f.serial) }) {
                ListItem(
                    headlineContent = { Text(known?.name ?: f.serial?.let { "TSUN $it" } ?: f.host) },
                    supportingContent = {
                        Text("${f.host}:${f.port} · ${f.source}" + if (known != null) " · збережений" else "")
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 8.dp))
}
