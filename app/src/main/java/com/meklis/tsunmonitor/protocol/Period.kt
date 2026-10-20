package com.meklis.tsunmonitor.protocol

/**
 * Power control period register (3003..3018).
 *
 * Format: enabled,start,end,powerW,f5,mode,f7,f8,f9,maxSOC,minSOC
 * powerW < 0 means charging from the grid, > 0 discharging/feeding.
 */
data class Period(val register: Int, val fields: List<String>) {
    val enabled get() = fields[0] == "1"
    val start get() = fields[1]
    val end get() = fields[2]
    val powerW get() = fields[3].toDouble().toInt()
    val mode get() = fields[5].toDouble().toInt()
    val maxSoc get() = fields[9].toDouble().toInt()
    val minSoc get() = fields[10].toDouble().toInt()

    fun raw() = fields.joinToString(",")

    fun with(powerW: Int? = null, maxSoc: Int? = null, minSoc: Int? = null): Period {
        val f = fields.toMutableList()
        powerW?.let { f[3] = it.toString() }
        maxSoc?.let { f[9] = it.toString() }
        minSoc?.let { f[10] = it.toString() }
        return copy(fields = f)
    }

    companion object {
        val REGISTERS = (3003..3018).toList()

        /**
         * The inverter always switches the sockets off at 10 % SOC, whatever the registers say.
         * The period's min SOC is written as the same value, and the battery counts as empty there.
         */
        const val MIN_SOC = 10

        fun parse(register: Int, raw: String?): Period? {
            val f = raw?.split(",") ?: return null
            if (f.size < 11) return null
            return runCatching { Period(register, f).also { it.powerW; it.maxSoc; it.minSoc } }.getOrNull()
        }

        /** First enabled period, else period 1. */
        fun active(periods: List<Period>): Period? = periods.firstOrNull { it.enabled } ?: periods.firstOrNull()
    }
}
