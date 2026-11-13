package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.testing.FAST
import io.github.jkfran.frameartmode.testing.Frames
import io.github.jkfran.frameartmode.testing.frameInfo
import io.github.jkfran.frameartmode.testing.jsonResponse
import io.github.jkfran.frameartmode.tv.FrameTvClient
import io.github.jkfran.frameartmode.tv.TvException
import io.github.jkfran.frameartmode.tv.TvPorts
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DiscoveryTest {
    @Test
    fun `a 24-bit subnet without network, broadcast and own address`() {
        val hosts = Discovery.hostsAround("192.168.1.10", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.10" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun `wide subnets are narrowed to the device's 24`() {
        val hosts = Discovery.hostsAround("10.0.37.5", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.0.37.") })
    }

    @Test
    fun `narrow subnets are scanned as they are`() {
        assertEquals(listOf("192.168.1.9"), Discovery.hostsAround("192.168.1.10", 30))
        assertTrue(Discovery.hostsAround("192.168.1.10", 31).isEmpty())
        assertTrue(Discovery.hostsAround("not an ip", 24).isEmpty())
    }

    @Test
    fun `IPv4 validation`() {
        assertTrue(Ipv4.isValid("192.168.1.20"))
        assertTrue(Ipv4.isValid(" 10.0.0.1 "))
        for (bad in listOf("", "192.168.1", "192.168.1.256", "1.2.3.4.5", "a.b.c.d", "192.168.01234.1", "1..2.3")) {
            assertFalse(bad, Ipv4.isValid(bad))
        }
        assertEquals("255.255.255.255", Ipv4.format(Ipv4.parse("255.255.255.255")!!))
        assertNull(Ipv4.parse("-1.0.0.0"))
    }

    @Test
    fun `Frames are listed first, then by address`() = runTest {
        val answers = mapOf(
            "192.168.1.30" to frameInfo(frame = false),
            "192.168.1.4" to frameInfo(),
            "192.168.1.200" to frameInfo(id = "uuid:tv-2"),
        )
        val found = Discovery(probe = { answers[it] }).scan(Discovery.hostsAround("192.168.1.10", 24))
        assertEquals(listOf("192.168.1.4", "192.168.1.200", "192.168.1.30"), found.map { it.host })
    }

    @Test
    fun `probes run in parallel up to the limit`() = runTest {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        Discovery(probe = {
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(100)
            running.decrementAndGet()
            null
        }, parallelism = 8).scan(Discovery.hostsAround("192.168.1.10", 24))
        assertEquals(8, peak.get())
    }

    @Test
    fun `probing a real address reads the device info`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(jsonResponse(Frames.deviceInfo()))
            server.enqueue(jsonResponse("{}"))
            val client = FrameTvClient(TvPorts(rest = server.port, restTlsFallback = null))
            val discovery = Discovery(probe = { host ->
                try {
                    client.deviceInfo(host, FAST)
                } catch (_: TvException) {
                    null
                }
            })
            val found = discovery.scan(listOf(server.hostName, server.hostName))
            assertEquals(1, found.size)
            assertTrue(found.single().info.frameTvSupport)
        } finally {
            server.close()
        }
    }
}
