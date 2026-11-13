package io.github.jkfran.frameartmode.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What the screensaver does after an attempt. */
class DreamPolicyTest {
    private fun result(outcome: Outcome, fallback: Outcome? = null) = AttemptResult(
        outcome,
        Step.SET_STATUS,
        fallback = fallback?.let { AttemptResult(it, Step.POWER_KEY) },
    )

    @Test
    fun `after a successful request the screensaver keeps running by default`() {
        for (outcome in listOf(Outcome.ART_CONFIRMED, Outcome.ART_REQUESTED)) {
            val action = DreamPolicy.after(result(outcome), endAfterArt = false, endAfterSeconds = 10)
            assertNull(action.finishAfterMs)
            assertNull(action.notice)
        }
        val keyed = DreamPolicy.after(
            result(Outcome.CONNECT_FAILED, Outcome.POWER_KEY_SENT),
            endAfterArt = false,
            endAfterSeconds = 10,
        )
        assertNull(keyed.finishAfterMs)
    }

    @Test
    fun `ending early is opt-in and only follows a request`() {
        assertEquals(10_000L, DreamPolicy.after(result(Outcome.ART_REQUESTED), true, 10).finishAfterMs)
        assertEquals(
            5_000L,
            DreamPolicy.after(result(Outcome.CONNECT_FAILED, Outcome.POWER_KEY_SENT), true, 5).finishAfterMs,
        )
        // Already in Art Mode, a failure, nothing done: never finished by the app.
        for (outcome in listOf(Outcome.ALREADY_ART, Outcome.TERMS_NOT_ACCEPTED, Outcome.TV_OFF, Outcome.TIME_LIMIT)) {
            assertNull(outcome.name, DreamPolicy.after(result(outcome), true, 10).finishAfterMs)
        }
        assertNull(DreamPolicy.after(result(Outcome.CONNECT_FAILED, Outcome.POWER_KEY_FAILED), true, 10).finishAfterMs)
    }

    @Test
    fun `only outcomes that need the user are shown`() {
        for (outcome in Outcome.entries.filter { it.silent }) {
            assertNull(outcome.name, DreamPolicy.notice(result(outcome)))
        }
        assertEquals(Outcome.TERMS_NOT_ACCEPTED, DreamPolicy.notice(result(Outcome.TERMS_NOT_ACCEPTED)))
        assertEquals(Outcome.CONNECT_FAILED, DreamPolicy.notice(result(Outcome.CONNECT_FAILED, Outcome.POWER_KEY_SENT)))
        assertEquals(Outcome.POWER_KEY_FAILED, DreamPolicy.notice(result(Outcome.ALREADY_ART, Outcome.POWER_KEY_FAILED)))
    }

    @Test
    fun `the notice moves away from the edge it starts at`() {
        val random = Random(42)
        repeat(200) {
            val ltr = DreamPolicy.noticeOffset(300, fromRightEdge = false, random = random)
            assertTrue(ltr in 0f..300f)
            val rtl = DreamPolicy.noticeOffset(300, fromRightEdge = true, random = random)
            assertTrue(rtl in -300f..0f)
        }
        assertEquals(0f, DreamPolicy.noticeOffset(0, fromRightEdge = true))
        assertEquals(0f, DreamPolicy.noticeOffset(-20))
    }
}
