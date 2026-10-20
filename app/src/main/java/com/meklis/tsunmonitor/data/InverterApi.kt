package com.meklis.tsunmonitor.data

import com.meklis.tsunmonitor.protocol.EmsSession
import com.meklis.tsunmonitor.protocol.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Device operations. Every call opens its own session and closes it. */
object InverterApi {
    const val ATTEMPTS = 3
    const val RETRY_DELAY_MS = 5_000L
    const val TIMEOUT_MS = 10_000

    /** Quick connect probe so unreachable inverters are skipped without retries. */
    const val QUICK_CONNECT_MS = 2_000

    /** Read only by the info tab's button; regular polls read just the periods (status + settings). */
    val ALL_REGISTERS = (3000..3039).toList()

    sealed interface MonitorResult {
        data class Ok(val snapshot: Snapshot) : MonitorResult
        data object Unreachable : MonitorResult
        data class Failed(val error: Exception) : MonitorResult
    }

    /**
     * Background read: a 2 s connect first. If the address does not answer at all,
     * give up at once; only a device that answered but failed gets one retry
     * (background checks run inside a time-limited broadcast).
     */
    suspend fun readForMonitor(host: String, port: Int): MonitorResult {
        try {
            return MonitorResult.Ok(read(host, port, connectTimeoutMs = QUICK_CONNECT_MS, temperature = false))
        } catch (e: java.net.ConnectException) {
            return MonitorResult.Unreachable
        } catch (e: java.net.NoRouteToHostException) {
            return MonitorResult.Unreachable
        } catch (e: java.net.SocketTimeoutException) {
            if (e.message?.contains("connect", ignoreCase = true) == true) return MonitorResult.Unreachable
        } catch (e: Exception) {
            // Answered but failed (e.g. busy with another client): retry below.
        }
        return try {
            delay(RETRY_DELAY_MS)
            MonitorResult.Ok(readWithRetry(host, port, attempts = 1))
        } catch (e: Exception) {
            MonitorResult.Failed(e)
        }
    }

    suspend fun read(
        host: String,
        port: Int,
        timeoutMs: Int = TIMEOUT_MS,
        connectTimeoutMs: Int = timeoutMs,
        registers: List<Int> = Period.REGISTERS,
        temperature: Boolean = true,
    ): Snapshot = withContext(Dispatchers.IO) {
        EmsSession(host, port, connectTimeoutMs, timeoutMs).use { s ->
            val energy = s.energyParameter()
            val regs = s.registers(registers)
            // Optional: a failed pass-through must not fail the whole poll.
            val modbus = if (temperature) {
                runCatching { s.modbusRead(1, Snapshot.TEMPERATURE_REG, 1) }
                    .onFailure { android.util.Log.w("TsunApi", "temperature read failed", it) }
                    .getOrNull()
            } else null
            Snapshot.parse(System.currentTimeMillis(), energy, regs, modbus.orEmpty())
        }
    }

    /**
     * Read with retries: up to [ATTEMPTS] attempts, [RETRY_DELAY_MS] apart.
     * [onAttempt] reports the attempt number before each try.
     */
    suspend fun readWithRetry(
        host: String,
        port: Int,
        attempts: Int = ATTEMPTS,
        registers: List<Int> = Period.REGISTERS,
        onAttempt: (Int) -> Unit = {},
    ): Snapshot {
        var last: Exception? = null
        repeat(attempts) { i ->
            onAttempt(i + 1)
            try {
                return read(host, port, registers = registers)
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) delay(RETRY_DELAY_MS)
            }
        }
        throw last ?: IllegalStateException("no attempts")
    }

    /** Quick probe used by discovery and manual add: returns the storage serial number. */
    suspend fun probe(host: String, port: Int, timeoutMs: Int = 3_000): String? = withContext(Dispatchers.IO) {
        EmsSession(host, port, timeoutMs, timeoutMs).use { s ->
            s.energyParameter().optJSONArray("Storage_list")?.optJSONObject(0)?.optString("StorageSN") ?: ""
        }
    }

    /**
     * Change charge power / SOC limits of the active period. The register is
     * re-read inside the same session, only the given fields are replaced and
     * the result is verified by reading it back. Returns the previous raw value.
     */
    suspend fun writeActivePeriod(
        host: String,
        port: Int,
        chargePowerW: Int?,
        maxSoc: Int?,
        minSoc: Int = Period.MIN_SOC,
    ): String = withContext(Dispatchers.IO) {
        EmsSession(host, port, TIMEOUT_MS, TIMEOUT_MS).use { s ->
            val periods = s.registers(Period.REGISTERS).let { regs -> Period.REGISTERS.mapNotNull { Period.parse(it, regs[it]) } }
            val current = Period.active(periods) ?: error("Немає періоду розкладу")
            val newMax = maxSoc ?: current.maxSoc
            require(minSoc in 0 until newMax && newMax <= 100) { "Неправильні межі SOC: $minSoc–$newMax" }
            val updated = current.with(powerW = chargePowerW?.let { -it }, maxSoc = newMax, minSoc = minSoc)
            if (updated.raw() != current.raw()) {
                s.setRegisters(mapOf(current.register to updated.raw()))
                val readBack = s.registers(listOf(current.register))[current.register]
                check(readBack == updated.raw()) { "Інвертор не застосував значення (прочитано: $readBack)" }
            }
            current.raw()
        }
    }

    /** Write one raw register value and verify it by reading back. Returns the previous value. */
    suspend fun writeRegister(host: String, port: Int, register: Int, value: String): String? = withContext(Dispatchers.IO) {
        EmsSession(host, port, TIMEOUT_MS, TIMEOUT_MS).use { s ->
            val previous = s.registers(listOf(register))[register]
            if (previous != value) {
                s.setRegisters(mapOf(register to value))
                val readBack = s.registers(listOf(register))[register]
                check(readBack == value) { "Інвертор не застосував значення (прочитано: $readBack)" }
            }
            previous
        }
    }
}
