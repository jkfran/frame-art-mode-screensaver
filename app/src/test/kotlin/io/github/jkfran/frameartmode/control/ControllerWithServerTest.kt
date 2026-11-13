package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.testing.FAST
import io.github.jkfran.frameartmode.testing.Frames
import io.github.jkfran.frameartmode.testing.MemoryStore
import io.github.jkfran.frameartmode.testing.MemoryText
import io.github.jkfran.frameartmode.testing.ScriptedTv
import io.github.jkfran.frameartmode.testing.jsonResponse
import io.github.jkfran.frameartmode.tv.ArtMode
import io.github.jkfran.frameartmode.tv.FrameTvClient
import io.github.jkfran.frameartmode.tv.TvPorts
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The controller and the real client against a local server that plays the TV. */
class ControllerWithServerTest {
    private val server = MockWebServer()
    private lateinit var store: MemoryStore
    private lateinit var controller: ArtModeController
    private val now = 5_000_000L

    @Before
    fun setUp() {
        server.start()
        store = MemoryStore(tv = TvConfig(server.hostName, "uuid:tv-1", "00:00:5E:00:53:01", null, null))
        val client = FrameTvClient(
            TvPorts(rest = server.port, restTlsFallback = null, socket = server.port, socketTls = false),
        )
        controller = ArtModeController(client, store, EventLog(MemoryText()), clock = { now }, pause = { })
    }

    @After
    fun tearDown() = server.close()

    private fun dream() = runBlocking { controller.switchToArtMode(Trigger.DREAM, FAST) }

    /** A TV that answers get with [before] until a set arrives, then with [after]. */
    private fun tv(
        before: String = "off",
        after: String = "on",
        onSet: ((JSONObject, WebSocket) -> Unit)? = null,
    ): ScriptedTv {
        var setSeen = false
        return ScriptedTv { request, socket ->
            when (request.getString("request")) {
                "get_artmode_status" ->
                    socket.send(Frames.reply(request, "value" to if (setSeen) after else before))
                "set_artmode_status" -> {
                    setSeen = true
                    if (onSet != null) onSet(request, socket) else socket.send(Frames.reply(request))
                }
            }
        }
    }

