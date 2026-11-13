package io.github.jkfran.frameartmode.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** An OkHttp WebSocket whose incoming frames are read one at a time with a deadline. */
internal class TvSocket private constructor(
    private val webSocket: WebSocket,
    private val frames: Channel<String>,
    private val closed: CompletableDeferred<Unit>,
) {
    sealed interface Next {
        class Text(val text: String) : Next
        data object Timeout : Next
        data object Closed : Next
    }

    /** The next frame, or [Next.Timeout] after [timeoutMs], or [Next.Closed]. */
    suspend fun next(timeoutMs: Long): Next {
        if (timeoutMs <= 0) {
            val result = frames.tryReceive()
            return result.getOrNull()?.let { Next.Text(it) } ?: if (result.isClosed) Next.Closed else Next.Timeout
        }
        val result = withTimeoutOrNull(timeoutMs) { frames.receiveCatching() } ?: return Next.Timeout
        return result.getOrNull()?.let { Next.Text(it) } ?: Next.Closed
    }

    /** Queues [text]; false when the socket is closing or closed. */
    fun send(text: String): Boolean = webSocket.send(text)

    /** Sends a normal close (1000), waits up to [timeoutMs] for the TV's close, then drops the socket. */
    suspend fun close(timeoutMs: Long) {
        webSocket.close(NORMAL_CLOSURE, null)
        withTimeoutOrNull(timeoutMs) { closed.await() }
        webSocket.cancel()
    }

    fun cancel() = webSocket.cancel()

    companion object {
        const val NORMAL_CLOSURE = 1000

        suspend fun open(client: OkHttpClient, request: Request, timeoutMs: Long, logger: Logger): TvSocket {
            val frames = Channel<String>(Channel.UNLIMITED)
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.trySend(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val json = ArtProtocol.leadingJson(bytes.toByteArray())
                    if (json != null) frames.trySend(json) else logger.log("Skipped a binary frame without JSON")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    frames.close()
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    frames.close()
                    closed.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val failure = if (response != null) {
                        TvException.ConnectFailed("HTTP ${response.code} instead of a WebSocket", retryable = false, t)
                    } else {
                        TvException.ConnectFailed(t.toString(), retryable = true, t)
                    }
                    opened.completeExceptionally(failure)
                    frames.close()
                    closed.complete(Unit)
                }
            }
            val webSocket = client.newWebSocket(request, listener)
            // withTimeoutOrNull returns null only for its own deadline; an outer deadline or a
            // cancellation still propagates.
            val isOpen = try {
                withTimeoutOrNull(timeoutMs) { opened.await() } != null
            } catch (e: Throwable) {
                webSocket.cancel()
                throw e
            }
            if (!isOpen) {
                webSocket.cancel()
                throw TvException.ConnectFailed("no connection within $timeoutMs ms", retryable = true)
            }
            return TvSocket(webSocket, frames, closed)
        }
    }
}
