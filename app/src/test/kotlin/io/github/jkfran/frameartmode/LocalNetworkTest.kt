package io.github.jkfran.frameartmode

import io.github.jkfran.frameartmode.LocalNetwork.Address
import io.github.jkfran.frameartmode.LocalNetwork.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which networks discovery scans. */
class LocalNetworkTest {
    private val wifi = Candidate(local = true, vpn = false, addresses = listOf(Address("192.168.1.10", 24)))
    private val ethernet = Candidate(local = true, vpn = false, addresses = listOf(Address("192.168.2.10", 24)))
    private val vpn = Candidate(local = false, vpn = true, addresses = listOf(Address("10.8.0.2", 16)))

    @Test
    fun `a VPN is never scanned, the network under it is`() {
        assertEquals(listOf(Address("192.168.1.10", 24)), LocalNetwork.localAddresses(listOf(vpn, wifi)))
    }

    @Test
    fun `Wi-Fi and Ethernet are scanned, other transports are not`() {
        val cellular = Candidate(local = false, vpn = false, addresses = listOf(Address("100.64.0.5", 10)))
        assertEquals(
            listOf(Address("192.168.1.10", 24), Address("192.168.2.10", 24)),
            LocalNetwork.localAddresses(listOf(wifi, cellular, ethernet, wifi)),
        )
        // A network that reports a local transport and a VPN one is a VPN.
        assertTrue(LocalNetwork.localAddresses(listOf(vpn.copy(local = true))).isEmpty())
    }
}
