package io.github.jkfran.frameartmode.control

/** How an attempt ended. Names are stored in the event log; do not rename them. */
enum class Outcome(
    /** The TV is, or was asked to be, in Art Mode. */
    val success: Boolean = false,
    /** Nothing worth showing on the screensaver itself. */
    val silent: Boolean = false,
) {
    NOT_CONFIGURED,
    /** Skipped: the TV refused earlier because its Art Store terms were not accepted. */
    TERMS_PENDING,
    /** No answer from the TV's REST API: it is most likely off. */
    TV_UNREACHABLE(silent = true),
    /** The TV reports standby. */
    TV_OFF(silent = true),
    /** The TV does not report its power state, so the app cannot tell that it is on. */
    POWER_STATE_UNKNOWN,
    /** Another TV answers at the stored address. */
    DIFFERENT_TV,
    NOT_A_FRAME,
    CONNECT_FAILED,
    ACCESS_DENIED,
    CHANNEL_TIMEOUT,
    TV_ERROR,
    ALREADY_ART(success = true, silent = true),
    /** Accepted, and read back as Art Mode. */
    ART_CONFIRMED(success = true, silent = true),
    /** Accepted; the read-back did not confirm it (yet). */
    ART_REQUESTED(success = true, silent = true),
    TERMS_NOT_ACCEPTED,
    ART_REQUEST_FAILED,
    REQUEST_TIMEOUT,
    CONNECTION_LOST,
    /** The screensaver stopped after the request was sent and before the TV answered. */
    ENDED_AFTER_REQUEST(silent = true),
    /** The screensaver stopped before anything was asked of the TV. */
    CANCELLED(silent = true),
    TIME_LIMIT,
    POWER_KEY_SENT(success = true, silent = true),
    POWER_KEY_FAILED,
    REMOTE_PAIRED(silent = true),
    /** "Check TV" found nothing wrong. */
    CHECK_OK(silent = true),
    UNEXPECTED_ERROR,
}

/** The step an attempt ended in. */
enum class Step { SETUP, DEVICE_INFO, CONNECT, GET_STATUS, SET_STATUS, READ_BACK, POWER_KEY, PAIRING }

/** What started an attempt. */
enum class Trigger { DREAM, TEST, CHECK, PAIRING }

data class AttemptResult(
    val outcome: Outcome,
    val step: Step,
    /** Technical detail for the event log (an error code, an exception). */
    val detail: String? = null,
    /** The power-key fallback, when it ran. */
    val fallback: AttemptResult? = null,
)