    @Test
    fun `switches a Frame in TV mode to Art Mode`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = tv()
        server.enqueue(tv.response())
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
        assertEquals(listOf("get_artmode_status", "set_artmode_status", "get_artmode_status"), tv.requestNames())
        assertEquals("on", tv.artRequests[1].getString("value"))
    }

    @Test
    fun `already in Art Mode sends no request`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = ScriptedTv { request, socket ->
            socket.send(Frames.d2d(JSONObject().put("event", "art_mode_changed").put("status", "off")))
            socket.send(Frames.reply(request, "value" to "on"))
        }
        server.enqueue(tv.response())
        assertEquals(Outcome.ALREADY_ART, dream().outcome)
        assertEquals(listOf("get_artmode_status"), tv.requestNames())
    }

    @Test
    fun `error -2 stops after exactly one request and opens nothing else`() {
        store.powerKeyFallback = true
        store.remotePaired = true
        for (code in listOf<Any>("-2", -2)) {
            store.termsNotAcceptedAt = null
            server.enqueue(jsonResponse(Frames.deviceInfo()))
            val tv = tv(onSet = { request, socket -> socket.send(Frames.error(request, code)) })
            server.enqueue(tv.response())
            val before = server.requestCount
            val result = dream()
            assertEquals(Outcome.TERMS_NOT_ACCEPTED, result.outcome)
            assertNull(result.fallback)
            assertEquals(listOf("get_artmode_status", "set_artmode_status"), tv.requestNames())
            assertEquals(now, store.termsNotAcceptedAt)
            // REST and the art channel only: no remote-control connection.
            assertEquals(before + 2, server.requestCount)
        }
    }

    @Test
    fun `another error code is reported and not re-sent`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = tv(onSet = { request, socket -> socket.send(Frames.error(request, "-1")) })
        server.enqueue(tv.response())
        val result = dream()
        assertEquals(Outcome.ART_REQUEST_FAILED, result.outcome)
        assertEquals("error_code -1", result.detail)
        assertEquals(1, tv.requestNames().count { it == "set_artmode_status" })
    }

    @Test
    fun `no reply to the request is a timeout, not a second request`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = tv(onSet = { _, _ -> })
        server.enqueue(tv.response())
        assertEquals(Outcome.REQUEST_TIMEOUT, dream().outcome)
        assertEquals(1, tv.requestNames().count { it == "set_artmode_status" })
    }

    @Test
    fun `an error frame while the request is pending sends no power key`() {
        store.powerKeyFallback = true
        store.remotePaired = true
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = tv(onSet = { _, socket -> socket.send("""{"event":"ms.error","data":{"message":"busy"}}""") })
        server.enqueue(tv.response())
        val result = dream()
        assertEquals(Outcome.TV_ERROR, result.outcome)
        assertNull(result.fallback)
        assertEquals(listOf("get_artmode_status", "set_artmode_status"), tv.requestNames())
        // REST and the art channel only: no second REST read, no remote-control connection.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `standby means no WebSocket at all`() {
        server.enqueue(jsonResponse(Frames.deviceInfo(power = "standby")))
        assertEquals(Outcome.TV_OFF, dream().outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `not a Frame means no WebSocket at all`() {
        server.enqueue(jsonResponse(Frames.deviceInfo(frame = "false")))
        assertEquals(Outcome.NOT_A_FRAME, dream().outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a boolean Frame flag counts`() {
        server.enqueue(jsonResponse(Frames.deviceInfo(frame = true)))
        server.enqueue(tv().response())
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
    }

    @Test
    fun `a TV that refuses connections is left alone`() {
        val closed = MockWebServer().apply { start() }
        val port = closed.port
        closed.close()
        val offline = ArtModeController(
            FrameTvClient(TvPorts(rest = port, restTlsFallback = null, socket = port, socketTls = false)),
            store,
            EventLog(MemoryText()),
        )
        val result = runBlocking { offline.switchToArtMode(Trigger.DREAM, FAST) }
        assertEquals(Outcome.TV_UNREACHABLE, result.outcome)
    }

    @Test
    fun `closed during the handshake is retried, then works`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        server.enqueue(ScriptedTv(closeOnOpen = true).response())
        val tv = tv()
        server.enqueue(tv.response())
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `closed during every handshake gives up after two retries`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        repeat(4) { server.enqueue(ScriptedTv(closeOnOpen = true).response()) }
        assertEquals(Outcome.CONNECT_FAILED, dream().outcome)
        assertEquals(1 + 3, server.requestCount)
    }

    @Test
    fun `unauthorized clears the token`() {
        store.token = "12345678"
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = ScriptedTv(greeting = listOf(Frames.UNAUTHORIZED))
        server.enqueue(tv.response())
        assertEquals(Outcome.ACCESS_DENIED, dream().outcome)
        assertNull(store.token)
        assertEquals(0, tv.received.size)
    }

    @Test
    fun `pending terms mean no network at all`() {
        store.termsNotAcceptedAt = now - 1_000
        assertEquals(Outcome.TERMS_PENDING, dream().outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `check reports the state without a request`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val tv = ScriptedTv { request, socket ->
            when (request.getString("request")) {
                "get_artmode_status" -> socket.send(Frames.reply(request, "value" to "off"))
                "api_version" -> socket.send(Frames.reply(request, "version" to "4.3.4.0"))
            }
        }
        server.enqueue(tv.response())
        val report = runBlocking { controller.check(FAST) }
        assertEquals(Outcome.CHECK_OK, report.result.outcome)
        assertEquals("4.3.4.0", report.apiVersion)
        assertNotNull(report.info)
        assertEquals(listOf("get_artmode_status", "api_version"), tv.requestNames())
    }

    @Test
    fun `check keeps the Art Mode status when the version request fails`() {
        val failures = listOf<(WebSocket) -> Unit>(
            { socket -> socket.send("""{"event":"ms.error","data":{"message":"bad request"}}""") },
            { socket -> socket.close(1000, null) },
        )
        for (fail in failures) {
            server.enqueue(jsonResponse(Frames.deviceInfo()))
            val tv = ScriptedTv { request, socket ->
                when (request.getString("request")) {
                    "get_artmode_status" -> socket.send(Frames.reply(request, "value" to "off"))
                    else -> fail(socket)
                }
            }
            server.enqueue(tv.response())
            val report = runBlocking { controller.check(FAST) }
            assertEquals(Outcome.CHECK_OK, report.result.outcome)
            assertEquals(ArtMode.TV, report.artMode)
            assertNull(report.apiVersion)
        }
    }
}
