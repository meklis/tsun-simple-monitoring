package com.meklis.tsunmonitor.monitor

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.meklis.tsunmonitor.R
import com.meklis.tsunmonitor.data.Inverter
import com.meklis.tsunmonitor.data.InverterApi
import com.meklis.tsunmonitor.data.InverterStore
import com.meklis.tsunmonitor.data.Snapshot
import com.meklis.tsunmonitor.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Low battery alerts, highest first. */
private val LOW_LEVELS = listOf(20, 10)

/** SOC must rise this much above a threshold before it can alert again. */
private const val LOW_HYSTERESIS = 3

/**
 * Low battery decision for one reading. [alerted] is the lowest threshold already alerted.
 * Returns the threshold to alert now (or null) and the new [alerted] value.
 */
fun lowBatteryStep(soc: Double, alerted: Int?): Pair<Int?, Int?> {
    val reached = LOW_LEVELS.lastOrNull { soc <= it }
    if (reached != null && (alerted == null || reached < alerted)) return reached to reached
    // Thresholds already alerted stay so until SOC is clearly back above them.
    val kept = LOW_LEVELS.filter { alerted != null && it >= alerted && soc < it + LOW_HYSTERESIS }.minOrNull()
    return null to kept
}

/**
 * Background monitoring without a service: an allow-while-idle alarm fires every 5 minutes
 * (Doze may stretch it to ~9 min) and [MonitorReceiver] checks all saved inverters.
 * Joining Wi-Fi is reported by the system through a PendingIntent and triggers a check too.
 */
object Monitor {
    private const val TAG = "TsunMonitor"
    private const val INTERVAL_MS = 5 * 60_000L

    /** The app polled recently: reuse its data instead of a second connection. */
    private const val REUSE_MS = 4 * 60_000L

    /** Background broadcasts are time-limited. */
    private const val CHECK_BUDGET_MS = 55_000L

    const val ACTION_CHECK = "com.meklis.tsunmonitor.CHECK"
    const val ACTION_WIFI = "com.meklis.tsunmonitor.WIFI"
    const val ACTION_DISMISSED = "com.meklis.tsunmonitor.DISMISSED"
    const val EXTRA_ID = "id"

    private const val CH_STATUS = "status"
    private const val CH_ALERTS = "grid_alerts"
    private const val CH_BATTERY = "battery_low"
    private const val ALERT_ID_BASE = 1000
    private const val STATUS_ID_BASE = 2000
    private const val LOW_ID_BASE = 3000

