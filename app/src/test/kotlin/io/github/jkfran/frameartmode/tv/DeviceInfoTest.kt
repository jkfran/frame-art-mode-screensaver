package io.github.jkfran.frameartmode.tv

import io.github.jkfran.frameartmode.testing.Frames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoTest {
    @Test
    fun `parses a Frame in TV or Art Mode`() {
        val info = DeviceInfo.parse(Frames.deviceInfo())!!
        assertTrue(info.frameTvSupport)
        assertTrue(info.isOn)
        assertEquals("on", info.powerState)
        assertEquals("QE55LS03BAUXXU", info.modelName)
        assertEquals("Samsung The Frame 55", info.name)
        assertEquals("uuid:tv-1", info.id)
        assertEquals("00:00:5E:00:53:01", info.wifiMac)
        assertEquals(true, info.tokenAuthSupport)
    }

    @Test
    fun `standby and missing power state`() {
        val standby = DeviceInfo.parse(Frames.deviceInfo(power = "standby"))!!
        assertFalse(standby.isOn)
        val missing = DeviceInfo.parse(Frames.deviceInfo(power = null))!!
        assertNull(missing.powerState)
        assertFalse(missing.isOn)
    }

    @Test
    fun `Frame support as string or boolean`() {
        assertFalse(DeviceInfo.parse(Frames.deviceInfo(frame = "false"))!!.frameTvSupport)
        assertTrue(DeviceInfo.parse(Frames.deviceInfo(frame = true))!!.frameTvSupport)
        assertTrue(DeviceInfo.parse(Frames.deviceInfo(frame = "TRUE"))!!.frameTvSupport)
        assertFalse(DeviceInfo.parse(Frames.deviceInfo(frame = null))!!.frameTvSupport)
        assertFalse(DeviceInfo.parse(Frames.deviceInfo(frame = "yes"))!!.frameTvSupport)
    }

    @Test
    fun `not a device info body`() {
        assertNull(DeviceInfo.parse("<html></html>"))
        assertNull(DeviceInfo.parse("""{"name":"x"}"""))
        assertNull(DeviceInfo.parse("""{"device":"x"}"""))
        assertNull(DeviceInfo.parse(""))
    }

    @Test
    fun `html entities in the name`() {
        val info = DeviceInfo.parse("""{"device":{"name":"Living Room&#39;s &amp; &#x41;rt TV","FrameTVSupport":"true"}}""")!!
        assertEquals("Living Room's & Art TV", info.name)
    }

    @Test
    fun `id falls back to duid`() {
        val info = DeviceInfo.parse("""{"device":{"duid":"uuid:d"}}""")!!
        assertEquals("uuid:d", info.id)
    }
}
