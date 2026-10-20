package com.meklis.tsunmonitor.ui

import com.meklis.tsunmonitor.protocol.Period
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meklis.tsunmonitor.data.Inverter
import com.meklis.tsunmonitor.data.InverterApi
import com.meklis.tsunmonitor.data.InverterStore
import com.meklis.tsunmonitor.data.Snapshot
import com.meklis.tsunmonitor.discovery.Discovery
import com.meklis.tsunmonitor.discovery.Found
import com.meklis.tsunmonitor.monitor.Monitor
import com.meklis.tsunmonitor.protocol.EmsSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Screen { LIST, MAIN }
enum class Tab { STATUS, INFO, SETTINGS }

/** Who started the running request: decides which progress indicators are shown. */
enum class RefreshSource { BACKGROUND, USER, PULL }

data class UiState(
    val screen: Screen = Screen.LIST,
    val tab: Tab = Tab.STATUS,
    val inverters: List<Inverter> = emptyList(),
    val current: Inverter? = null,
    val snapshot: Snapshot? = null,
    val loading: Boolean = false,
    val refreshSource: RefreshSource = RefreshSource.BACKGROUND,
    val attempt: Int = 0,
    val error: String? = null,
    val nowMs: Long = System.currentTimeMillis(),
    val discovered: List<Found> = emptyList(),
    val scanning: Boolean = false,
    /** Phone address/prefix the scan covers. */
    val scanNetwork: String? = null,
    val connecting: String? = null,
    val addError: String? = null,
    val writing: Boolean = false,
    val writeMessage: String? = null,
    val monitorEnabled: Boolean = false,
    val alwaysShowStatus: Boolean = false,
    /** Last background check (time, result), for diagnosis in the settings. */
    val lastCheck: Pair<Long, String>? = null,
    /** Full read for the hidden info tab, fetched only on its button. */
    val info: Snapshot? = null,
    val infoLoading: Boolean = false,
    val infoError: String? = null,
) {
    val stale: Boolean get() = snapshot == null || nowMs - snapshot.timeMs > STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 5 * 60_000L
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = InverterStore(app)
    private val discovery = Discovery(app)
    private val _state = MutableStateFlow(UiState(inverters = store.all(), monitorEnabled = store.monitorEnabled, alwaysShowStatus = store.alwaysShowStatus))
    val state: StateFlow<UiState> = _state

    private val fetchLock = Mutex()
    private var pollJob: Job? = null
    private var scanJob: Job? = null

    init {
        // The last opened inverter loads on launch; onStart() then polls it.
        val last = store.lastOpenedId?.let(store::get)
        if (last != null) open(last, refresh = false) else startScan()
    }

    /** Foreground: poll every 60 s and tick the staleness clock. */
    fun onStart() {
        _state.update { it.copy(lastCheck = store.lastCheck) }
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            launch {
                while (isActive) {
                    _state.update { it.copy(nowMs = System.currentTimeMillis()) }
                    delay(5_000)
                }
            }
            while (isActive) {
                // The info tab reads only on its button.
                if (_state.value.tab != Tab.INFO) refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun onStop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Background polls only show the header spinner; a pull also shows the pull indicator. */
    fun refresh(source: RefreshSource = RefreshSource.BACKGROUND) {
        val manual = source != RefreshSource.BACKGROUND
        val inv = _state.value.current ?: return
        if (fetchLock.isLocked) {
            if (!manual) return
            // A poll is already running: let it stand for this manual request, so the
            // pull indicator shows until it ends (otherwise the indicator gets stuck).
            if (_state.value.loading) {
                _state.update { it.copy(refreshSource = source, error = null) }
                return
            }
            // A write or info read holds the device: show progress now, read right after it.
            _state.update { it.copy(loading = true, refreshSource = source, error = null) }
        }
        viewModelScope.launch {
            fetchLock.withLock {
                _state.update { it.copy(loading = true, refreshSource = source, error = if (manual) null else it.error) }
                try {
                    val snap = InverterApi.readWithRetry(inv.host, inv.port) { n -> _state.update { it.copy(attempt = n) } }
                    store.saveSnapshot(inv.id, snap)
                    notify(inv, snap)
                    if (_state.value.current?.id == inv.id) {
                        _state.update { it.copy(snapshot = snap, error = null, nowMs = System.currentTimeMillis()) }
                    }
                } catch (e: Exception) {
                    if (_state.value.current?.id == inv.id) {
                        _state.update { it.copy(error = "Не вдалося отримати дані після ${InverterApi.ATTEMPTS} спроб: ${describe(e)}") }
                    }
                } finally {
                    _state.update { it.copy(loading = false, refreshSource = RefreshSource.BACKGROUND, attempt = 0) }
                }
            }
        }
    }

    fun open(inv: Inverter, refresh: Boolean = true) {
        val opened = inv.copy(lastOpenedMs = System.currentTimeMillis())
        store.save(opened)
        store.lastOpenedId = opened.id
        _state.update {
            it.copy(
                screen = Screen.MAIN, tab = Tab.STATUS, current = opened, inverters = store.all(),
                snapshot = store.snapshot(opened.id), error = null, writeMessage = null,
                info = null, infoError = null,
            )
        }
        stopScan()
        if (refresh) refresh(RefreshSource.USER)
    }

    fun showList() {
        _state.update { it.copy(screen = Screen.LIST, inverters = store.all(), addError = null) }
        startScan()
    }

    fun backToMain() {
        if (_state.value.current != null) {
            stopScan()
            _state.update { it.copy(screen = Screen.MAIN) }
        }
    }

    fun selectTab(tab: Tab) {
        val leftInfo = _state.value.tab == Tab.INFO && tab != Tab.INFO
        _state.update { it.copy(tab = tab, writeMessage = null) }
        // Polling was paused on the info tab: catch up if the data went stale meanwhile.
        if (leftInfo && _state.value.stale) refresh()
    }

    /** Everything the device reports, including all control registers 3000..3039. */
    fun fetchInfo() {
        val inv = _state.value.current ?: return
        viewModelScope.launch {
            if (fetchLock.isLocked) return@launch
            fetchLock.withLock {
                _state.update { it.copy(infoLoading = true, infoError = null) }
                try {
                    val snap = InverterApi.readWithRetry(inv.host, inv.port, registers = InverterApi.ALL_REGISTERS)
                    store.saveSnapshot(inv.id, snap)
                    notify(inv, snap)
                    if (_state.value.current?.id == inv.id) {
                        _state.update { it.copy(info = snap, snapshot = snap, error = null, nowMs = System.currentTimeMillis()) }
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(infoError = "Не вдалося отримати дані: ${describe(e)}") }
                } finally {
                    _state.update { it.copy(infoLoading = false) }
                }
            }
        }
    }

    fun startScan() {
        scanJob?.cancel()
        _state.update { it.copy(discovered = emptyList(), scanning = true, addError = null, scanNetwork = discovery.ownNetwork()) }
        scanJob = viewModelScope.launch {
            try {
                discovery.scan().collect { f ->
                    _state.update { s ->
                        if (s.discovered.any { it.host == f.host && it.port == f.port }) s
                        else s.copy(discovered = s.discovered + f)
                    }
                }
            } finally {
                _state.update { it.copy(scanning = false) }
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /** Connect to a discovered or manually entered address, save it and open it. */
    fun connect(address: String, defaultPort: Int = EmsSession.DEFAULT_PORT, knownSerial: String? = null) {
        // Accepts "host" or "host:port".
        val h = address.trim().substringBefore(':')
        val port = address.trim().substringAfter(':', "").toIntOrNull() ?: defaultPort
        if (h.isEmpty()) return
        _state.update { it.copy(connecting = h, addError = null) }
        viewModelScope.launch {
            try {
                val sn = InverterApi.probe(h, port, timeoutMs = InverterApi.TIMEOUT_MS)?.takeIf { it.isNotEmpty() } ?: knownSerial
                val id = sn ?: "$h:$port"
                val existing = store.get(id)
                val inv = existing?.copy(host = h, port = port) ?: Inverter(id = id, host = h, port = port, name = sn?.let { "TSUN $it" } ?: h)
                _state.update { it.copy(connecting = null) }
                open(inv)
            } catch (e: Exception) {
                _state.update { it.copy(connecting = null, addError = "$h:$port — ${describe(e)}") }
            }
        }
    }

    fun remove(inv: Inverter) {
        store.remove(inv.id)
        _state.update {
            it.copy(
                inverters = store.all(),
                current = if (it.current?.id == inv.id) null else it.current,
                snapshot = if (it.current?.id == inv.id) null else it.snapshot,
            )
        }
    }

    fun setMonitor(enabled: Boolean) {
        store.monitorEnabled = enabled
        val app = getApplication<Application>()
        if (enabled) Monitor.enable(app) else Monitor.disable(app)
        _state.update { it.copy(monitorEnabled = enabled) }
    }

    fun setAlwaysShowStatus(enabled: Boolean) {
        store.alwaysShowStatus = enabled
        Monitor.refreshStatus(getApplication())
        _state.update { it.copy(alwaysShowStatus = enabled) }
    }

    /** The app's own polls keep notifications current too, so the pinned status follows the screen. */
    private fun notify(inv: Inverter, snap: Snapshot) {
        if (store.monitorEnabled) Monitor.process(getApplication(), store, inv, snap)
    }

    fun saveCapacity(capacityWh: Double) {
        val inv = _state.value.current ?: return
        val updated = inv.copy(capacityWh = capacityWh)
        store.save(updated)
        _state.update { it.copy(current = updated, inverters = store.all()) }
    }

    fun writeSettings(chargePowerW: Int?, maxSoc: Int?, minSoc: Int = Period.MIN_SOC) {
        val inv = _state.value.current ?: return
        _state.update { it.copy(writing = true, writeMessage = null) }
        viewModelScope.launch {
            val msg = fetchLock.withLock {
                try {
                    val previous = InverterApi.writeActivePeriod(inv.host, inv.port, chargePowerW, maxSoc, minSoc)
                    "Збережено. Було: $previous"
                } catch (e: Exception) {
                    "Помилка запису: ${if (e is IllegalArgumentException || e is IllegalStateException) e.message else describe(e)}"
                }
            }
            _state.update { it.copy(writing = false, writeMessage = msg) }
            if (_state.value.tab == Tab.INFO) fetchInfo() else refresh()
        }
    }

    fun writeRegister(register: Int, value: String) {
        val inv = _state.value.current ?: return
        _state.update { it.copy(writing = true, writeMessage = null) }
        viewModelScope.launch {
            val msg = fetchLock.withLock {
                try {
                    val previous = InverterApi.writeRegister(inv.host, inv.port, register, value)
                    "Регістр $register збережено. Було: $previous"
                } catch (e: Exception) {
                    "Помилка запису $register: ${if (e is IllegalStateException) e.message else describe(e)}"
                }
            }
            _state.update { it.copy(writing = false, writeMessage = msg) }
            if (_state.value.tab == Tab.INFO) fetchInfo() else refresh()
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 60_000L

        fun describe(e: Throwable): String = when {
            e is java.net.SocketTimeoutException -> "інвертор не відповів за 10 с"
            e is java.net.ConnectException || e.message?.contains("ECONNREFUSED") == true -> "з'єднання відхилено"
            e is java.net.NoRouteToHostException || e.message?.contains("EHOSTUNREACH") == true -> "адреса недоступна"
            e is java.net.UnknownHostException -> "невідома адреса"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
