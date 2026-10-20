package com.meklis.tsunmonitor

import com.meklis.tsunmonitor.discovery.ownSubnetHosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetTest {
    @Test
    fun ownSlash24() {
        val h = ownSubnetHosts("192.168.1.73", 24)
        assertEquals(254, h.size)
        assertEquals("192.168.1.1", h.first())
        assertEquals("192.168.1.254", h.last())
    }

    @Test
    fun smallAndLargeNetworks() {
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), ownSubnetHosts("10.0.0.2", 30))
        assertEquals(1022, ownSubnetHosts("192.168.4.7", 22).size)
        // Too large to scan whole: a /24 around the own address.
        val big = ownSubnetHosts("10.20.30.40", 16)
        assertEquals(254, big.size)
        assertTrue(big.all { it.startsWith("10.20.30.") })
        assertTrue(ownSubnetHosts("bad", 24).isEmpty())
    }
}
