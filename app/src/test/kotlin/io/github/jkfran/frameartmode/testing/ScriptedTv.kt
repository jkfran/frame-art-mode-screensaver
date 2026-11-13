package io.github.jkfran.frameartmode.testing

import mockwebserver3.MockResponse
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Frames a TV sends. */
object Frames {
    const val CONNECT = """{"event":"ms.channel.connect","data":{"id":"0f9a7e64","clients":[]}}"""
    const val READY = """{"event":"ms.channel.ready","data":{}}"""
    const val UNAUTHORIZED = """{"event":"ms.channel.unauthorized"}"""
    const val TIMEOUT = """{"event":"ms.channel.timeOut"}"""
    const val CLIENT_CONNECT = """{"event":"ms.channel.clientConnect","data":{}}"""

    fun connectWithToken(token: String) =
        JSONObject().put("event", "ms.channel.connect")
            .put("data", JSONObject().put("id", "0f9a7e64").put("token", token)).toString()

    fun d2d(payload: JSONObject): String =
        JSONObject().put("event", "d2d_service_message").put("data", payload.toString()).toString()

    /** A success reply to [request], correlated by its id. */
    fun reply(request: JSONObject, vararg fields: Pair<String, Any>): String {
        val payload = JSONObject()
            .put("id", request.getString("id"))
            .put("request_id", request.getString("request_id"))
            .put("event", request.getString("request"))
            .put("target_client_id", "0f9a7e64")
        for ((key, value) in fields) payload.put(key, value)
        return d2d(payload)
    }

    /** An error reply to [request]; [code] may be a string or a number. */
    fun error(request: JSONObject, code: Any): String =
        d2d(
            JSONObject()
                .put("id", request.getString("id"))
                .put("request_id", request.getString("request_id"))
                .put("event", "error")
                .put("error_code", code)
                .put("request_data", request.toString()),
        )

    fun deviceInfo(
        power: Any? = "on",
        frame: Any? = "true",
        id: String = "uuid:tv-1",
        mac: String = "00:00:5E:00:53:01",
    ): String {
        val device = JSONObject()
            .put("name", "Samsung The Frame 55")
            .put("modelName", "QE55LS03BAUXXU")
            .put("id", id)
            .put("wifiMac", mac)
            .put("TokenAuthSupport", "true")
        power?.let { device.put("PowerState", it) }
        frame?.let { device.put("FrameTVSupport", it) }
        return JSONObject().put("device", device).put("id", id).put("version", "2.0.25").toString()
    }
}

/**
 * The TV end of a WebSocket for MockWebServer: sends [greeting] on open, records every frame it
 * receives, and lets [onArtRequest] answer art requests.
 */
class ScriptedTv(
    private val greeting: List<String> = listOf(Frames.CONNECT, Frames.READY),
    private val closeOnOpen: Boolean = false,
    private val onArtRequest: (request: JSONObject, socket: WebSocket) -> Unit = { _, _ -> },
) : WebSocketListener() {
    val received = CopyOnWriteArrayList<String>()

    /** The inner `data` of every art request received, in order. */
    val artRequests: List<JSONObject>
        get() = received.mapNotNull { text ->
            val outer = JSONObject(text)
            if (outer.optString("method") != "ms.channel.emit") null
            else JSONObject(outer.getJSONObject("params").getString("data"))
        }

    fun requestNames(): List<String> = artRequests.map { it.getString("request") }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (closeOnOpen) {
            webSocket.close(1000, null)
            return
        }
        greeting.forEach { webSocket.send(it) }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        received += text
        val outer = JSONObject(text)
        if (outer.optString("method") == "ms.channel.emit") {
            onArtRequest(JSONObject(outer.getJSONObject("params").getString("data")), webSocket)
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    fun response(): MockResponse = MockResponse.Builder().webSocketUpgrade(this).build()
}

fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
