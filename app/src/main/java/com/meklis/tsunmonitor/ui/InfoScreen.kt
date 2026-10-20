package com.meklis.tsunmonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meklis.tsunmonitor.data.Snapshot
import com.meklis.tsunmonitor.data.objects
import com.meklis.tsunmonitor.protocol.Period
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Labels and units of EnergyParameter fields; unknown fields are shown under their own name. */
private data class Field(val label: String, val unit: String = "", val scale: Double = 1.0)

private val SUMMARY_FIELDS = mapOf(
    "AverageBatteryAverageSOC" to Field("SOC батареї", "%"),
    "TotalGridOutputPower" to Field("Мережа (− споживання, + віддача)", "Вт"),
    "TotalACChargePower" to Field("Заряд від мережі", "Вт"),
    "TotalChargePower" to Field("Заряд батареї", "Вт"),
    "TotalBatteryOutputPower" to Field("Віддача батареї", "Вт"),
    "TotalBackUpPower" to Field("Backup (занижено в 10 разів)", "Вт"),
    "TotalPVPower" to Field("Сонячні панелі", "Вт"),
    "TotalPVChargePower" to Field("Заряд від PV", "Вт"),
    "TotalSmartLoadElectricalPower" to Field("Розумні навантаження", "Вт"),
    "MeterTotalActivePower" to Field("Лічильник", "Вт"),
    "ControlEnableStatus" to Field("Керування увімкнено"),
)

// Storage_list powers are in 0.1 W, except OffGridLoadPower (already W).
private val STORAGE_FIELDS = mapOf(
    "StorageSN" to Field("Серійний номер"),
    "DevAddr" to Field("Адреса"),
    "StorageStatus" to Field("Статус"),
    "BatterySoc" to Field("SOC", "%"),
    "AcInActivePower" to Field("Вхід AC", "Вт", 0.1),
    "AcChargingPower" to Field("Заряд від мережі", "Вт", 0.1),
    "BatteryChargingPower" to Field("Заряд батареї", "Вт", 0.1),
    "BatteryDischargingPower" to Field("Розряд батареї", "Вт", 0.1),
    "OffGridLoadPower" to Field("Розетка 2 (Off-Grid)", "Вт"),
    "PvChargingPower" to Field("Заряд від PV", "Вт", 0.1),
    "PvStringCount" to Field("Кількість PV-входів"),
    "Pv1Power" to Field("PV1", "Вт", 0.1),
    "Pv2Power" to Field("PV2", "Вт", 0.1),
    "Pv3Power" to Field("PV3", "Вт", 0.1),
    "Pv4Power" to Field("PV4", "Вт", 0.1),
)

/** Register meanings that are confirmed; the rest are shown raw. */
private val REGISTER_LABELS = mapOf(
    3027 to "Період дійсний з",
    3036 to "Макс. потужність системи, Вт",
    3020 to "Невідомо (3; не відключає розетки)",
    3023 to "Невідомо (10; не відключає розетки)",
)

private val SERVICE_KEYS = setOf("Response", "SerialNumber", "Target", "SSumInfoList", "Storage_list")

