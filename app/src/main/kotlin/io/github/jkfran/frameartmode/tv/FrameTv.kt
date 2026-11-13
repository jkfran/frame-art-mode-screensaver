package io.github.jkfran.frameartmode.tv

/** Art Mode as the TV reports it on the art channel. */
enum class ArtMode {
    ART,
    TV,
    UNKNOWN;

    companion object {
        /** `value` of a `get_artmode_status` reply: "on" is Art Mode, "off" is TV mode. */
        fun fromValue(value: String?): ArtMode = when (value?.trim()?.lowercase()) {
            "on" -> ART
            "off" -> TV
            else -> UNKNOWN
        }
    }
}

/** The TV's local API, as the app uses it. */
interface FrameTv {
    /** `GET /api/v2/`. Throws [TvException.Unreachable]. */
    suspend fun deviceInfo(host: String, timeouts: Timeouts): DeviceInfo

    /**
     * Opens the art channel and waits for `ms.channel.ready`. [onToken] receives a token the
     * TV issues. Throws [TvException] subclasses.
     */
    suspend fun openArt(host: String, token: String?, timeouts: Timeouts, onToken: (String) -> Unit): ArtSession

    /** Connects to the remote-control channel once, so the TV can ask to allow this app. */
    suspend fun pairRemote(host: String, token: String?, timeouts: Timeouts, onToken: (String) -> Unit)

    /**
     * Sends exactly one click of the power key over the remote-control channel. [onSent] runs as
     * soon as the key is handed to the connection, before the pause that follows it.
     */
    suspend fun clickPowerKey(
        host: String,
        token: String?,
        timeouts: Timeouts,
        onSent: () -> Unit = {},
        onToken: (String) -> Unit,
    )
}

/** An open art channel. One request is in flight at a time. */
interface ArtSession {
    suspend fun artMode(timeoutMs: Long): ArtMode

    /** Returns when the TV accepts the request; throws [TvException.RequestFailed] when it refuses. */
    suspend fun setArtMode(on: Boolean, timeoutMs: Long)

    /** The art API version, or null when the TV does not say. For display only. */
    suspend fun apiVersion(timeoutMs: Long): String?

    suspend fun close(timeoutMs: Long)
}
