package com.meklis.tsunmonitor.data

import com.meklis.tsunmonitor.protocol.Period
import org.json.JSONArray
import org.json.JSONObject

/** One poll result. Raw JSON is kept so it can be cached and re-parsed. */
data class Snapshot(
    val timeMs: Long,
    val soc: Double?,
    /** TotalGridOutputPower: negative = import from grid, positive = feed-in. */
    val gridPowerW: Double?,
    val acChargeW: Double?,
    val batteryChargeW: Double?,
    val batteryOutputW: Double?,
    /** Socket 2 load (Storage_list OffGridLoadPower, W); socket 1 is not measured. */
    val offGridW: Double?,
    val pvW: Double?,
    val smartLoadW: Double?,
    val periods: List<Period>,
    /** Control registers as read: 3000..3039 on the info tab, the periods otherwise. */
    val registers: Map<Int, String>,
    /** Inverter temperature, Modbus 30073 x 0.1 (same as the TSUN app). */
    val temperatureC: Double?,
    val raw: JSONObject,
) {
    val activePeriod: Period? get() = Period.active(periods)

    /**
     * Whole inverter output, as TSUN Smart's "Home power": what comes from grid and PV
     * minus what goes into the battery, or the battery output when nothing comes from the grid.
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

    /**
     * The device has no grid flag, so presence is inferred; null when it cannot be told:
     *  - power is drawn from the grid -> present;
     *  - the battery is charging -> present;
     *  - the home consumes but nothing comes from the grid -> absent;
     *  - below max SOC and not charging -> absent.
     */
    val gridPresent: Boolean?
        get() {
            if (maxOf(-(gridPowerW ?: 0.0), acChargeW ?: 0.0) > 0) return true
            if ((batteryChargeW ?: 0.0) > 0) return true
            if ((homeW ?: 0.0) > 0) return false
            val soc = soc ?: return null
            val maxSoc = activePeriod?.maxSoc ?: return null
            return if (soc < maxSoc) false else null
        }

    fun toJson(): JSONObject = JSONObject().put("timeMs", timeMs).put("raw", raw)

    companion object {
        const val TEMPERATURE_REG = 30073

        fun parse(timeMs: Long, energy: JSONObject, registers: Map<Int, String>, modbus: Map<Int, Int> = emptyMap()): Snapshot {
            val sum = energy.optJSONObject("SSumInfoList") ?: JSONObject()
            val storage = energy.optJSONArray("Storage_list")?.optJSONObject(0)
            val raw = JSONObject().put("energy", energy)
                .put("registers", JSONObject(registers.mapKeys { it.key.toString() }))
                .put("modbus", JSONObject(modbus.mapKeys { it.key.toString() }))
            return Snapshot(
                timeMs = timeMs,
                soc = sum.num("AverageBatteryAverageSOC") ?: storage?.num("BatterySoc"),
                gridPowerW = sum.num("TotalGridOutputPower"),
                acChargeW = sum.num("TotalACChargePower"),
                batteryChargeW = sum.num("TotalChargePower"),
                batteryOutputW = sum.num("TotalBatteryOutputPower"),
                // Unlike other Storage_list powers (0.1 W) this one is in W;
                // TotalBackUpPower carries the same value divided by 10.
                offGridW = storage?.num("OffGridLoadPower") ?: sum.num("TotalBackUpPower")?.times(10),
                pvW = sum.num("TotalPVPower"),
                smartLoadW = sum.num("TotalSmartLoadElectricalPower"),
                periods = Period.REGISTERS.mapNotNull { Period.parse(it, registers[it]) },
                registers = registers,
                temperatureC = modbus[TEMPERATURE_REG]?.let { it / 10.0 },
                raw = raw,
            )
        }

        fun fromJson(o: JSONObject): Snapshot {
            val raw = o.getJSONObject("raw")
            val regs = raw.optJSONObject("registers") ?: JSONObject()
            val mb = raw.optJSONObject("modbus") ?: JSONObject()
            return parse(
                o.getLong("timeMs"),
                raw.getJSONObject("energy"),
                regs.keys().asSequence().associate { it.toInt() to regs.getString(it) },
                mb.keys().asSequence().associate { it.toInt() to mb.getInt(it) },
            )
        }

        private fun JSONObject.num(key: String): Double? =
            if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
    }
}

/** Charging losses stretch the time to full by this factor. */
private const val CHARGE_MARGIN = 1.05

/** Hours until [targetSoc] at the current charge power; null when not charging from the grid. */
fun hoursToFull(soc: Double?, targetSoc: Int, chargeW: Double?, capacityWh: Double, gridPresent: Boolean?): Double? {
    if (soc == null || chargeW == null || gridPresent == false || chargeW <= 0 || soc >= targetSoc) return null
    return (targetSoc - soc) / 100.0 * capacityWh / chargeW * CHARGE_MARGIN
}

/** Hours until the battery drops to [minSoc] at the current consumption. */
fun hoursToEmpty(soc: Double?, minSoc: Int, consumptionW: Double?, capacityWh: Double): Double? {
    if (soc == null || consumptionW == null || consumptionW <= 0 || soc <= minSoc) return null
    return (soc - minSoc) / 100.0 * capacityWh / consumptionW
}

fun formatHours(hours: Double?): String? {
    if (hours == null || hours.isInfinite() || hours.isNaN()) return null
    val totalMin = (hours * 60).toLong()
    if (totalMin >= 99 * 60) return "> 99 год"
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h год $m хв" else "$m хв"
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