@Composable
fun InfoScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val s = state.info
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InverterControlsCard(state, vm, editableMinSoc = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::fetchInfo, enabled = !state.infoLoading && !state.writing) {
                Text(if (state.infoLoading) "Запит…" else "Отримати інформацію")
            }
            Spacer(Modifier.width(12.dp))
            s?.let {
                Text(
                    "Отримано о " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.timeMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.infoError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (s == null) {
            Text(
                "Тут усі дані інвертора, включно з регістрами керування. Вкладка не оновлюється сама.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val energy = s.raw.optJSONObject("energy") ?: JSONObject()

        Section("Розрахунок застосунку") {
            KeyValue("Мережа", when (s.gridPresent) { true -> "є"; false -> "немає"; null -> "невідомо" })
            KeyValue("Вихід інвертора", watts(s.homeW))
            KeyValue("Розетка 2 (Off-Grid)", watts(s.offGridW))
            KeyValue("Розетка 1 + втрати", watts(s.houseLoadW))
            KeyValue("Температура інвертора", s.temperatureC?.let { "%.1f °C".format(it) } ?: "—", key = "Modbus ${Snapshot.TEMPERATURE_REG}")
        }

        energy.optJSONObject("SSumInfoList")?.let { Section("Підсумок (SSumInfoList)") { Fields(it, SUMMARY_FIELDS) } }
        energy.optJSONArray("Storage_list")?.objects()?.forEachIndexed { i, st ->
            Section("Накопичувач ${i + 1} (Storage_list)") { Fields(st, STORAGE_FIELDS) }
        }
        val other = energy.keys().asSequence().filter { it !in SERVICE_KEYS }.toList()
        if (other.isNotEmpty()) {
            Section("Інше") { other.forEach { KeyValue(it, energy.opt(it).toString(), key = it) } }
        }

        Section("Періоди розкладу") {
            Text(
                "Натисніть на період, щоб змінити регістр",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Periods(s) { editing = it }
        }

        val plain = s.registers.filterKeys { it !in Period.REGISTERS }.toSortedMap()
        if (plain.isNotEmpty()) {
            Section("Регістри керування") {
                EditHint(state)
                plain.forEach { (reg, value) ->
                    KeyValue(REGISTER_LABELS[reg] ?: "—", value, key = reg.toString(), onClick = { editing = reg })
                }
            }
        } else {
            Text(
                "Регістри будуть після наступного оновлення",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    editing?.let { reg ->
        RegisterDialog(reg, s?.registers?.get(reg) ?: "", onDismiss = { editing = null }) { value ->
            editing = null
            vm.writeRegister(reg, value)
        }
    }
}

@Composable
private fun EditHint(state: UiState) {
    Text(
        "Натисніть на регістр, щоб змінити його значення",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.writing) Text("Запис…", style = MaterialTheme.typography.bodySmall)
    state.writeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Raw register edit. Values are written as typed, so the dialog asks twice. */
@Composable
private fun RegisterDialog(register: Int, current: String, onDismiss: () -> Unit, onWrite: (String) -> Unit) {
    var value by remember(register) { mutableStateOf(current) }
    var sure by remember(register) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Регістр $register") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Зараз: $current", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = value, onValueChange = { value = it.trim(); sure = false },
                    label = { Text("Нове значення") }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (sure) "Натисніть «Записати» ще раз для підтвердження"
                    else "Значення записується як є. Неправильне значення може змінити роботу інвертора.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (sure) onWrite(value) else sure = true },
                enabled = value.isNotEmpty() && value != current,
            ) { Text(if (sure) "Так, записати" else "Записати") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

@Composable
private fun Periods(s: Snapshot, onEdit: (Int) -> Unit) {
    val active = s.activePeriod
    s.periods.forEachIndexed { i, p ->
        if (i > 0) HorizontalDivider()
        val n = p.register - 3002
        val what = when {
            p.powerW < 0 -> "заряд ${-p.powerW} Вт"
            p.powerW > 0 -> "розряд ${p.powerW} Вт"
            else -> "0 Вт"
        }
        val title = "Період $n" + if (p == active) " · активний" else ""
        if (p.enabled) {
            Stacked(
                title, "${p.start}–${p.end}, $what, до ${p.maxSoc} %, режим ${p.mode}", "${p.register}: ${p.raw()}",
                bold = p == active, onClick = { onEdit(p.register) },
            )
        } else {
            KeyValue(title, "вимкнено", key = p.register.toString(), onClick = { onEdit(p.register) })
        }
    }
}

@Composable
private fun Fields(o: JSONObject, labels: Map<String, Field>) {
    val keys = labels.keys.filter { o.has(it) } + o.keys().asSequence().filter { it !in labels }
    keys.forEachIndexed { i, k ->
        if (i > 0) HorizontalDivider()
        val f = labels[k]
        val raw = o.opt(k)
        val value = when {
            f == null -> raw.toString()
            raw is Number && f.unit.isNotEmpty() -> {
                val v = raw.toDouble() * f.scale
                "${if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)} ${f.unit}"
            }
            else -> raw.toString()
        }
        KeyValue(f?.label ?: k, value, key = if (f != null) "$k = $raw" else null)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}

/** Label with the raw field name below it, value on the right. */
@Composable
private fun KeyValue(label: String, value: String, key: String? = null, bold: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickableIf(onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else null)
            key?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Long values wrap instead of squeezing the label column.
        Text(
            value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
    }
}

/** Title, a full-width value line and the raw register below. */
@Composable
private fun Stacked(title: String, value: String, raw: String, bold: Boolean, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().clickableIf(onClick).padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else null)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            raw, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun watts(w: Double?) = w?.let { "${it.roundToInt()} Вт" } ?: "—"

private fun Modifier.clickableIf(onClick: (() -> Unit)?) = if (onClick != null) clickable(onClick = onClick) else this