    fun enable(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Стан батареї", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Відключення мережі", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_BATTERY, "Низький заряд", NotificationManager.IMPORTANCE_HIGH))
        scheduleNext(context, delayMs = 5_000)
        context.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            receiverIntent(context, ACTION_WIFI, 2),
        )
    }

    fun disable(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(receiverIntent(context, ACTION_CHECK, 1))
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(receiverIntent(context, ACTION_WIFI, 2))
        }
        hideAllStatus(context, InverterStore(context))
    }

    fun scheduleNext(context: Context, delayMs: Long = INTERVAL_MS) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs
        val pi = receiverIntent(context, ACTION_CHECK, 1)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        }
    }

    private fun receiverIntent(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, code, Intent(context, MonitorReceiver::class.java).setAction(action),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // mutable: network extras
        )

    /** Checks every saved inverter; off Wi-Fi it only hides the status, whose data cannot be current. */
    suspend fun check(context: Context) {
        val store = InverterStore(context)
        if (!store.monitorEnabled) return
        if (!onLocalNetwork(context)) {
            hideAllStatus(context, store)
            report(store, "пропущено: немає Wi-Fi")
            return
        }
        val results = mutableListOf<String>()
        val done = withTimeoutOrNull(CHECK_BUDGET_MS) {
            for (inv in store.all()) {
                val cached = store.snapshot(inv.id)?.takeIf { System.currentTimeMillis() - it.timeMs < REUSE_MS }
                val snap = cached ?: try {
                    InverterApi.readForMonitor(inv.host, inv.port).also { store.saveSnapshot(inv.id, it) }
                } catch (e: Exception) {
                    // The last notification stays as it is.
                    results += "${inv.host}: " + if (InverterApi.isUnreachable(e)) "недоступний" else "помилка ${e.javaClass.simpleName}"
                    continue
                }
                results += "${inv.host}: ${if (cached != null) "дані застосунку" else "OK"}"
                process(context, store, inv, snap)
            }
        }
        if (done == null) results += "перевищено час"
        report(store, results.joinToString("; ").ifEmpty { "немає інверторів" })
    }

    private fun report(store: InverterStore, result: String) {
        Log.i(TAG, "check: $result")
        store.lastCheck = System.currentTimeMillis() to result
    }

    /** Alerts and status for one fresh snapshot; also called from the app's own polls. */
    fun process(context: Context, store: InverterStore, inv: Inverter, snap: Snapshot) {
        alertOnGridChange(context, store, inv, snap)
        alertOnLowBattery(context, store, inv, snap)
        updateStatus(context, store, inv, snap)
    }

    /**
     * After the "always show" switch changed: pinning shows the status of every inverter at once,
     * unpinning removes it; the regular status comes back only when its condition next changes.
     */
    fun refreshStatus(context: Context) {
        val store = InverterStore(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        for (inv in store.all()) {
            val snap = store.snapshot(inv.id)
            if (store.monitorEnabled && store.alwaysShowStatus && snap != null) {
                updateStatus(context, store, inv, snap)
            } else {
                nm.cancel(statusId(inv))
                store.setStatusDismissed(inv.id, true)
            }
        }
    }

    /** Android 14+ lets the user clear even ongoing notifications: a pinned status is put back at once. */
    fun onStatusDismissed(context: Context, id: String) {
        val store = InverterStore(context)
        if (store.monitorEnabled && store.alwaysShowStatus) {
            val inv = store.get(id) ?: return
            store.snapshot(id)?.let { updateStatus(context, store, inv, it) }
        } else {
            store.setStatusDismissed(id, true)
        }
    }

    private fun hideAllStatus(context: Context, store: InverterStore) {
        val nm = context.getSystemService(NotificationManager::class.java)
        store.all().forEach { nm.cancel(statusId(it)) }
    }

    private fun onLocalNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Shown while the grid is absent or SOC differs from the max SOC, or always (pinned) if asked for. */
    private fun updateStatus(context: Context, store: InverterStore, inv: Inverter, s: Snapshot) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!onLocalNetwork(context)) {
            nm.cancel(statusId(inv))
            return
        }
        val always = store.alwaysShowStatus
        val soc = s.soc?.roundToInt()
        val maxSoc = s.activePeriod?.maxSoc ?: 100
        if (!always && s.gridPresent != false && (soc == null || soc == maxSoc)) {
            nm.cancel(statusId(inv))
            store.setStatusDismissed(inv.id, false)
            return
        }
        if (!always && store.statusDismissed(inv.id)) return
        val grid = when (s.gridPresent) {
            true -> "є"
            false -> "немає"
            null -> "невідомо"
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val gridW = abs(s.gridPowerW ?: 0.0).roundToInt()
        val out = (s.homeW ?: 0.0).roundToInt()
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("${inv.name}: заряд ${soc ?: "—"} %")
            .setContentText("Мережа: $grid · з мережі $gridW Вт · вихід $out Вт · $time")
            .apply { if (soc != null) setProgress(100, soc, false) }
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setOngoing(always)
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context, statusId(inv),
                    Intent(context, MonitorReceiver::class.java).setAction(ACTION_DISMISSED).putExtra(EXTRA_ID, inv.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
        nm.notify(statusId(inv), n)
    }

    private fun alertOnGridChange(context: Context, store: InverterStore, inv: Inverter, s: Snapshot) {
        val now = s.gridPresent ?: return // unknown is not a change
        val before = store.lastGridState(inv.id)
        store.setLastGridState(inv.id, now)
        if (before == null || before == now) return
        store.setStatusDismissed(inv.id, false) // a grid change brings the status back
        val soc = s.soc?.roundToInt()?.let { " Заряд батареї $it %." } ?: ""
        alert(
            context, CH_ALERTS, ALERT_ID_BASE + idHash(inv), R.drawable.ic_bolt,
            (if (now) "Мережу відновлено" else "Мережу відключено") + " · ${inv.name}",
            (if (now) "Інвертор знову бачить мережу." else "Живлення від батареї.") + soc,
        )
    }

    private fun alertOnLowBattery(context: Context, store: InverterStore, inv: Inverter, s: Snapshot) {
        val soc = s.soc ?: return
        val alerted = store.lowBatteryAlerted(inv.id)
        val (reached, next) = lowBatteryStep(soc, alerted)
        if (next != alerted) store.setLowBatteryAlerted(inv.id, next)
        if (reached == null) return
        val grid = if (s.gridPresent == false) " Мережі немає." else ""
        alert(
            context, CH_BATTERY, LOW_ID_BASE + idHash(inv), R.drawable.ic_battery,
            "Заряд батареї ${soc.roundToInt()} % · ${inv.name}",
            "Заряд опустився до $reached %.$grid",
        )
    }

    private fun alert(context: Context, channel: String, id: Int, icon: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun idHash(inv: Inverter) = inv.id.hashCode() and 0xFFFF

    private fun statusId(inv: Inverter) = STATUS_ID_BASE + idHash(inv)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Alarm, Wi-Fi and notification-dismiss events; also re-arms monitoring after a reboot or app update. */
class MonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = InverterStore(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (store.monitorEnabled) Monitor.enable(context)
                return
            }
            Monitor.ACTION_DISMISSED -> {
                intent.getStringExtra(Monitor.EXTRA_ID)?.let { Monitor.onStatusDismissed(context, it) }
                return
            }
            Monitor.ACTION_CHECK, Monitor.ACTION_WIFI -> Unit
            else -> return
        }
        if (!store.monitorEnabled) return
        val pending = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsun:check").apply { acquire(60_000L) }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (intent.action == Monitor.ACTION_WIFI) delay(3_000) // let Wi-Fi become the default network
                Monitor.check(context)
            } finally {
                if (intent.action == Monitor.ACTION_CHECK) Monitor.scheduleNext(context)
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
            }
        }
    }
}
