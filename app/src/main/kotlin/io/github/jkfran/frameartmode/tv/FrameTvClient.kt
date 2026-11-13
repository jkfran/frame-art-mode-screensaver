package io.github.jkfran.frameartmode.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.util.UUID

/** Where the TV listens. The defaults are the real TV's; tests point them at a local server. */
data class TvPorts(
    val rest: Int = TvUrls.REST_PORT,
    val restTls: Boolean = false,
    /** Tried with https when [rest] refuses the connection; null to skip. */
    val restTlsFallback: Int? = TvUrls.SECURE_PORT,
    val socket: Int = TvUrls.SECURE_PORT,
    val socketTls: Boolean = true,
)

/** [FrameTv] over OkHttp. */
class FrameTvClient(
    private val ports: TvPorts = TvPorts(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val logger: Logger = Logger.NONE,
) : FrameTv {

    override suspend fun deviceInfo(host: String, timeouts: Timeouts): DeviceInfo {
        if (TvUrls.canonicalHost(host) == null) throw TvException.Unreachable("not a usable address")
        val client = TvHttp.forHost(host, timeouts.restConnectMs, timeouts.restReadMs, timeouts.restCallMs)
        return try {
            fetchDeviceInfo(client, TvUrls.rest(host, ports.rest, ports.restTls))
        } catch (e: ConnectException) {
            val fallback = ports.restTlsFallback ?: throw TvException.Unreachable(e.toString(), e)
            try {
                fetchDeviceInfo(client, TvUrls.rest(host, fallback, https = true))
            } catch (e2: IOException) {
                throw TvException.Unreachable(e2.toString(), e2)
            }
        } catch (e: IOException) {
            throw TvException.Unreachable(e.toString(), e)
        }
    }

    private suspend fun fetchDeviceInfo(client: OkHttpClient, url: HttpUrl): DeviceInfo {
        val response = client.newCall(Request.Builder().url(url).build()).await()
        val body = response.use {
            if (it.code != 200) throw TvException.Unreachable("HTTP ${it.code} from ${url.port}")
            withContext(Dispatchers.IO) { it.body.string() }
        }
        return DeviceInfo.parse(body) ?: throw TvException.Unreachable("not a Samsung TV API reply")
    }

    override suspend fun openArt(
        host: String,
        token: String?,
        timeouts: Timeouts,
        onToken: (String) -> Unit,
    ): ArtSession {
        val socket = connect(host, TvUrls.ART_CHANNEL, token, timeouts)
        handshakeOrClose(socket, waitForReady = true, timeouts, onToken)
        return SocketArtSession(socket, newId, logger)
    }

    override suspend fun pairRemote(host: String, token: String?, timeouts: Timeouts, onToken: (String) -> Unit) {
        val socket = connect(host, TvUrls.REMOTE_CHANNEL, token, timeouts)
        handshakeOrClose(socket, waitForReady = false, timeouts, onToken)
        withContext(NonCancellable) { socket.close(timeouts.closeMs) }
    }

    override suspend fun clickPowerKey(
        host: String,
        token: String?,
        timeouts: Timeouts,
        onSent: () -> Unit,
        onToken: (String) -> Unit,
    ) {
        val socket = connect(host, TvUrls.REMOTE_CHANNEL, token, timeouts)
        handshakeOrClose(socket, waitForReady = false, timeouts, onToken)
        try {
            if (!socket.send(ArtProtocol.remoteKeyClick(ArtProtocol.KEY_POWER))) {
                throw TvException.ConnectionLost(ArtProtocol.KEY_POWER)
            }
            // Queued: it goes out before the close below, even if this coroutine is cancelled.
            onSent()
            // The reference client keeps the socket open for a second after a key.
            delay(KEY_SETTLE_MS)
        } finally {
            withContext(NonCancellable) { socket.close(timeouts.closeMs) }
        }
    }

    private suspend fun connect(host: String, channel: String, token: String?, timeouts: Timeouts): TvSocket {
        if (TvUrls.canonicalHost(host) == null) throw TvException.ConnectFailed("not a usable address", retryable = false)
        val url = TvUrls.channel(host, channel, token, ports.socket, ports.socketTls)
        logger.log("Connecting to ${TvUrls.redacted(url)}")
        // The read timeout applies to the upgrade only; OkHttp clears it once the socket is open.
        val client = TvHttp.forHost(host, timeouts.socketConnectMs, timeouts.socketConnectMs, 0)
        val request = Request.Builder().url(url).header("Origin", TvUrls.origin(url)).build()
        return TvSocket.open(client, request, timeouts.socketConnectMs, logger)
    }

    private suspend fun handshakeOrClose(
        socket: TvSocket,
        waitForReady: Boolean,
        timeouts: Timeouts,
        onToken: (String) -> Unit,
    ) {
        try {
            handshake(socket, waitForReady, timeouts.handshakeMs, onToken)
        } catch (e: Throwable) {
            withContext(NonCancellable) { socket.close(timeouts.closeMs) }
            throw e
        }
    }

    /**
     * Reads the TV's answer to the connection: `ms.channel.connect`, then on the art channel
     * `ms.channel.ready`. Startup noise is skipped.
     */
    private suspend fun handshake(socket: TvSocket, waitForReady: Boolean, timeoutMs: Long, onToken: (String) -> Unit) {
        val deadline = nowMs() + timeoutMs
        while (true) {
            val frame = when (val next = socket.next(deadline - nowMs())) {
                TvSocket.Next.Timeout -> throw TvException.ChannelTimeout("no ms.channel.connect within $timeoutMs ms")
                TvSocket.Next.Closed -> throw TvException.ConnectFailed("closed during the handshake", retryable = true)
                is TvSocket.Next.Text -> ArtProtocol.parseFrame(next.text)
            }
            if (frame == null) {
                logger.log("Skipped a frame that is not JSON")
                continue
            }
            when (frame.event) {
                in ArtProtocol.IGNORED_AT_STARTUP -> continue
                ArtProtocol.EVENT_CONNECT -> {
                    ArtProtocol.tokenOf(frame)?.let(onToken)
                    break
                }
                ArtProtocol.EVENT_UNAUTHORIZED -> throw TvException.AccessDenied()
                ArtProtocol.EVENT_TIMEOUT -> throw TvException.ChannelTimeout(ArtProtocol.EVENT_TIMEOUT)
                ArtProtocol.EVENT_ERROR -> throw TvException.TvError(ArtProtocol.errorMessageOf(frame))
                else -> throw TvException.ConnectFailed("unexpected event ${frame.event}", retryable = false)
            }
        }
        if (!waitForReady) return
        while (true) {
            val frame = when (val next = socket.next(deadline - nowMs())) {
                TvSocket.Next.Timeout -> throw TvException.ChannelTimeout("no ms.channel.ready within $timeoutMs ms")
                TvSocket.Next.Closed -> throw TvException.ConnectFailed("closed during the handshake", retryable = true)
                is TvSocket.Next.Text -> ArtProtocol.parseFrame(next.text) ?: continue
            }
            when (frame.event) {
                ArtProtocol.EVENT_READY -> return
                ArtProtocol.EVENT_UNAUTHORIZED -> throw TvException.AccessDenied()
                ArtProtocol.EVENT_TIMEOUT -> throw TvException.ChannelTimeout(ArtProtocol.EVENT_TIMEOUT)
                ArtProtocol.EVENT_ERROR -> throw TvException.TvError(ArtProtocol.errorMessageOf(frame))
                else -> logger.log("Skipped ${frame.event} while waiting for ms.channel.ready")
            }
        }
    }

    private companion object {
        const val KEY_SETTLE_MS = 1_000L
    }
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000

/** Art requests over an open socket, matched to their replies by id. */
private class SocketArtSession(
    private val socket: TvSocket,
    private val newId: () -> String,
    private val logger: Logger,
) : ArtSession {
    private val inFlight = Mutex()

    override suspend fun artMode(timeoutMs: Long): ArtMode {
        val payload = request(ArtProtocol.GET_ARTMODE_STATUS, emptyMap(), timeoutMs)
        val mode = ArtMode.fromValue(payload.stringOrNull("value"))
        if (mode == ArtMode.UNKNOWN) logger.log("Art status reply without a known value: $payload")
        return mode
    }

    override suspend fun setArtMode(on: Boolean, timeoutMs: Long): SetReply {
        val value = if (on) "on" else "off"
        val payload = request(ArtProtocol.SET_ARTMODE_STATUS, mapOf("value" to value), timeoutMs, announced = value)
        return if (ArtProtocol.announcedArtMode(payload) == value) SetReply.ANNOUNCED else SetReply.ACCEPTED
    }

    /** For display only, so any failure reads as "unknown" rather than failing the caller. */
    override suspend fun apiVersion(timeoutMs: Long): String? {
        val payload = try {
            request(ArtProtocol.API_VERSION, emptyMap(), timeoutMs)
        } catch (_: TvException.RequestFailed) {
            try {
                request(ArtProtocol.GET_API_VERSION, emptyMap(), timeoutMs)
            } catch (e: TvException) {
                logger.log("No art service version: ${e.message}")
                return null
            }
        } catch (e: TvException) {
            logger.log("No art service version: ${e.message}")
            return null
        }
        return payload.stringOrNull("version")
    }

    override suspend fun close(timeoutMs: Long) = socket.close(timeoutMs)

    /**
     * Sends one request and returns its reply. With [announced], an `art_mode_changed`
     * announcement of that value also ends the wait and is returned instead.
     */
    private suspend fun request(
        name: String,
        params: Map<String, String>,
        timeoutMs: Long,
        announced: String? = null,
    ): JSONObject =
        inFlight.withLock {
            val id = newId()
            if (!socket.send(ArtProtocol.artRequest(name, id, params))) throw TvException.ConnectionLost(name)
            awaitReply(name, id, nowMs() + timeoutMs, announced)
        }

    private suspend fun awaitReply(name: String, id: String, deadline: Long, announced: String?): JSONObject {
        while (true) {
            val frame = when (val next = socket.next(deadline - nowMs())) {
                TvSocket.Next.Timeout -> throw TvException.RequestTimeout(name)
                TvSocket.Next.Closed -> throw TvException.ConnectionLost(name)
                is TvSocket.Next.Text -> ArtProtocol.parseFrame(next.text)
            }
            if (frame == null) {
                logger.log("Skipped a frame that is not JSON")
                continue
            }
            if (frame.event == ArtProtocol.EVENT_ERROR) throw TvException.TvError(ArtProtocol.errorMessageOf(frame))
            val payload = ArtProtocol.d2dPayload(frame) ?: continue
            val announcement = ArtProtocol.announcedArtMode(payload)
            if (announcement != null) logger.log("The TV announced Art Mode $announcement")
            if (announced != null && announcement == announced) return payload
            if (!isReplyTo(payload, id, name)) {
                if (announcement == null) logger.log("Skipped ${ArtProtocol.subEvent(payload)} not addressed to this request")
                continue
            }
            if (ArtProtocol.isError(payload)) {
                throw TvException.RequestFailed(
                    request = name,
                    code = ArtProtocol.errorCode(payload),
                    rawCode = payload.stringOrNull("error_code"),
                )
            }
            return payload
        }
    }

    private fun isReplyTo(payload: JSONObject, id: String, name: String): Boolean {
        val replyId = ArtProtocol.correlationId(payload)
        if (replyId != null) return replyId == id
        // An error without any id still names the request it answers.
        return ArtProtocol.isError(payload) && ArtProtocol.errorRequestName(payload) == name
    }
}
