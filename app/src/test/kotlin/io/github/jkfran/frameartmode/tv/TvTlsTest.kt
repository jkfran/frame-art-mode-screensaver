package io.github.jkfran.frameartmode.tv

import io.github.jkfran.frameartmode.testing.FAST
import io.github.jkfran.frameartmode.testing.Frames
import io.github.jkfran.frameartmode.testing.ScriptedTv
import io.github.jkfran.frameartmode.testing.jsonResponse
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** TLS as the TV does it: a self-signed certificate issued for a name that is not the address. */
class TvTlsTest {
    private val certificate = HeldCertificate.Builder().commonName("SmartViewSDK").addSubjectAlternativeName("tv.invalid").build()
    private val server = MockWebServer()
    private lateinit var host: String

    @Before
    fun setUp() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.start()
        host = server.hostName
    }

    @After
    fun tearDown() = server.close()

    private fun tlsClient() = FrameTvClient(
        TvPorts(rest = server.port, restTls = true, restTlsFallback = null, socket = server.port, socketTls = true),
    )

    @Test
    fun `a default client rejects the TV certificate`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        // By IP, as the app connects: "localhost" may resolve to an address the server is not on.
        val url = server.url("/api/v2/").newBuilder().host(server.socketAddress.address.hostAddress!!).build()
        val call = OkHttpClient().newCall(Request.Builder().url(url).build())
        try {
            call.execute().close()
            throw AssertionError("the self-signed certificate was accepted")
        } catch (e: SSLException) {
            // expected
        }
    }

    @Test
    fun `the TV client accepts it for the TV address only`() {
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val ok = TvHttp.forHost(host, 3_000, 3_000, 5_000)
        ok.newCall(Request.Builder().url(server.url("/api/v2/")).build()).execute().use { assertEquals(200, it.code) }

        val otherHost = TvHttp.forHost("192.0.2.99", 3_000, 3_000, 5_000)
        try {
            otherHost.newCall(Request.Builder().url(server.url("/api/v2/")).build()).execute().close()
            throw AssertionError("a connection to another host was accepted")
        } catch (e: IOException) {
            // expected: the hostname check limits the client to its TV
        }
    }

    @Test
    fun `token is stored and sent on the next connection`() = runBlocking {
        val tokens = mutableListOf<String>()
        server.enqueue(ScriptedTv(greeting = listOf(Frames.connectWithToken("12345678"), Frames.READY)).response())
        tlsClient().openArt(host, null, FAST) { tokens += it }.close(FAST.closeMs)
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(first.url.queryParameter("token"))
        assertEquals(listOf("12345678"), tokens)

        server.enqueue(ScriptedTv().response())
        tlsClient().openArt(host, tokens.last(), FAST) { tokens += it }.close(FAST.closeMs)
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("12345678", second.url.queryParameter("token"))
        assertTrue(second.url.toString().endsWith("&token=12345678"))
        assertEquals("https://$host:${server.port}", second.headers["Origin"])
    }

    @Test
    fun `REST falls back to https when port 8001 refuses`() = runBlocking {
        val closed = MockWebServer().apply { start() }
        val refused = closed.port
        closed.close()
        server.enqueue(jsonResponse(Frames.deviceInfo()))
        val client = FrameTvClient(TvPorts(rest = refused, restTlsFallback = server.port))
        assertTrue(client.deviceInfo(host, FAST).frameTvSupport)
    }
}
