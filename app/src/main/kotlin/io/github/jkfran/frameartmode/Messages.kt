package io.github.jkfran.frameartmode

import android.content.Context
import android.text.format.DateFormat
import io.github.jkfran.frameartmode.control.AttemptResult
import io.github.jkfran.frameartmode.control.EventLog
import io.github.jkfran.frameartmode.control.Outcome
import io.github.jkfran.frameartmode.control.Trigger
import java.util.Date

/** User-facing texts for outcomes and log entries. */
object Messages {
    fun outcomeText(outcome: Outcome): Int = when (outcome) {
        Outcome.NOT_CONFIGURED -> R.string.outcome_not_configured
        Outcome.TERMS_PENDING -> R.string.outcome_terms_pending
        Outcome.TV_UNREACHABLE -> R.string.outcome_tv_unreachable
        Outcome.TV_OFF -> R.string.outcome_tv_off
        Outcome.POWER_STATE_UNKNOWN -> R.string.outcome_power_state_unknown
        Outcome.DIFFERENT_TV -> R.string.outcome_different_tv
        Outcome.NOT_A_FRAME -> R.string.outcome_not_a_frame
        Outcome.CONNECT_FAILED -> R.string.outcome_connect_failed
        Outcome.ACCESS_DENIED -> R.string.outcome_access_denied
        Outcome.CHANNEL_TIMEOUT -> R.string.outcome_channel_timeout
        Outcome.TV_ERROR -> R.string.outcome_tv_error
        Outcome.ALREADY_ART -> R.string.outcome_already_art
        Outcome.ART_CONFIRMED -> R.string.outcome_art_confirmed
        Outcome.ART_REQUESTED -> R.string.outcome_art_requested
        Outcome.TERMS_NOT_ACCEPTED -> R.string.outcome_terms_not_accepted
        Outcome.ART_REQUEST_FAILED -> R.string.outcome_art_request_failed
        Outcome.REQUEST_TIMEOUT -> R.string.outcome_request_timeout
        Outcome.CONNECTION_LOST -> R.string.outcome_connection_lost
        Outcome.ENDED_AFTER_REQUEST -> R.string.outcome_ended_after_request
        Outcome.CANCELLED -> R.string.outcome_cancelled
        Outcome.TIME_LIMIT -> R.string.outcome_time_limit
        Outcome.POWER_KEY_SENT -> R.string.outcome_power_key_sent
        Outcome.POWER_KEY_FAILED -> R.string.outcome_power_key_failed
        Outcome.REMOTE_PAIRED -> R.string.outcome_remote_paired
        Outcome.CHECK_OK -> R.string.outcome_check_ok
        Outcome.UNEXPECTED_ERROR -> R.string.outcome_unexpected_error
    }

    fun triggerText(trigger: Trigger): Int = when (trigger) {
        Trigger.DREAM -> R.string.trigger_dream
        Trigger.TEST -> R.string.trigger_test
        Trigger.CHECK -> R.string.trigger_check
        Trigger.PAIRING -> R.string.trigger_pairing
    }

    /** The outcome, its detail, and the fallback's outcome when one ran. */
    fun describe(context: Context, result: AttemptResult): String {
        val lines = mutableListOf(withDetail(context, context.getString(outcomeText(result.outcome)), result.detail))
        result.fallback?.let { lines += withDetail(context, context.getString(outcomeText(it.outcome)), it.detail) }
        return lines.joinToString("\n")
    }

    fun logLine(context: Context, entry: EventLog.Entry): String {
        val date = Date(entry.time)
        val time = DateFormat.getDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
        val text = withDetail(context, context.getString(outcomeText(entry.outcome)), entry.detail)
        return context.getString(R.string.log_line, time, context.getString(triggerText(entry.trigger)), text)
    }

    private fun withDetail(context: Context, text: String, detail: String?): String =
        if (detail.isNullOrBlank()) text else context.getString(R.string.log_detail, text, detail)
}
