package com.meklis.tsunmonitor.data

object Estimates {
    /** Charging losses: the time to full is stretched by this factor. */
    const val CHARGE_MARGIN = 1.05

    /**
     * Hours until the battery reaches [targetSoc] at the current charge power,
     * or null when it is not charging from the grid.
     */
    fun hoursToFull(soc: Double?, targetSoc: Int, chargeW: Double?, capacityWh: Double, gridPresent: Boolean?): Double? {
        if (soc == null || chargeW == null || gridPresent == false) return null
        if (chargeW <= 0 || soc >= targetSoc) return null
        return (targetSoc - soc) / 100.0 * capacityWh / chargeW * CHARGE_MARGIN
    }

    /** Hours until the battery drops to [minSoc] at the current consumption. */
    fun hoursToEmpty(soc: Double?, minSoc: Int, consumptionW: Double?, capacityWh: Double): Double? {
        if (soc == null || consumptionW == null || consumptionW <= 0 || soc <= minSoc) return null
        return (soc - minSoc) / 100.0 * capacityWh / consumptionW
    }

    fun format(hours: Double?): String? {
        if (hours == null || hours.isInfinite() || hours.isNaN()) return null
        val totalMin = (hours * 60).toLong()
        if (totalMin >= 99 * 60) return "> 99 год"
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h год $m хв" else "$m хв"
    }
}
