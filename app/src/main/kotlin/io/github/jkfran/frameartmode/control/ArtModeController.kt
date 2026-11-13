package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.tv.ArtMode
import io.github.jkfran.frameartmode.tv.ArtProtocol
import io.github.jkfran.frameartmode.tv.ArtSession
import io.github.jkfran.frameartmode.tv.DeviceInfo
import io.github.jkfran.frameartmode.tv.FrameTv
import io.github.jkfran.frameartmode.tv.Logger
import io.github.jkfran.frameartmode.tv.SetReply
import io.github.jkfran.frameartmode.tv.TvException
import io.github.jkfran.frameartmode.tv.Timeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides what to send to the TV, and sends it.
 *
 * Rules: nothing is sent to a TV that is off, unknown or not the chosen one; Art Mode is
 * requested at most once per attempt and never re-sent; a refusal for the Art Store terms is
 * never retried within [TERMS_RETRY_AFTER_MS] except from the settings screen; the optional
 * power key is sent at most once, only when the art channel is missing or refused the request
 * outright, and only to a TV that reports being on right before the press; never after the TV
 * refused for its terms or denied access, after an Art Mode request that may have been carried
 * out, or after the TV went silent (it may be going to standby, or already showing art).
 */
class ArtModeController(
    private val tv: FrameTv,
    private val store: SettingsStore,
    private val eventLog: EventLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val logger: Logger = Logger.NONE,
) {
    /** What "Check TV" found. */
    data class CheckReport(
        val info: DeviceInfo?,
        val artMode: ArtMode?,
        val apiVersion: String?,
        val result: AttemptResult,
    )

    private class Progress {
        var step = Step.SETUP
        var setSent = false

        /** The art channel's result, once known. */
        var art: AttemptResult? = null

        /** The power key went out. A press toggles the TV, so the log names it whatever happens next. */
        var keySent = false
    }

    private class ArtOutcome(val result: AttemptResult, val fallbackAllowed: Boolean)

    /** Switches the chosen TV to Art Mode when it is on and not in Art Mode already. */
    suspend fun switchToArtMode(trigger: Trigger, timeouts: Timeouts): AttemptResult {
        val progress = Progress()
        val result = try {
            withTimeoutOrNull(timeouts.totalMs) { attempt(trigger, timeouts, progress) }
                ?: stopped(progress, AttemptResult(Outcome.TIME_LIMIT, progress.step, "stopped after ${timeouts.totalMs} ms"))
        } catch (e: CancellationException) {
            val ended = if (progress.setSent) Outcome.ENDED_AFTER_REQUEST else Outcome.CANCELLED
            record(trigger, stopped(progress, AttemptResult(ended, progress.step)))
            throw e
        } catch (e: Exception) {
            // A bug or an unexpected platform error: report it; nothing is retried.
            stopped(progress, AttemptResult(Outcome.UNEXPECTED_ERROR, progress.step, e.toString()))
        }
        record(trigger, result)
        return result
    }

    /**
     * [interruption], unless the power key had already gone out when the attempt was stopped:
     * then the attempt's result is the art channel's, with the key as its fallback.
     */
    private fun stopped(progress: Progress, interruption: AttemptResult): AttemptResult {
        if (!progress.keySent) return interruption
        val key = AttemptResult(Outcome.POWER_KEY_SENT, Step.POWER_KEY, "then ${interruption.outcome}")
        return (progress.art ?: interruption).copy(fallback = key)
    }

    private suspend fun attempt(trigger: Trigger, timeouts: Timeouts, progress: Progress): AttemptResult {
        val config = store.tv ?: return AttemptResult(Outcome.NOT_CONFIGURED, Step.SETUP)
        if (trigger == Trigger.DREAM) {
            val refusedAt = store.termsNotAcceptedAt
            if (refusedAt != null && clock() - refusedAt in 0 until TERMS_RETRY_AFTER_MS) {
                return AttemptResult(Outcome.TERMS_PENDING, Step.SETUP)
            }
        } else {
            // The user asked from the settings screen, typically after accepting the terms.
            store.termsNotAcceptedAt = null
        }

        progress.step = Step.DEVICE_INFO
        val info = try {
            tv.deviceInfo(config.host, timeouts)
        } catch (e: TvException) {
            return AttemptResult(Outcome.TV_UNREACHABLE, Step.DEVICE_INFO, e.message)
        }
        refusal(config, info)?.let { return it }

        progress.step = Step.CONNECT
        val art = artChannel(config, timeouts, progress)
        progress.art = art.result
        val fallback = if (art.fallbackAllowed && store.powerKeyFallback && store.remotePaired) {
            powerKey(config, timeouts, progress)
        } else {
            null
        }
        return art.result.copy(fallback = fallback)
    }

    /** Null when [info] is the chosen TV, on, and a Frame; otherwise why nothing may be sent. */
    private fun refusal(config: TvConfig, info: DeviceInfo): AttemptResult? = when {
        config.isDifferentFrom(info) ->
            AttemptResult(Outcome.DIFFERENT_TV, Step.DEVICE_INFO, "found ${info.modelName ?: "another TV"}")
        info.powerState == null -> AttemptResult(Outcome.POWER_STATE_UNKNOWN, Step.DEVICE_INFO)
        !info.isOn -> AttemptResult(Outcome.TV_OFF, Step.DEVICE_INFO, "PowerState ${info.powerState}")
        !info.frameTvSupport -> AttemptResult(Outcome.NOT_A_FRAME, Step.DEVICE_INFO, info.modelName)
        else -> null
    }

    private suspend fun artChannel(config: TvConfig, timeouts: Timeouts, progress: Progress): ArtOutcome {
        val session = try {
            openArt(config, timeouts)
        } catch (e: TvException) {
            return failure(e, progress)
        }
        try {
            progress.step = Step.GET_STATUS
            val before = try {
                session.artMode(timeouts.getMs)
            } catch (e: TvException.RequestFailed) {
                // The status could not be read; the request below decides.
                logger.log("Art status not readable: ${e.message}")
                ArtMode.UNKNOWN
            }
            if (before == ArtMode.ART) {
                return ArtOutcome(AttemptResult(Outcome.ALREADY_ART, Step.GET_STATUS), fallbackAllowed = false)
            }

            progress.step = Step.SET_STATUS
            progress.setSent = true
            val reply = try {
                session.setArtMode(on = true, timeoutMs = timeouts.setMs)
            } catch (e: TvException.RequestFailed) {
                val detail = "error_code ${e.rawCode ?: "missing"}"
                if (e.code == ArtProtocol.ERROR_TERMS_NOT_ACCEPTED) {
                    store.termsNotAcceptedAt = clock()
                    return ArtOutcome(AttemptResult(Outcome.TERMS_NOT_ACCEPTED, Step.SET_STATUS, detail), false)
                }
                return ArtOutcome(AttemptResult(Outcome.ART_REQUEST_FAILED, Step.SET_STATUS, detail), true)
            }
            if (reply == SetReply.ANNOUNCED) {
                // The TV said so itself; a read-back would race the sleep it is about to cause.
                return ArtOutcome(AttemptResult(Outcome.ART_CONFIRMED, Step.SET_STATUS, "announced by the TV"), false)
            }

            // Informational: the TV may already be putting this device to sleep over HDMI-CEC.
            progress.step = Step.READ_BACK
            val after = try {
                session.artMode(timeouts.readBackMs)
            } catch (e: TvException) {
                logger.log("Read-back after the request: ${e.message}")
                ArtMode.UNKNOWN
            }
            val outcome = if (after == ArtMode.ART) Outcome.ART_CONFIRMED else Outcome.ART_REQUESTED
            return ArtOutcome(AttemptResult(outcome, Step.READ_BACK), fallbackAllowed = false)
        } catch (e: TvException) {
            return failure(e, progress)
        } finally {
            withContext(NonCancellable) { session.close(timeouts.closeMs) }
        }
    }

    /** Opens the art channel, retrying transient connection failures with backoff. */
    private suspend fun openArt(config: TvConfig, timeouts: Timeouts): ArtSession {
        var retries = 0
        while (true) {
            try {
                return tv.openArt(config.host, store.token, timeouts) { store.token = it }
            } catch (e: TvException.ConnectFailed) {
                if (!e.retryable || retries >= CONNECT_RETRY_DELAYS_MS.size) throw e
                logger.log("Art channel connection failed (${e.message}); retrying")
                pause(CONNECT_RETRY_DELAYS_MS[retries++])
            }
        }
    }

    private fun failure(e: TvException, progress: Progress): ArtOutcome {
        val step = progress.step
        val result = when (e) {
            is TvException.AccessDenied -> {
                forgetAuthorisation()
                AttemptResult(Outcome.ACCESS_DENIED, step, e.message)
            }
            is TvException.ConnectFailed, is TvException.Unreachable -> AttemptResult(Outcome.CONNECT_FAILED, step, e.message)
            is TvException.ChannelTimeout -> AttemptResult(Outcome.CHANNEL_TIMEOUT, step, e.message)
            is TvException.TvError -> AttemptResult(Outcome.TV_ERROR, step, e.tvMessage)
            is TvException.RequestFailed -> AttemptResult(Outcome.ART_REQUEST_FAILED, step, e.message)
            is TvException.RequestTimeout -> AttemptResult(Outcome.REQUEST_TIMEOUT, step, e.message)
            is TvException.ConnectionLost -> AttemptResult(Outcome.CONNECTION_LOST, step, e.message)
        }
        // The power key toggles. It may follow an art channel that is missing or refused the
        // request outright, but not silence (the TV may be going to standby, or showing art
        // already with its art service hung), and nothing at all once an Art Mode request was
        // sent that may have been carried out, whatever went wrong afterwards.
        val keyMayHelp = when (e) {
            is TvException.ConnectFailed, is TvException.Unreachable,
            is TvException.TvError, is TvException.RequestFailed -> true
            is TvException.AccessDenied, is TvException.ChannelTimeout,
            is TvException.RequestTimeout, is TvException.ConnectionLost -> false
        }
        return ArtOutcome(result, fallbackAllowed = keyMayHelp && !progress.setSent)
    }

    private suspend fun powerKey(config: TvConfig, timeouts: Timeouts, progress: Progress): AttemptResult {
        progress.step = Step.POWER_KEY
        // The art channel may have taken most of the attempt: the TV may have been switched off
        // (or another TV may answer) since the first read, and a press would then switch it on.
        val info = try {
            tv.deviceInfo(config.host, timeouts)
        } catch (e: TvException) {
            return AttemptResult(Outcome.TV_UNREACHABLE, Step.POWER_KEY, e.message)
        }
        refusal(config, info)?.let { return it.copy(step = Step.POWER_KEY) }
        return try {
            tv.clickPowerKey(config.host, store.token, timeouts, onSent = { progress.keySent = true }) { store.token = it }
            AttemptResult(Outcome.POWER_KEY_SENT, Step.POWER_KEY)
        } catch (e: TvException) {
            if (e is TvException.AccessDenied) forgetAuthorisation()
            val outcome = if (progress.keySent) Outcome.POWER_KEY_SENT else Outcome.POWER_KEY_FAILED
            AttemptResult(outcome, Step.POWER_KEY, e.message)
        }
    }

    /** Reads the TV's state for the settings screen. Sends no Art Mode request. */
    suspend fun check(timeouts: Timeouts): CheckReport {
        val report = withTimeoutOrNull(timeouts.totalMs) { checkNow(timeouts) }
            ?: CheckReport(null, null, null, AttemptResult(Outcome.TIME_LIMIT, Step.CONNECT))
        record(Trigger.CHECK, report.result)
        return report
    }

    private suspend fun checkNow(timeouts: Timeouts): CheckReport {
        val config = store.tv
            ?: return CheckReport(null, null, null, AttemptResult(Outcome.NOT_CONFIGURED, Step.SETUP))
        val info = try {
            tv.deviceInfo(config.host, timeouts)
        } catch (e: TvException) {
            return CheckReport(null, null, null, AttemptResult(Outcome.TV_UNREACHABLE, Step.DEVICE_INFO, e.message))
        }
        refusal(config, info)?.let { return CheckReport(info, null, null, it) }

        val progress = Progress().apply { step = Step.CONNECT }
        val session = try {
            openArt(config, timeouts)
        } catch (e: TvException) {
            return CheckReport(info, null, null, failure(e, progress).result)
        }
        return try {
            progress.step = Step.GET_STATUS
            val mode = try {
                session.artMode(timeouts.getMs)
            } catch (_: TvException.RequestFailed) {
                ArtMode.UNKNOWN
            }
            // For display only: a failure here must not hide the status just read.
            val version = try {
                session.apiVersion(timeouts.apiVersionMs)
            } catch (e: TvException) {
                logger.log("Art service version not readable: ${e.message}")
                null
            }
            CheckReport(info, mode, version, AttemptResult(Outcome.CHECK_OK, Step.GET_STATUS))
        } catch (e: TvException) {
            CheckReport(info, null, null, failure(e, progress).result)
        } finally {
            withContext(NonCancellable) { session.close(timeouts.closeMs) }
        }
    }

    /** Lets the TV ask once whether this app may use its remote-control channel. */
    suspend fun pairRemote(timeouts: Timeouts): AttemptResult {
        val result = withTimeoutOrNull(timeouts.totalMs) { pairNow(timeouts) }
            ?: AttemptResult(Outcome.TIME_LIMIT, Step.PAIRING)
        record(Trigger.PAIRING, result)
        return result
    }

    private suspend fun pairNow(timeouts: Timeouts): AttemptResult {
        val config = store.tv ?: return AttemptResult(Outcome.NOT_CONFIGURED, Step.SETUP)
        val info = try {
            tv.deviceInfo(config.host, timeouts)
        } catch (e: TvException) {
            return AttemptResult(Outcome.TV_UNREACHABLE, Step.DEVICE_INFO, e.message)
        }
        refusal(config, info)?.let { return it }
        return try {
            tv.pairRemote(config.host, store.token, timeouts) { store.token = it }
            store.remotePaired = true
            AttemptResult(Outcome.REMOTE_PAIRED, Step.PAIRING)
        } catch (e: TvException) {
            failure(e, Progress().apply { step = Step.PAIRING }).result
        }
    }

    private fun forgetAuthorisation() {
        store.token = null
        store.remotePaired = false
    }

    private fun record(trigger: Trigger, result: AttemptResult) {
        val now = clock()
        eventLog.append(EventLog.Entry(now, trigger, result.step, result.outcome, result.detail))
        logger.log("$trigger: ${result.outcome} at ${result.step}${EventLog.sanitize(result.detail)?.let { " ($it)" } ?: ""}")
        result.fallback?.let { record(trigger, it) }
    }

    companion object {
        /** Waits before the second and third connection attempts. */
        val CONNECT_RETRY_DELAYS_MS = listOf(1_000L, 2_000L)

        /** After the TV refused for its Art Store terms, the screensaver waits this long. */
        const val TERMS_RETRY_AFTER_MS = 24L * 60 * 60 * 1000
    }
}
