package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.testing.CHOSEN_TV
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which TV a stored token and pairing belong to. */
class TvConfigTest {
    @Test
    fun `the same TV keeps its authorisation, also at a new address`() {
        assertTrue(CHOSEN_TV.keepsAuthorisationFrom(CHOSEN_TV))
        assertTrue(CHOSEN_TV.copy(host = "192.0.2.30").keepsAuthorisationFrom(CHOSEN_TV))
        // Matched by MAC address when one side has no ID; case does not matter.
        assertTrue(CHOSEN_TV.copy(tvId = null, wifiMac = "00:00:5e:00:53:01").keepsAuthorisationFrom(CHOSEN_TV))
        // Neither ID nor MAC address: the address decides.
        val bare = CHOSEN_TV.copy(tvId = null, wifiMac = null)
        assertTrue(bare.keepsAuthorisationFrom(bare))
    }

    @Test
    fun `another TV starts without the previous one's token, pairing or terms state`() {
        assertFalse(CHOSEN_TV.keepsAuthorisationFrom(null))
        assertFalse(CHOSEN_TV.copy(tvId = "uuid:tv-2").keepsAuthorisationFrom(CHOSEN_TV))
        assertFalse(CHOSEN_TV.copy(tvId = null, wifiMac = "00:00:5E:00:53:99").keepsAuthorisationFrom(CHOSEN_TV))
        val bare = CHOSEN_TV.copy(tvId = null, wifiMac = null)
        assertFalse(bare.copy(host = "192.0.2.30").keepsAuthorisationFrom(bare))
        // The ID wins over a matching address.
        assertFalse(CHOSEN_TV.copy(tvId = "uuid:tv-2", wifiMac = "00:00:5E:00:53:99").keepsAuthorisationFrom(CHOSEN_TV))
    }

    @Test
    fun `isSameTv is symmetric`() {
        val other = CHOSEN_TV.copy(tvId = "uuid:tv-2")
        assertFalse(CHOSEN_TV.isSameTv(other))
        assertFalse(other.isSameTv(CHOSEN_TV))
        assertTrue(CHOSEN_TV.isSameTv(CHOSEN_TV.copy(name = "Living room")))
    }
}
