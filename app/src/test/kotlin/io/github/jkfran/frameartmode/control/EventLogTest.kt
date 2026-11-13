package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.testing.MemoryText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {
    private fun entry(time: Long, outcome: Outcome = Outcome.ART_CONFIRMED, detail: String? = null) =
        EventLog.Entry(time, Trigger.DREAM, Step.READ_BACK, outcome, detail)

    @Test
    fun `round trip keeps order and fields`() {
        val storage = MemoryText()
        val log = EventLog(storage)
        log.append(entry(1, Outcome.TV_OFF, "PowerState standby"))
        log.append(entry(2))
        val reread = EventLog(storage).entries()
        assertEquals(listOf(1L, 2L), reread.map { it.time })
        assertEquals(Outcome.TV_OFF, reread[0].outcome)
        assertEquals("PowerState standby", reread[0].detail)
        assertNull(reread[1].detail)
        assertEquals(Trigger.DREAM, reread[1].trigger)
        assertEquals(Step.READ_BACK, reread[1].step)
    }

    @Test
    fun `keeps the last entries only`() {
        val log = EventLog(MemoryText(), capacity = 50)
        repeat(60) { log.append(entry(it.toLong())) }
        val entries = log.entries()
        assertEquals(50, entries.size)
        assertEquals(10L, entries.first().time)
        assertEquals(59L, entries.last().time)
    }

    @Test
    fun `never stores a token`() {
        val storage = MemoryText()
        EventLog(storage).append(entry(1, Outcome.CONNECT_FAILED, "wss://tv:8002/x?name=a&token=12345678 failed"))
        assertFalse(storage.value!!.contains("12345678"))
        assertTrue(storage.value!!.contains("token=***"))
    }

    @Test
    fun `long details are cut`() {
        val log = EventLog(MemoryText())
        log.append(entry(1, detail = "x".repeat(1_000)))
        assertEquals(300, log.entries().single().detail!!.length)
    }

    @Test
    fun `unreadable storage is an empty log`() {
        assertTrue(EventLog(MemoryText("not json")).entries().isEmpty())
        val unknown = """[{"time":1,"trigger":"DREAM","step":"SETUP","outcome":"SOMETHING_NEW"},""" +
            """{"time":2,"trigger":"DREAM","step":"SETUP","outcome":"TV_OFF"}]"""
        assertEquals(listOf(2L), EventLog(MemoryText(unknown)).entries().map { it.time })
    }

    @Test
    fun `clear empties the log`() {
        val log = EventLog(MemoryText())
        log.append(entry(1))
        log.clear()
        assertTrue(log.entries().isEmpty())
    }
}
