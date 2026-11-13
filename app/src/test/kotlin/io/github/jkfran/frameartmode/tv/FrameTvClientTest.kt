package io.github.jkfran.frameartmode.tv

import io.github.jkfran.frameartmode.testing.FAST
import io.github.jkfran.frameartmode.testing.Frames
import io.github.jkfran.frameartmode.testing.ScriptedTv
import io.github.jkfran.frameartmode.testing.jsonResponse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The client against a local server that plays the TV (plain ws; TLS is in [TvTlsTest]). */
class FrameTvClientTest {
    private val server = MockWebServer()
    private lateinit var client: FrameTvClient
    private lateinit var host: String
    private val tokens = mutableListOf<String>()

    @Before
    fun setUp() {
        server.start()
        host = server.hostName
        client = FrameTvClient(
            ports = TvPorts(rest = server.port, restTlsFallback = null, socket = server.port, socketTls = false),
        )
    }

    @After
    fun tearDown() = server.close()

    private suspend fun open(token: String? = null) = client.openArt(host, token, FAST) { tokens += it }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun `connect and ready open the art channel without a token`() = runBlocking {
        val tv = ScriptedTv()
        server.enqueue(tv.response())
        val session = open()
        session.close(FAST.closeMs)
        assertTrue(tokens.isEmpty())
        val upgrade = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v2/channels/com.samsung.art-app", upgrade.url.encodedPath)
        assertEquals("RnJhbWUgQXJ0IE1vZGU=", upgrade.url.queryParameter("name"))
        assertNull(upgrade.url.queryParameter("token"))
        assertEquals("http://$host:${server.port}", upgrade.headers["Origin"])
    }

    @Test
    fun `startup noise and other clients are skipped`() = runBlocking {
        val tv = ScriptedTv(
            greeting = listOf(
                """{"event":"ms.remote.touchEnable"}""",
                """{"event":"ed.edenTV.update"}""",
                Frames.CONNECT,
                Frames.CLIENT_CONNECT,
                Frames.READY,
            ),
        )
        server.enqueue(tv.response())
        open().close(FAST.closeMs)
    }

    @Test
    fun `unauthorized is access denied and nothing is sent`() = runBlocking {
        val tv = ScriptedTv(greeting = listOf(Frames.UNAUTHORIZED))
        server.enqueue(tv.response())
        assertThrows<TvException.AccessDenied> { runBlocking { open() } }
        assertTrue(tv.received.isEmpty())
    }

    @Test
    fun `channel timeout`() = runBlocking {
        server.enqueue(ScriptedTv(greeting = listOf(Frames.TIMEOUT)).response())
        assertThrows<TvException.ChannelTimeout> { runBlocking { open() } }
        Unit
    }

    @Test
    fun `no ready before the deadline is a channel timeout`() = runBlocking {
        server.enqueue(ScriptedTv(greeting = listOf(Frames.CONNECT)).response())
        assertThrows<TvException.ChannelTimeout> { runBlocking { open() } }
        Unit
    }

    @Test
    fun `close during the handshake is a retryable connection failure`() = runBlocking {
        server.enqueue(ScriptedTv(closeOnOpen = true).response())
        val e = assertThrows<TvException.ConnectFailed> { runBlocking { open() } }
        assertTrue(e.retryable)
    }

