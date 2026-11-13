package io.github.jkfran.frameartmode.control

import kotlin.random.Random

/**
 * What the screensaver does once an attempt has ended. [io.github.jkfran.frameartmode.ArtModeDreamService]
 * only carries it out.
 *
 * After a successful request the screensaver keeps running: the TV puts this device to sleep over
 * HDMI-CEC once Art Mode is on, which ends it. A screensaver that finishes lets the device go to
 * sleep on its own, and the device would then tell the TV to turn off or to switch input.
 */
object DreamPolicy {
    data class Action(
        /** Shown on the screensaver; null when nothing needs the user's attention. */
        val notice: Outcome?,
        /** Ends the screensaver after this many milliseconds; null leaves it to the system. */
        val finishAfterMs: Long?,
    )

    /** [endAfterArt] and [endAfterSeconds] are the opt-in "End the screensaver after Art Mode starts". */
    fun after(result: AttemptResult, endAfterArt: Boolean, endAfterSeconds: Int): Action = Action(
        notice = notice(result),
        finishAfterMs = if (endAfterArt && requestedArtMode(result)) endAfterSeconds.coerceAtLeast(0) * 1_000L else null,
    )

    /** The first outcome that needs the user's attention, if any. */
    fun notice(result: AttemptResult): Outcome? =
        listOfNotNull(result, result.fallback).map { it.outcome }.firstOrNull { !it.silent }

    /** True when this attempt asked the TV for Art Mode (not when it was in Art Mode already). */
    fun requestedArtMode(result: AttemptResult): Boolean =
        result.outcome == Outcome.ART_CONFIRMED ||
            result.outcome == Outcome.ART_REQUESTED ||
            result.fallback?.outcome == Outcome.POWER_KEY_SENT

    /**
     * A random offset for the notice within [room] pixels of free space, away from the edge it
     * is laid out against. Horizontally that is the start edge, which is the right one in
     * right-to-left languages ([fromRightEdge]): the notice then moves left, so it stays on screen.
     */
    fun noticeOffset(room: Int, fromRightEdge: Boolean = false, random: Random = Random): Float {
        val offset = if (room > 0) random.nextInt(room + 1).toFloat() else 0f
        return if (fromRightEdge && offset > 0f) -offset else offset
    }
}
