package com.meklis.tsunmonitor.data

import com.meklis.tsunmonitor.protocol.Period
import org.json.JSONArray
import org.json.JSONObject

/** One poll result. Raw JSON is kept so it can be cached and re-parsed. */
data class Snapshot(
    val timeMs: Long,
    val serial: String?,
    val soc: Double?,
    /** TotalGridOutputPower: negative = import from grid, positive = feed-in. */
    val gridPowerW: Double?,
    val acChargeW: Double?,
    val batteryChargeW: Double?,
    val batteryOutputW: Double?,
    /** Grid-Off sockets load: Storage_list OffGridLoadPower, in W. */
    val offGridW: Double?,
    val pvW: Double?,
    val smartLoadW: Double?,
    val storageStatus: Int?,
    val periods: List<Period>,
    /** Control registers as read (3000..3039 from the app, periods only from the monitor). */
    val registers: Map<Int, String>,
    /** Inverter temperature, Modbus 30073 x 0.1 (matches the TSUN app). */
    val temperatureC: Double?,
    val raw: JSONObject,
) {
    val activePeriod: Period? get() = Period.active(periods)

    /**
     * Whole home consumption, as TSUN Smart's "Home power" (= off-grid + house load +
     * intelligent load): what comes in from grid and PV minus what goes into the battery.
     * Without grid draw the home runs from the battery: its output is used instead.
     */
    val homeW: Double?
        get() {
            val importW = maxOf(-(gridPowerW ?: return null), 0.0)
            val pv = pvW ?: 0.0
            val home = if (importW > 0) importW + pv - (batteryChargeW ?: 0.0) else (batteryOutputW ?: 0.0) + pv
            return maxOf(home, offGridW ?: 0.0)
        }

    /** Output not measured as off-grid: socket 1 plus inverter losses. */
    val houseLoadW: Double? get() = homeW?.let { maxOf(it - (offGridW ?: 0.0) - (smartLoadW ?: 0.0), 0.0) }

    /** Grid presence; null while it cannot be determined. */
    val gridPresent: Boolean? get() = GridDetector.detect(this)

    fun toJson(): JSONObject = JSONObject().put("timeMs", timeMs).put("raw", raw)

    companion object {
        const val TEMPERATURE_REG = 30073

        fun parse(timeMs: Long, energy: JSONObject, registers: Map<Int, String>, modbus: Map<Int, Int> = emptyMap()): Snapshot {
            val sum = energy.optJSONObject("SSumInfoList") ?: JSONObject()
            val storage = energy.optJSONArray("Storage_list")?.optJSONObject(0)
            val periods = Period.REGISTERS.mapNotNull { Period.parse(it, registers[it]) }
            val raw = JSONObject().put("energy", energy).put("registers", JSONObject(registers.mapKeys { it.key.toString() }))
                .put("modbus", JSONObject(modbus.mapKeys { it.key.toString() }))
            return Snapshot(
                timeMs = timeMs,
                serial = storage?.optString("StorageSN")?.takeIf { it.isNotEmpty() },
                soc = sum.num("AverageBatteryAverageSOC") ?: storage?.num("BatterySoc"),
                gridPowerW = sum.num("TotalGridOutputPower"),
                acChargeW = sum.num("TotalACChargePower"),
                batteryChargeW = sum.num("TotalChargePower"),
                batteryOutputW = sum.num("TotalBatteryOutputPower"),
                // Unlike other Storage_list powers (0.1 W) this one is already in W;
                // TotalBackUpPower carries the same value wrongly divided by 10.
                offGridW = storage?.num("OffGridLoadPower") ?: sum.num("TotalBackUpPower")?.times(10),
                pvW = sum.num("TotalPVPower"),
                smartLoadW = sum.num("TotalSmartLoadElectricalPower"),
                storageStatus = storage?.optInt("StorageStatus"),
                periods = periods,
                registers = registers,
                temperatureC = modbus[TEMPERATURE_REG]?.let { it / 10.0 },
                raw = raw,
            )
        }

        fun fromJson(o: JSONObject): Snapshot {
            val raw = o.getJSONObject("raw")
            val regs = raw.optJSONObject("registers") ?: JSONObject()
            val map = regs.keys().asSequence().associate { it.toInt() to regs.getString(it) }
            val mb = raw.optJSONObject("modbus") ?: JSONObject()
            val modbus = mb.keys().asSequence().associate { it.toInt() to mb.getInt(it) }
            return parse(o.getLong("timeMs"), raw.getJSONObject("energy"), map, modbus)
        }

        private fun JSONObject.num(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
    }
}

/**
 * The device reports no explicit grid flag, so presence is inferred:
 *  - power is drawn from the grid -> present;
 *  - the battery is charging -> present;
 *  - the home consumes power but nothing comes from the grid -> absent;
 *  - battery is below its max SOC and not charging -> absent;
 *  - anything else -> unknown (null).
 */
object GridDetector {
    fun detect(s: Snapshot): Boolean? {
        val importW = maxOf(-(s.gridPowerW ?: 0.0), s.acChargeW ?: 0.0)
        if (importW > 0) return true
        if ((s.batteryChargeW ?: 0.0) > 0) return true
        if ((s.homeW ?: 0.0) > 0) return false
        val soc = s.soc ?: return null
        val maxSoc = s.activePeriod?.maxSoc ?: return null
        val charging = (s.batteryChargeW ?: 0.0) > 0 || (s.acChargeW ?: 0.0) > 0
        return if (soc < maxSoc && !charging) false else null
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
