package com.meklis.tsunmonitor.discovery

/** IPv4 subnet helpers for the scan. */
object Subnet {
    /** Larger networks are not scanned whole: a /24 around the own address is used instead. */
    const val MIN_PREFIX = 22

    private fun toInt(ip: String): Int? {
        val p = ip.trim().split(".")
        if (p.size != 4) return null
        return p.fold(0) { acc, s -> (acc shl 8) or (s.toIntOrNull()?.takeIf { it in 0..255 } ?: return null) }
    }

    private fun toIp(v: Int) = "${v ushr 24 and 255}.${v ushr 16 and 255}.${v ushr 8 and 255}.${v and 255}"

    /** Host addresses of [ip]/[prefix] (network and broadcast excluded). */
    fun hosts(ip: String, prefix: Int): List<String> {
        val addr = toInt(ip) ?: return emptyList()
        if (prefix !in MIN_PREFIX..30) return emptyList()
        val mask = -1 shl (32 - prefix)
        val network = addr and mask
        val size = 1 shl (32 - prefix)
        return (1 until size - 1).map { toIp(network + it) }
    }

    /** Own network for the automatic scan; too large networks fall back to a /24. */
    fun ownHosts(ip: String, prefix: Int): List<String> =
        hosts(ip, if (prefix in MIN_PREFIX..30) prefix else 24)
}
