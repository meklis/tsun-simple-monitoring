package com.meklis.tsunmonitor

import com.meklis.tsunmonitor.data.Snapshot
import com.meklis.tsunmonitor.data.formatHours
import com.meklis.tsunmonitor.data.hoursToEmpty
import com.meklis.tsunmonitor.data.hoursToFull
import com.meklis.tsunmonitor.monitor.lowBatteryStep
import com.meklis.tsunmonitor.protocol.Period
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    // Real EnergyParameter response from the TSOL-MAU5000.
    private val energy = JSONObject(
        """
        {"Response":"EnergyParameter","SerialNumber":1,"Target":"HA",
         "Storage_list":[{"DevAddr":1,"StorageSN":"N000000000000001","StorageStatus":1,"PvChargingPower":0,
           "AcChargingPower":24980,"BatterySoc":66,"BatteryDischargingPower":0,"AcInActivePower":-24980,
           "OffGridLoadPower":7,"BatteryChargingPower":0,"PvStringCount":0}],
         "SSumInfoList":{"ControlEnableStatus":1,"MeterTotalActivePower":0,"TotalPVPower":0,"TotalPVChargePower":0,
           "TotalACChargePower":2498,"AverageBatteryAverageSOC":66,"TotalBatteryOutputPower":0,
           "TotalGridOutputPower":-2498,"TotalBackUpPower":0.7,"TotalChargePower":2366}}
        """
    )
    private val regs = mapOf(
        3003 to "1,00:00,23:59,-2500,0,6,0,0,0,100,3",
        3004 to "0,00:00,00:00,0,0,0,0,0,0,100,10",
    )

    @Test
    fun parsesSnapshot() {
        val s = Snapshot.parse(1000, energy, regs)
        assertEquals(66.0, s.soc!!, 0.0)
        assertEquals(-2498.0, s.gridPowerW!!, 0.0)
        assertEquals(7.0, s.offGridW!!, 0.0) // OffGridLoadPower, W
        assertEquals(2366.0, s.batteryChargeW!!, 0.0)
        assertEquals(true, s.gridPresent)
        val p = s.activePeriod!!
        assertEquals(3003, p.register)
        assertEquals(-2500, p.powerW)
        assertEquals(100, p.maxSoc)
        assertEquals(3, p.minSoc)
    }

    @Test
    fun snapshotRoundTrip() {
        val s = Snapshot.parse(1234, energy, regs)
        val back = Snapshot.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s.copy(raw = back.raw), back)
    }

    @Test
    fun periodEditKeepsOtherFields() {
        val p = Period.parse(3003, regs[3003])!!
        assertEquals("1,00:00,23:59,-1500,0,6,0,0,0,95,15", p.with(powerW = -1500, maxSoc = 95, minSoc = 15).raw())
        assertEquals(p.raw(), p.with().raw())
    }

    @Test
    fun activeFallsBackToFirstPeriod() {
        val off = Period.REGISTERS.map { Period.parse(it, "0,00:00,00:00,0,0,0,0,0,0,100,10")!! }
        assertEquals(3003, Period.active(off)!!.register)
        assertNull(Period.parse(3003, "garbage"))
    }

    private fun withSum(soc: Int, grid: Double, acCharge: Double, charge: Double): Snapshot {
        val e = JSONObject(energy.toString())
        e.getJSONObject("SSumInfoList")
            .put("AverageBatteryAverageSOC", soc).put("TotalGridOutputPower", grid)
            .put("TotalACChargePower", acCharge).put("TotalChargePower", charge)
        return Snapshot.parse(0, e, regs) // active period: max SOC 100
    }

    @Test
    fun homePower() {
        // Values from TSUN Smart at 01:51: buy 12 W, battery 0 W, off-grid 6 W -> home 12, house 6.
        val e = JSONObject(energy.toString())
        e.getJSONObject("SSumInfoList").put("TotalGridOutputPower", -12).put("TotalChargePower", 0)
            .put("TotalACChargePower", 0).put("TotalBatteryOutputPower", 5)
        e.getJSONArray("Storage_list").getJSONObject(0).put("OffGridLoadPower", 6)
        val s = Snapshot.parse(0, e, regs)
        assertEquals(12.0, s.homeW!!, 0.0)
        assertEquals(6.0, s.houseLoadW!!, 0.0)
        // Charging from the grid: the charge is not home consumption.
        assertEquals(2498.0 - 2366.0, Snapshot.parse(0, energy, regs).homeW!!, 0.0)
        // No grid draw: home runs from the battery.
        e.getJSONObject("SSumInfoList").put("TotalGridOutputPower", 0).put("TotalBatteryOutputPower", 300)
        assertEquals(300.0, Snapshot.parse(0, e, regs).homeW!!, 0.0)
    }

    @Test
    fun gridDetection() {
        // Drawing from the grid -> present.
        assertEquals(true, withSum(66, -2498.0, 2498.0, 2366.0).gridPresent)
        // Below max SOC and not charging -> absent.
        assertEquals(false, withSum(80, 0.0, 0.0, 0.0).gridPresent)
        // Full battery, nothing flowing at all -> unknown.
        val idle = JSONObject(energy.toString())
        idle.getJSONObject("SSumInfoList").put("AverageBatteryAverageSOC", 100).put("TotalGridOutputPower", 0)
            .put("TotalACChargePower", 0).put("TotalChargePower", 0).put("TotalBatteryOutputPower", 0)
        idle.getJSONArray("Storage_list").getJSONObject(0).put("OffGridLoadPower", 0)
        assertNull(Snapshot.parse(0, idle, regs).gridPresent)
        // Home consumes but nothing comes from the grid -> absent, even at max SOC.
        idle.getJSONObject("SSumInfoList").put("TotalBatteryOutputPower", 40)
        assertEquals(false, Snapshot.parse(0, idle, regs).gridPresent)
        // Battery charging with no import reported -> present, even while the home consumes.
        idle.getJSONObject("SSumInfoList").put("TotalChargePower", 120)
        assertEquals(true, Snapshot.parse(0, idle, regs).gridPresent)
    }

    @Test
    fun estimates() {
        // 34 % of 5120 Wh at 2366 W, +5 %: ~0.77 h
        val full = hoursToFull(66.0, 100, 2366.0, 5120.0, true)!!
        assertEquals(0.34 * 5120 / 2366 * 1.05, full, 1e-9)
        assertNull(hoursToFull(66.0, 100, 2366.0, 5120.0, false))
        assertNull(hoursToFull(100.0, 100, 2366.0, 5120.0, true))
        // (80 - 10) % of 5120 Wh at 500 W: 7.168 h
        assertEquals(7.168, hoursToEmpty(80.0, 10, 500.0, 5120.0)!!, 1e-9)
        assertNull(hoursToEmpty(80.0, 10, 0.0, 5120.0))
        assertEquals("7 год 10 хв", formatHours(7.168))
        assertEquals("46 хв", formatHours(0.77))
        assertTrue(formatHours(500.0)!!.startsWith(">"))
    }

    @Test
    fun lowBatteryAlerts() {
        // Discharging 25 -> 9 %: one alert at 20, one at 10.
        var alerted: Int? = null
        val fired = mutableListOf<Int>()
        for (soc in 25 downTo 9) {
            val (now, next) = lowBatteryStep(soc.toDouble(), alerted)
            now?.let(fired::add)
            alerted = next
        }
        assertEquals(listOf(20, 10), fired)
        assertEquals(10, alerted)
        // Wobbling around 10 % does not repeat it; back above 13 % only the 20 % alert stays recorded.
        assertEquals(null to 10, lowBatteryStep(11.0, 10))
        assertEquals(null to 20, lowBatteryStep(14.0, 10))
        assertEquals(10 to 10, lowBatteryStep(10.0, 20))
        // Charged back above 23 %: everything re-armed.
        assertEquals(null to null, lowBatteryStep(24.0, 20))
        assertEquals(20 to 20, lowBatteryStep(20.0, null))
    }
}
