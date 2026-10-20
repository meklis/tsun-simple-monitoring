package com.meklis.tsunmonitor.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }
    BackHandler(enabled = state.screen == Screen.LIST && state.current != null) { vm.backToMain() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (state.screen == Screen.LIST) {
                        Text("Інвертори")
                    } else {
                        Column {
                            Text(state.current?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                state.current?.let { if (it.port == 8080) it.host else "${it.host}:${it.port}" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (state.screen == Screen.LIST && state.current != null) {
                        IconButton(onClick = vm::backToMain) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    }
                },
                actions = {
                    if (state.loading || state.writing || state.infoLoading) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(22.dp), strokeWidth = 2.5.dp)
                    }
                    if (state.screen == Screen.MAIN) {
                        IconButton(onClick = vm::showList) { Icon(Icons.AutoMirrored.Filled.List, "Список інверторів") }
                    }
                },
            )
        },
        bottomBar = {
            if (state.screen == Screen.MAIN) {
                NavigationBar {
                    NavigationBarItem(
                        selected = state.tab == Tab.STATUS,
                        onClick = { vm.selectTab(Tab.STATUS) },
                        icon = { Icon(Icons.Filled.Info, null) },
                        label = { Text("Стан") },
                    )
                    // The info tab is hidden: three quick taps on "Налаштування" open it.
                    var taps by remember { mutableStateOf(emptyList<Long>()) }
                    NavigationBarItem(
                        selected = state.tab == Tab.SETTINGS || state.tab == Tab.INFO,
                        onClick = {
                            val now = SystemClock.uptimeMillis()
                            taps = (taps + now).filter { now - it < TRIPLE_TAP_MS }
                            if (taps.size >= 3) {
                                taps = emptyList()
                                vm.selectTab(Tab.INFO)
                            } else if (state.tab != Tab.INFO) {
                                vm.selectTab(Tab.SETTINGS)
                            }
                        },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text("Налаштування") },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (state.screen == Screen.MAIN && state.tab != Tab.INFO) DataAgeStrip(state)
            if (state.screen == Screen.LIST) {
                InverterListScreen(state, vm, Modifier.weight(1f))
            } else if (state.tab == Tab.INFO) {
                // No pull-to-refresh: the info tab reads only on its button.
                InfoScreen(state, vm, Modifier.weight(1f))
            } else {
                // Pull down to request data right now.
                PullToRefreshBox(
                    isRefreshing = state.loading && state.refreshSource == RefreshSource.PULL,
                    onRefresh = { vm.refresh(RefreshSource.PULL) },
                    modifier = Modifier.weight(1f),
                ) {
                    when (state.tab) {
                        Tab.STATUS -> StatusScreen(state, vm)
                        else -> SettingsScreen(state, vm)
                    }
                }
            }
        }
    }
}

/** Thin strip under the top bar once the shown data is older than 3 minutes. */
@Composable
private fun DataAgeStrip(state: UiState) {
    val snap = state.snapshot ?: return
    val ageMin = (state.nowMs - snap.timeMs) / 60_000
    if (ageMin < 3) return
    val text = if (ageMin < 60) "Дані оновлено $ageMin хв тому" else "Дані оновлено ${ageMin / 60} год ${ageMin % 60} хв тому"
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(vertical = 3.dp),
    )
}

private const val TRIPLE_TAP_MS = 1_500L
