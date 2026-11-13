package io.github.jkfran.frameartmode.tv

import okhttp3.HttpUrl
import okio.ByteString.Companion.encodeUtf8

/** URLs of the TV's local API. */
object TvUrls {
    /** The name the TV shows in its prompts and in Device Connection Manager. Never vary it. */
    const val CLIENT_NAME = "Frame Art Mode"
    const val ART_CHANNEL = "com.samsung.art-app"
    const val REMOTE_CHANNEL = "samsung.remote.control"
    const val REST_PORT = 8001
    const val SECURE_PORT = 8002

    /** Standard Base64 with padding, as the TV expects in the `name` query parameter. */
    fun encodeName(name: String): String = name.encodeUtf8().base64()

    /** `http://<host>:8001/api/v2/` (or https on [SECURE_PORT]). The trailing slash matters. */
    fun rest(host: String, port: Int = REST_PORT, https: Boolean = port == SECURE_PORT): HttpUrl =
        HttpUrl.Builder()
            .scheme(if (https) "https" else "http")
            .host(host)
            .port(port)
            .addPathSegments("api/v2/")
            .build()

    /**
     * The URL of a WebSocket channel. OkHttp takes web socket URLs with http(s) schemes;
     * https here means wss. The token is only ever sent over TLS.
     */
    fun channel(
        host: String,
        channel: String,
        token: String?,
        port: Int = SECURE_PORT,
        tls: Boolean = port == SECURE_PORT,
        name: String = CLIENT_NAME,
    ): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme(if (tls) "https" else "http")
            .host(host)
            .port(port)
            .addPathSegments("api/v2/channels")
            .addPathSegment(channel)
            .addQueryParameter("name", encodeName(name))
        if (tls && !token.isNullOrEmpty()) builder.addQueryParameter("token", token)
        return builder.build()
    }

    /** The `Origin` header the reference client sends: `https://<host>:<port>`. */
    fun origin(url: HttpUrl): String =
        url.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/")

    /** The URL as text for logs, with any token masked and the web socket scheme. */
    fun redacted(url: HttpUrl): String {
        val masked = if (url.queryParameter("token") != null) {
            url.newBuilder().setQueryParameter("token", "***").build()
        } else {
            url
        }
        val text = masked.toString()
        return when {
            text.startsWith("https://") -> "wss://" + text.removePrefix("https://")
            text.startsWith("http://") -> "ws://" + text.removePrefix("http://")
            else -> text
        }
    }

    /** Canonical form of [host] as OkHttp will see it (IPv6 without brackets, lower case). */
    fun canonicalHost(host: String): String? =
        try {
            HttpUrl.Builder().scheme("http").host(host).build().host
        } catch (_: IllegalArgumentException) {
            null
        }
}
