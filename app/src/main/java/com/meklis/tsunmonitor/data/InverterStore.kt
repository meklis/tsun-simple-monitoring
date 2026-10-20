package com.meklis.tsunmonitor.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Inverter(
    /** Storage serial number, or "host:port" when unknown. */
    val id: String,
    val host: String,
    val port: Int,
    val name: String,
    val capacityWh: Double = DEFAULT_CAPACITY_WH,
    val lastOpenedMs: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("host", host).put("port", port).put("name", name)
        .put("capacityWh", capacityWh).put("lastOpenedMs", lastOpenedMs)

    companion object {
        /** TSUN MAU5000: 5.12 kWh per unit. */
        const val DEFAULT_CAPACITY_WH = 5120.0

        fun fromJson(o: JSONObject) = Inverter(
            id = o.getString("id"),
            host = o.getString("host"),
            port = o.optInt("port", 8080),
            name = o.optString("name", o.getString("id")),
            capacityWh = o.optDouble("capacityWh", DEFAULT_CAPACITY_WH),
            lastOpenedMs = o.optLong("lastOpenedMs"),
        )
    }
}

/** Saved inverters, last opened one and cached snapshots in SharedPreferences. */
class InverterStore(context: Context) {
    private val prefs = context.getSharedPreferences("inverters", Context.MODE_PRIVATE)

    fun all(): List<Inverter> {
        val arr = JSONArray(prefs.getString(KEY_LIST, "[]"))
        return (0 until arr.length()).map { Inverter.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.lastOpenedMs }
    }

    fun get(id: String): Inverter? = all().firstOrNull { it.id == id }

    /** Insert or replace by id. */
    fun save(inv: Inverter) {
        val list = all().filterNot { it.id == inv.id } + inv
        prefs.edit().putString(KEY_LIST, JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun remove(id: String) {
        val list = all().filterNot { it.id == id }
        val editor = prefs.edit()
            .putString(KEY_LIST, JSONArray(list.map { it.toJson() }).toString())
            .remove(KEY_SNAPSHOT + id)
            .remove(KEY_GRID + id)
            .remove(KEY_DISMISSED + id)
            .remove(KEY_LOW + id)
        if (lastOpenedId == id) editor.remove(KEY_LAST)
        editor.apply()
    }

    var lastOpenedId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    /** Background monitoring switched on by the user. */
    var monitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITOR, false)
        set(value) = prefs.edit().putBoolean(KEY_MONITOR, value).apply()

    /** Status notification is pinned and always shown, not only without grid or below max SOC. */
    var alwaysShowStatus: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS, value).apply()

    /** Last background check: time and a short result, shown in the settings for diagnosis. */
    var lastCheck: Pair<Long, String>?
        get() = if (prefs.contains(KEY_CHECK_TIME)) prefs.getLong(KEY_CHECK_TIME, 0) to prefs.getString(KEY_CHECK_RESULT, "").orEmpty() else null
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_CHECK_TIME).remove(KEY_CHECK_RESULT)
            else putLong(KEY_CHECK_TIME, value.first).putString(KEY_CHECK_RESULT, value.second)
        }.apply()

    /** Lowest low-battery threshold already alerted (20 or 10), null when SOC is above them. */
    fun lowBatteryAlerted(id: String): Int? = if (prefs.contains(KEY_LOW + id)) prefs.getInt(KEY_LOW + id, 0) else null

    fun setLowBatteryAlerted(id: String, level: Int?) = prefs.edit().apply {
        if (level == null) remove(KEY_LOW + id) else putInt(KEY_LOW + id, level)
    }.apply()

    /** Last known grid state of an inverter seen by the monitor (null = never known). */
    fun lastGridState(id: String): Boolean? =
        if (prefs.contains(KEY_GRID + id)) prefs.getBoolean(KEY_GRID + id, false) else null

    fun setLastGridState(id: String, value: Boolean) = prefs.edit().putBoolean(KEY_GRID + id, value).apply()

    /** The user swiped away the status notification; keep it hidden until the condition clears. */
    fun statusDismissed(id: String): Boolean = prefs.getBoolean(KEY_DISMISSED + id, false)

    fun setStatusDismissed(id: String, value: Boolean) = prefs.edit().putBoolean(KEY_DISMISSED + id, value).apply()

    fun snapshot(id: String): Snapshot? =
        prefs.getString(KEY_SNAPSHOT + id, null)?.let { runCatching { Snapshot.fromJson(JSONObject(it)) }.getOrNull() }

    fun saveSnapshot(id: String, s: Snapshot) {
        prefs.edit().putString(KEY_SNAPSHOT + id, s.toJson().toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_LAST = "last"
        const val KEY_SNAPSHOT = "snapshot_"
        const val KEY_MONITOR = "monitor_enabled"
        const val KEY_GRID = "monitor_last_grid_"
        const val KEY_DISMISSED = "monitor_dismissed_"
        const val KEY_ALWAYS = "monitor_always_status"
        const val KEY_LOW = "monitor_low_battery_"
        const val KEY_CHECK_TIME = "monitor_last_check"
        const val KEY_CHECK_RESULT = "monitor_last_check_result"
    }
}
