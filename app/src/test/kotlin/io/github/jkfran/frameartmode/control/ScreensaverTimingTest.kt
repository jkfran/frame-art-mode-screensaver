package io.github.jkfran.frameartmode.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screensaver delay against the device's sleep time. */
class ScreensaverTimingTest {
    private val minute = 60_000L

    @Test
    fun `the sleep limit is the smaller positive timeout`() {
        assertNull(ScreensaverTiming.sleepLimitMs(null, null))
        assertNull(ScreensaverTiming.sleepLimitMs(-1, 0))
        assertEquals(20 * minute, ScreensaverTiming.sleepLimitMs(20 * minute, -1))
        assertEquals(20 * minute, ScreensaverTiming.sleepLimitMs(-1, 20 * minute))
        assertEquals(30 * minute, ScreensaverTiming.sleepLimitMs(60 * minute, 30 * minute))
        assertEquals(15 * minute, ScreensaverTiming.sleepLimitMs(15 * minute, null))
    }

    @Test
    fun `a delay at or above the sleep limit leaves no time for the screensaver`() {
        assertTrue(ScreensaverTiming.fits(60 * minute, null))
        assertTrue(ScreensaverTiming.fits(15 * minute, 30 * minute))
        assertFalse(ScreensaverTiming.fits(30 * minute, 30 * minute))
        assertFalse(ScreensaverTiming.fits(60 * minute, 30 * minute))
    }

    @Test
    fun `only delays shorter than the sleep limit are offered`() {
        assertEquals(ScreensaverTiming.DELAY_CHOICES_MS, ScreensaverTiming.choices(null))
        assertEquals(listOf(1L, 2L, 5L, 10L).map { it * minute }, ScreensaverTiming.choices(15 * minute))
        assertEquals(listOf(1L, 2L, 5L, 10L, 15L).map { it * minute }, ScreensaverTiming.choices(20 * minute))
        assertTrue(ScreensaverTiming.choices(minute).isEmpty())
    }

    @Test
    fun `the suggested delay always leaves time for the screensaver`() {
        assertEquals(5 * minute, ScreensaverTiming.suggestedDelayMs(5 * minute, 30 * minute))
        assertEquals(10 * minute, ScreensaverTiming.suggestedDelayMs(60 * minute, 30 * minute))
        assertEquals(10 * minute, ScreensaverTiming.suggestedDelayMs(null, null))
        assertEquals(2 * minute, ScreensaverTiming.suggestedDelayMs(null, 5 * minute))
        assertEquals(30_000L, ScreensaverTiming.suggestedDelayMs(10 * minute, minute))
        for (limit in listOf(minute, 5 * minute, 15 * minute, 20 * minute, 60 * minute)) {
            assertTrue(ScreensaverTiming.fits(ScreensaverTiming.suggestedDelayMs(60 * minute, limit), limit))
        }
    }
}
