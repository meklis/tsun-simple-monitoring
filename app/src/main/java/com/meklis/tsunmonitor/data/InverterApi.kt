package com.meklis.tsunmonitor.data

import android.util.Log
import com.meklis.tsunmonitor.protocol.EmsSession
import com.meklis.tsunmonitor.protocol.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** Device operations. Every call opens its own session and closes it. */
object InverterApi {
    const val ATTEMPTS = 3
    const val RETRY_DELAY_MS = 5_000L
    const val TIMEOUT_MS = 10_000
    private const val QUICK_CONNECT_MS = 2_000

    /** Read only by the info tab's button; regular polls read just the periods. */
    val ALL_REGISTERS = (3000..3039).toList()

    /** Nothing answers at the address at all, as opposed to a device that is busy or failed. */
    fun isUnreachable(e: Throwable) = e is ConnectException || e is NoRouteToHostException ||
        (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)

    suspend fun read(
        host: String,
        port: Int,
        connectTimeoutMs: Int = TIMEOUT_MS,
        registers: List<Int> = Period.REGISTERS,
        temperature: Boolean = true,
    ): Snapshot = withContext(Dispatchers.IO) {
        EmsSession(host, port, connectTimeoutMs, TIMEOUT_MS).use { s ->
            val energy = s.energyParameter()
            val regs = s.registers(registers)
            // A failed pass-through must not fail the whole poll.
            val modbus = if (temperature) {
                runCatching { s.modbusRead(1, Snapshot.TEMPERATURE_REG, 1) }
                    .onFailure { Log.w("TsunApi", "temperature read failed", it) }
                    .getOrNull()
            } else null
            Snapshot.parse(System.currentTimeMillis(), energy, regs, modbus.orEmpty())
        }
    }

    /** Up to [attempts] reads, [RETRY_DELAY_MS] apart; [onAttempt] gets the attempt number. */
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

    /**
     * Background read inside a time-limited broadcast: a 2 s connect, no retry when
     * nothing answers ([isUnreachable]), one retry when the device answered but failed.
     */
    suspend fun readForMonitor(host: String, port: Int): Snapshot = try {
        read(host, port, connectTimeoutMs = QUICK_CONNECT_MS, temperature = false)
    } catch (e: Exception) {
        if (isUnreachable(e)) throw e
        delay(RETRY_DELAY_MS)
        read(host, port, temperature = false)
    }

    /** Storage serial number, or "" when the device does not report one. */
    suspend fun probe(host: String, port: Int): String = withContext(Dispatchers.IO) {
        EmsSession(host, port, TIMEOUT_MS, TIMEOUT_MS).use { s ->
            s.energyParameter().optJSONArray("Storage_list")?.optJSONObject(0)?.optString("StorageSN").orEmpty()
        }
    }

    /**
     * Charge power and SOC limits of the active period. The register is re-read in the same
     * session, only these fields are replaced. Returns the previous raw value.
     */
    suspend fun writeActivePeriod(host: String, port: Int, chargePowerW: Int?, maxSoc: Int?, minSoc: Int): String =
        withContext(Dispatchers.IO) {
            EmsSession(host, port, TIMEOUT_MS, TIMEOUT_MS).use { s ->
                val regs = s.registers(Period.REGISTERS)
                val current = Period.active(Period.REGISTERS.mapNotNull { Period.parse(it, regs[it]) })
                    ?: error("Немає періоду розкладу")
                val newMax = maxSoc ?: current.maxSoc
                require(minSoc in 0 until newMax && newMax <= 100) { "Неправильні межі SOC: $minSoc–$newMax" }
                val updated = current.with(powerW = chargePowerW?.let { -it }, maxSoc = newMax, minSoc = minSoc)
                s.writeVerified(current.register, current.raw(), updated.raw())
                current.raw()
            }
        }

    /** One raw register value. Returns the previous value. */
    suspend fun writeRegister(host: String, port: Int, register: Int, value: String): String? =
        withContext(Dispatchers.IO) {
            EmsSession(host, port, TIMEOUT_MS, TIMEOUT_MS).use { s ->
                val previous = s.registers(listOf(register))[register]
                s.writeVerified(register, previous, value)
                previous
            }
        }

    private fun EmsSession.writeVerified(register: Int, current: String?, value: String) {
        if (current == value) return
        setRegisters(mapOf(register to value))
        val readBack = registers(listOf(register))[register]
        check(readBack == value) { "Інвертор не застосував значення (прочитано: $readBack)" }
    }
}
