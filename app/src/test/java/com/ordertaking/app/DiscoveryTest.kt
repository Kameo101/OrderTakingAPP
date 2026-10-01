package com.ordertaking.app

import com.ordertaking.app.net.ipToString
import com.ordertaking.app.net.probeForKitchen
import com.ordertaking.app.net.subnetHosts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class DiscoveryTest {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = (a shl 24) or (b shl 16) or (c shl 8) or d

    @Test
    fun androidHotspotSubnetCoversEveryOtherDevice() {
        val hosts = subnetHosts(ip(192, 168, 43, 57), 24).map(::ipToString)
        assertEquals(253, hosts.size)
        assertEquals("192.168.43.1", hosts.first())
        assertEquals("192.168.43.254", hosts.last())
        assertTrue("192.168.43.57" !in hosts)
    }

    @Test
    fun iphoneHotspotSmallSubnet() {
        // iPhone Personal Hotspot hands out 172.20.10.x/28.
        val hosts = subnetHosts(ip(172, 20, 10, 3), 28).map(::ipToString)
        assertEquals((1..14).filter { it != 3 }.map { "172.20.10.$it" }, hosts)
    }

    @Test
    fun largeNetworksOnlyScanOwnSlash24() {
        assertEquals(253, subnetHosts(ip(10, 0, 5, 9), 16).size)
    }

    @Test
    fun probeFindsTheOpenPort() = runBlocking {
        // Bind to 127.0.0.1 only; on Linux the whole 127/8 range would otherwise answer.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val found = probeForKitchen(listOf("127.0.0.2", "127.0.0.1", "127.0.0.3"), server.localPort)
            assertEquals("127.0.0.1", found)
        }
    }

    @Test
    fun probeReturnsNullWhenNothingAnswers() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listening
        assertNull(probeForKitchen(listOf("127.0.0.1"), port))
    }
}
