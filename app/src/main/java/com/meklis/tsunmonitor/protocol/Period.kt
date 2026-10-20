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
         * The period's min SOC does not stop the off-grid sockets (tested down to 4 % below it),
         * so it is always written as 3 % and the battery is treated as usable down to that level.
         */
        const val MIN_SOC = 3

        fun parse(register: Int, raw: String?): Period? {
            val f = raw?.split(",") ?: return null
            if (f.size < 11) return null
            return runCatching { Period(register, f).also { it.powerW; it.maxSoc; it.minSoc } }.getOrNull()
        }

        /** First enabled period, else period 1. */
        fun active(periods: List<Period>): Period? = periods.firstOrNull { it.enabled } ?: periods.firstOrNull()
    }
}
