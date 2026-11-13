package io.github.jkfran.frameartmode.tv

import okio.ByteString.Companion.decodeBase64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvUrlsTest {
    @Test
    fun `client name is standard base64 with padding`() {
        assertEquals("RnJhbWUgQXJ0IE1vZGU=", TvUrls.encodeName(TvUrls.CLIENT_NAME))
    }

    @Test
    fun `name encoding matches the reference table and round-trips`() {
        val vectors = mapOf(
            "Frame Art Mode" to "RnJhbWUgQXJ0IE1vZGU=",
            "SamsungTvRemote" to "U2Ftc3VuZ1R2UmVtb3Rl",
            "ab" to "YWI=",
            "a?>" to "YT8+",
            "???" to "Pz8/",
            "Frame Art Mode é" to "RnJhbWUgQXJ0IE1vZGUgw6k=",
        )
        for ((name, base64) in vectors) {
            assertEquals(name, base64, TvUrls.encodeName(name))
            val url = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = null, name = name)
            assertEquals(name, base64, url.queryParameter("name"))
            assertEquals(name, url.queryParameter("name")!!.decodeBase64()!!.utf8())
        }
    }

    @Test
    fun `plus and equals are percent-encoded in the query`() {
        val url = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = null, name = "a?>")
        assertEquals("name=YT8%2B", url.encodedQuery)
        val ours = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = null)
        assertEquals("name=RnJhbWUgQXJ0IE1vZGU%3D", ours.encodedQuery)
    }

    @Test
    fun `art channel url without a token`() {
        val url = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = null)
        assertEquals(
            "wss://192.0.2.20:8002/api/v2/channels/com.samsung.art-app?name=RnJhbWUgQXJ0IE1vZGU%3D",
            TvUrls.redacted(url),
        )
        assertEquals("https", url.scheme)
        assertNull(url.queryParameter("token"))
        assertFalse(url.toString().contains("token="))
    }

    @Test
    fun `token is appended over TLS only`() {
        val secure = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = "12345678")
        assertTrue(secure.toString().endsWith("&token=12345678"))
        val remote = TvUrls.channel("192.0.2.20", TvUrls.REMOTE_CHANNEL, token = "12345678")
        assertEquals("/api/v2/channels/samsung.remote.control", remote.encodedPath)
        val plain = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = "12345678", port = 8001)
        assertEquals("http", plain.scheme)
        assertNull(plain.queryParameter("token"))
        val empty = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = "")
        assertNull(empty.queryParameter("token"))
    }

    @Test
    fun `redacted url masks the token`() {
        val url = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = "12345678")
        val text = TvUrls.redacted(url)
        assertFalse(text.contains("12345678"))
        assertTrue(text.endsWith("&token=***"))
    }

    @Test
    fun `rest url keeps the trailing slash`() {
        assertEquals("http://192.0.2.20:8001/api/v2/", TvUrls.rest("192.0.2.20").toString())
        assertEquals("https://192.0.2.20:8002/api/v2/", TvUrls.rest("192.0.2.20", 8002).toString())
        assertEquals("http://[fe80::1]:8001/api/v2/", TvUrls.rest("[fe80::1]").toString())
    }

    @Test
    fun `origin is scheme host and port`() {
        val url = TvUrls.channel("192.0.2.20", TvUrls.ART_CHANNEL, token = "12345678")
        assertEquals("https://192.0.2.20:8002", TvUrls.origin(url))
    }

    @Test
    fun `canonical host`() {
        assertEquals("192.0.2.20", TvUrls.canonicalHost("192.0.2.20"))
        assertEquals("fe80::1", TvUrls.canonicalHost("[fe80::1]"))
        assertNull(TvUrls.canonicalHost("not a host"))
    }
}
