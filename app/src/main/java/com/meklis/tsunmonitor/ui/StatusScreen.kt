package com.meklis.tsunmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.meklis.tsunmonitor.R
import com.meklis.tsunmonitor.data.formatHours
import com.meklis.tsunmonitor.data.hoursToEmpty
import com.meklis.tsunmonitor.data.hoursToFull
import com.meklis.tsunmonitor.protocol.Period
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatusScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val s = state.snapshot
    val inv = state.current ?: return
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FreshnessBanner(state, vm)
        if (s == null) return@Column

        val period = s.activePeriod
        val soc = s.soc
        val grid = s.gridPresent
        val chargeW = s.batteryChargeW?.takeIf { it > 0 } ?: s.acChargeW
        // Time to full only with grid and below max SOC; time to empty only without grid.
        val estimate = when {
            grid == false && soc != null && soc <= Period.MIN_SOC -> "" to "Батарея розряджена"
            period == null -> null
            grid == true && soc != null && soc < period.maxSoc ->
                formatHours(hoursToFull(soc, period.maxSoc, chargeW, inv.capacityWh, grid))?.let { "Зарядиться за " to it }
            grid == false ->
                formatHours(hoursToEmpty(soc, Period.MIN_SOC, s.homeW, inv.capacityWh))?.let { "Вистачить на ~ " to it }
            else -> null
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_battery), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Заряд батареї", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    soc?.let { "${it.roundToInt()} %" } ?: "—",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                LinearProgressIndicator(
                    progress = { ((soc ?: 0.0) / 100).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                val line = estimate?.let { (prefix, time) ->
                    buildAnnotatedString {
                        append(prefix)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) { append(time) }
                    }
                } ?: period?.let { AnnotatedString("Заряд до ${it.maxSoc} %") }
                line?.let {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (estimate != null) {
                            Icon(
                                painterResource(if (grid == true) R.drawable.ic_battery_charging else R.drawable.ic_hourglass),
                                null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val gridW = s.gridPowerW ?: 0.0

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val gridColor = when (grid) {
                    true -> Color(0xFF2E7D32)
                    false -> MaterialTheme.colorScheme.error
                    null -> null
                }
                InfoRow(
                    painterResource(R.drawable.ic_bolt), "Мережа",
                    when (grid) {
                        true -> "є"
                        false -> "немає"
                        null -> "невідомо"
                    },
                    color = gridColor, iconTint = gridColor,
                )
                HorizontalDivider()
                InfoRow(
                    painterResource(R.drawable.ic_plug),
                    if (gridW > 0) "Віддача в мережу" else "Потужність з мережі",
                    "${abs(gridW).roundToInt()} Вт",
                )
                HorizontalDivider()
                InfoRow(
                    painterResource(R.drawable.ic_outlet), "Вихід",
                    "${(s.homeW ?: 0.0).roundToInt()} Вт",
                )
                Text(
                    "Розетка 2 ${(s.offGridW ?: 0.0).roundToInt()} Вт · розетка 1 + втрати ${(s.houseLoadW ?: 0.0).roundToInt()} Вт",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 34.dp, bottom = 8.dp),
                )
                s.temperatureC?.let { t ->
                    HorizontalDivider()
                    InfoRow(
                        painterResource(R.drawable.ic_thermometer), "Температура інвертора",
                        "${"%.1f".format(t).replace('.', ',')} °C",
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: Painter, label: String, value: String, color: Color? = null, iconTint: Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null, Modifier.size(22.dp),
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FreshnessBanner(state: UiState, vm: MainViewModel) {
    val s = state.snapshot
    val updated = s?.let { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.timeMs)) }
    when {
        state.loading && state.refreshSource != RefreshSource.BACKGROUND -> Text(
            "Запит даних… спроба ${state.attempt} з 3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error != null || state.stale -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
                if (state.stale) {
                    val age = s?.let { (state.nowMs - it.timeMs) / 60_000 }
                    Text(
                        if (age != null) "Дані неактуальні: отримані $age хв тому ($updated)" else "Даних ще немає",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Button(onClick = { vm.refresh(RefreshSource.USER) }, enabled = !state.loading) { Text("Запитати зараз") }
            }
        }
        else -> Text(
            "Оновлено о $updated",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