    @Test
    fun `refused upgrade is a connection failure that is not retried`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(403).build())
        val e = assertThrows<TvException.ConnectFailed> { runBlocking { open() } }
        assertTrue(!e.retryable)
    }

    @Test
    fun `ms error during the handshake`() = runBlocking {
        server.enqueue(ScriptedTv(greeting = listOf("""{"event":"ms.error","data":{"message":"nope"}}""")).response())
        val e = assertThrows<TvException.TvError> { runBlocking { open() } }
        assertEquals("nope", e.tvMessage)
    }

    @Test
    fun `token from connect is handed over`() = runBlocking {
        server.enqueue(ScriptedTv(greeting = listOf(Frames.connectWithToken("12345678"), Frames.READY)).response())
        open().close(FAST.closeMs)
        assertEquals(listOf("12345678"), tokens)
    }

    @Test
    fun `replies are matched by id`() = runBlocking {
        val tv = ScriptedTv { request, socket ->
            socket.send(Frames.d2d(JSONObject().put("id", "someone-else").put("request_id", "someone-else").put("value", "on")))
            socket.send(Frames.reply(request, "value" to "off"))
        }
        server.enqueue(tv.response())
        val session = open()
        assertEquals(ArtMode.TV, session.artMode(FAST.getMs))
        session.close(FAST.closeMs)
        assertEquals(listOf("get_artmode_status"), tv.requestNames())
    }

    @Test
    fun `unsolicited events never complete a request`() = runBlocking {
        val tv = ScriptedTv { request, socket ->
            socket.send(Frames.d2d(JSONObject().put("event", "art_mode_changed").put("status", "off")))
            socket.send(Frames.reply(request, "value" to "on"))
        }
        server.enqueue(tv.response())
        val session = open()
        assertEquals(ArtMode.ART, session.artMode(FAST.getMs))
        session.close(FAST.closeMs)
    }

    @Test
    fun `binary frame with a tail`() = runBlocking {
        val tv = ScriptedTv { request, socket ->
            val bytes = Frames.reply(request, "value" to "on").toByteArray() + byteArrayOf(0, 1)
            socket.send(bytes.toByteString())
        }
        server.enqueue(tv.response())
        val session = open()
        assertEquals(ArtMode.ART, session.artMode(FAST.getMs))
        session.close(FAST.closeMs)
    }

    @Test
    fun `bad frames are skipped`() = runBlocking {
        val tv = ScriptedTv { request, socket ->
            socket.send("not json")
            socket.send("""{"event":"d2d_service_message","data":5}""")
            socket.send("""{"event":"d2d_service_message","data":"{broken"}""")
            socket.send(Frames.reply(request, "value" to "off"))
        }
        server.enqueue(tv.response())
        val session = open()
        assertEquals(ArtMode.TV, session.artMode(FAST.getMs))
        session.close(FAST.closeMs)
    }

    @Test
    fun `unknown value reads as unknown`() = runBlocking {
        val tv = ScriptedTv { request, socket -> socket.send(Frames.reply(request, "value" to "maybe")) }
        server.enqueue(tv.response())
        val session = open()
        assertEquals(ArtMode.UNKNOWN, session.artMode(FAST.getMs))
        session.close(FAST.closeMs)
    }

    @Test
    fun `set is accepted by any correlated reply`() = runBlocking {
        val tv = ScriptedTv { request, socket -> socket.send(Frames.reply(request)) }
        server.enqueue(tv.response())
        val session = open()
        session.setArtMode(on = true, timeoutMs = FAST.setMs)
        session.close(FAST.closeMs)
        val set = tv.artRequests.single()
        assertEquals("set_artmode_status", set.getString("request"))
        assertEquals("on", set.getString("value"))
        assertEquals(set.getString("id"), set.getString("request_id"))
    }

    @Test
    fun `each request gets a fresh id`() = runBlocking {
        val tv = ScriptedTv { request, socket -> socket.send(Frames.reply(request, "value" to "off")) }
        server.enqueue(tv.response())
        val session = open()
        session.artMode(FAST.getMs)
        session.artMode(FAST.getMs)
        session.close(FAST.closeMs)
        val ids = tv.artRequests.map { it.getString("id") }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `error -2 as a string or a number`() = runBlocking {
        for (code in listOf<Any>("-2", -2)) {
            val tv = ScriptedTv { request, socket -> socket.send(Frames.error(request, code)) }
            server.enqueue(tv.response())
            val session = open()
            val e = assertThrows<TvException.RequestFailed> { runBlocking { session.setArtMode(true, FAST.setMs) } }
            assertEquals(-2, e.code)
            assertEquals("set_artmode_status", e.request)
            session.close(FAST.closeMs)
            assertEquals(1, tv.artRequests.size)
        }
    }

    @Test
    fun `an error without an id is matched by request name`() = runBlocking {
        val tv = ScriptedTv { request, socket ->
            socket.send(
                Frames.d2d(
                    JSONObject().put("event", "error").put("error_code", "-1")
                        .put("request_data", JSONObject().put("request", request.getString("request")).toString()),
                ),
            )
        }
        server.enqueue(tv.response())
        val session = open()
        val e = assertThrows<TvException.RequestFailed> { runBlocking { session.setArtMode(true, FAST.setMs) } }
        assertEquals(-1, e.code)
        session.close(FAST.closeMs)
    }

    @Test
    fun `no reply is a request timeout`() = runBlocking {
        val tv = ScriptedTv()
        server.enqueue(tv.response())
        val session = open()
        assertThrows<TvException.RequestTimeout> { runBlocking { session.setArtMode(true, FAST.setMs) } }
        session.close(FAST.closeMs)
        assertEquals(1, tv.artRequests.size)
    }

    @Test
    fun `ms error while a request is pending`() = runBlocking {
        val tv = ScriptedTv { _, socket -> socket.send("""{"event":"ms.error","data":{"message":"bad request"}}""") }
        server.enqueue(tv.response())
        val session = open()
        val e = assertThrows<TvException.TvError> { runBlocking { session.artMode(FAST.getMs) } }
        assertEquals("bad request", e.tvMessage)
        session.close(FAST.closeMs)
    }

    @Test
    fun `socket closed while waiting is a lost connection`() = runBlocking {
        val tv = ScriptedTv { _, socket -> socket.close(1000, null) }
        server.enqueue(tv.response())
        val session = open()
        assertThrows<TvException.ConnectionLost> { runBlocking { session.artMode(FAST.getMs) } }
        session.close(FAST.closeMs)
    }

    @Test
    fun `api version falls back to the legacy request`() = runBlocking {
        val tv = ScriptedTv { request, socket ->
            if (request.getString("request") == "api_version") {
                socket.send(Frames.error(request, "-1"))
            } else {
                socket.send(Frames.reply(request, "version" to "0.97"))
            }
        }
        server.enqueue(tv.response())
        val session = open()
        assertEquals("0.97", session.apiVersion(FAST.apiVersionMs))
        session.close(FAST.closeMs)
        assertEquals(listOf("api_version", "get_api_version"), tv.requestNames())
    }

    @Test
    fun `api version is unknown, not an error, when the TV fails it`() = runBlocking {
        // First an ms.error frame, then the socket closes.
        var requests = 0
        val tv = ScriptedTv { _, socket ->
            if (requests++ == 0) {
                socket.send("""{"event":"ms.error","data":{"message":"bad request"}}""")
            } else {
                socket.close(1000, null)
            }
        }
        server.enqueue(tv.response())
        val session = open()
        assertNull(session.apiVersion(FAST.apiVersionMs))
        assertNull(session.apiVersion(FAST.apiVersionMs))
        session.close(FAST.closeMs)
        assertEquals(listOf("api_version", "api_version"), tv.requestNames())
    }

    @Test
    fun `device info over REST`() = runBlocking {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val info = client.deviceInfo(host, FAST)
        assertTrue(info.frameTvSupport)
        assertEquals("/api/v2/", server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun `device info works again when the TV closed the previous connection`() = runBlocking {
        // The TV closes the connection after every reply on port 8001, without a
        // "Connection: close" header. A pooled connection must not be reused for the next read.
        repeat(3) {
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(Frames.deviceInfo())
                    .onResponseEnd(SocketEffect.CloseSocket())
                    .build(),
            )
        }
        repeat(3) { assertTrue(client.deviceInfo(host, FAST).frameTvSupport) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `REST errors read as unreachable`() = runBlocking {
        server.enqueue(jsonResponse(Frames.deviceInfo(), code = 500))
        assertThrows<TvException.Unreachable> { runBlocking { client.deviceInfo(host, FAST) } }
        server.enqueue(jsonResponse("<html>hello</html>"))
        assertThrows<TvException.Unreachable> { runBlocking { client.deviceInfo(host, FAST) } }
        Unit
    }

    @Test
    fun `refused REST reads as unreachable`() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val port = closed.port
        closed.close()
        val offline = FrameTvClient(TvPorts(rest = port, restTlsFallback = null))
        assertThrows<TvException.Unreachable> { runBlocking { offline.deviceInfo(host, FAST) } }
        Unit
    }

    @Test
    fun `an unusable address fails without a crash`() = runBlocking {
        assertThrows<TvException.Unreachable> { runBlocking { client.deviceInfo("not a host", FAST) } }
        val e = assertThrows<TvException.ConnectFailed> { runBlocking { client.openArt("not a host", null, FAST) { } } }
        assertTrue(!e.retryable)
    }

    @Test
    fun `power key is one click on the remote channel`() = runBlocking {
        val tv = ScriptedTv(greeting = listOf(Frames.CONNECT))
        server.enqueue(tv.response())
        var sent = 0
        client.clickPowerKey(host, null, FAST, onSent = { sent++ }) { tokens += it }
        assertEquals(1, sent)
        val upgrade = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/v2/channels/samsung.remote.control", upgrade.url.encodedPath)
        val frame = JSONObject(tv.received.single())
        assertEquals("ms.remote.control", frame.getString("method"))
        assertEquals("Click", frame.getJSONObject("params").getString("Cmd"))
        assertEquals("KEY_POWER", frame.getJSONObject("params").getString("DataOfCmd"))
    }

    @Test
    fun `pairing stores the token the TV issues`() = runBlocking {
        server.enqueue(ScriptedTv(greeting = listOf(Frames.connectWithToken("87654321"))).response())
        client.pairRemote(host, null, FAST) { tokens += it }
        assertEquals(listOf("87654321"), tokens)
    }
}
