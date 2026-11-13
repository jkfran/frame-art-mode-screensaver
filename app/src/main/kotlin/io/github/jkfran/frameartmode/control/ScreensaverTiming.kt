package io.github.jkfran.frameartmode.control

/**
 * When Android shows the screensaver. It starts after `screen_off_timeout` of inactivity and can
 * run until the sleep limit, the smaller of `sleep_timeout` and `attentive_timeout` (AOSP
 * PowerManagerService clamps the screen-off time to both). With a delay at or above that limit
 * there is no time left for a screensaver: the device goes straight to sleep, and as the TV's
 * active source it then sends `<Standby>` or `<Inactive Source>` instead of Art Mode starting.
 */
object ScreensaverTiming {
    const val DEFAULT_DELAY_MS = 10L * 60 * 1000

    /** The delays the settings screen offers, before [choices] drops those that are too long. */
    val DELAY_CHOICES_MS = listOf(1L, 2L, 5L, 10L, 15L, 30L, 60L).map { it * 60_000 }

    /**
     * The idle time after which the device sleeps, from `sleep_timeout` and `attentive_timeout`
     * (null when a setting is missing or unreadable; zero or negative means never). Null when
     * neither limits it.
     */
    fun sleepLimitMs(sleepTimeoutMs: Long?, attentiveTimeoutMs: Long?): Long? =
        listOfNotNull(sleepTimeoutMs, attentiveTimeoutMs).filter { it > 0 }.minOrNull()

    /** True when a screensaver that starts after [delayMs] gets any time before the device sleeps. */
    fun fits(delayMs: Long, sleepLimitMs: Long?): Boolean = sleepLimitMs == null || delayMs < sleepLimitMs

    /** The offered delays that leave the screensaver time to run. */
    fun choices(sleepLimitMs: Long?): List<Long> = DELAY_CHOICES_MS.filter { fits(it, sleepLimitMs) }

    /**
     * The delay to put in the ADB commands: [currentMs] when it fits, otherwise the longest
     * offered delay up to [DEFAULT_DELAY_MS] that does, otherwise half the sleep limit.
     */
    fun suggestedDelayMs(currentMs: Long?, sleepLimitMs: Long?): Long =
        currentMs?.takeIf { it > 0 && fits(it, sleepLimitMs) }
            ?: choices(sleepLimitMs).lastOrNull { it <= DEFAULT_DELAY_MS }
            ?: ((sleepLimitMs ?: (2 * DEFAULT_DELAY_MS)) / 2)
}
