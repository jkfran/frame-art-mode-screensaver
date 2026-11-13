package io.github.jkfran.frameartmode.tv

/** Everything that can go wrong talking to the TV. */
sealed class TvException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** REST did not answer, or did not answer like a Samsung TV. */
    class Unreachable(message: String, cause: Throwable? = null) : TvException(message, cause)

    /**
     * The WebSocket could not be opened or closed during the handshake. [retryable] is true for
     * transient network failures (refused, timed out, TLS, closed before the TV answered).
     */
    class ConnectFailed(message: String, val retryable: Boolean, cause: Throwable? = null) :
        TvException(message, cause)

    /** `ms.channel.unauthorized`: the TV denied this client. */
    class AccessDenied : TvException("ms.channel.unauthorized")

    /** `ms.channel.timeOut`, or no answer to the handshake in time. */
    class ChannelTimeout(message: String) : TvException(message)

    /** `ms.error` from the TV. */
    class TvError(val tvMessage: String?) : TvException("ms.error: ${tvMessage ?: "no message"}")

    /** The TV answered [request] with an error sub-event. */
    class RequestFailed(val request: String, val code: Int?, val rawCode: String?) :
        TvException("$request failed with error ${rawCode ?: "unknown"}")

    /** No correlated reply to [request] before its deadline. */
    class RequestTimeout(val request: String) : TvException("no reply to $request")

    /** The socket closed while [request] was pending, or the frame could not be sent. */
    class ConnectionLost(val request: String) : TvException("connection closed during $request")
}
