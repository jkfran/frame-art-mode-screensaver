package io.github.jkfran.frameartmode.tv

import org.json.JSONObject

/** Building and reading the JSON messages of the TV's WebSocket channels. */
object ArtProtocol {
    const val EVENT_CONNECT = "ms.channel.connect"
    const val EVENT_READY = "ms.channel.ready"
    const val EVENT_UNAUTHORIZED = "ms.channel.unauthorized"
    const val EVENT_TIMEOUT = "ms.channel.timeOut"
    const val EVENT_ERROR = "ms.error"
    const val EVENT_CLIENT_CONNECT = "ms.channel.clientConnect"
    const val EVENT_CLIENT_DISCONNECT = "ms.channel.clientDisconnect"
    const val EVENT_D2D = "d2d_service_message"

    /** Events the TV may send before it answers the connection; they decide nothing. */
    val IGNORED_AT_STARTUP = setOf(
        "ed.edenTV.update",
        "ms.voiceApp.hide",
        "ms.remote.touchEnable",
        "ms.remote.touchDisable",
    )

    const val GET_ARTMODE_STATUS = "get_artmode_status"
    const val SET_ARTMODE_STATUS = "set_artmode_status"
    const val API_VERSION = "api_version"
    const val GET_API_VERSION = "get_api_version"

    /** `set_artmode_status` fails with this code until the Art Store terms are accepted on the TV. */
    const val ERROR_TERMS_NOT_ACCEPTED = -2

    const val KEY_POWER = "KEY_POWER"

    /**
     * An art request. `params.data` is a JSON *string*, and the inner `id` and `request_id`
     * carry the same per-request [id].
     */
    fun artRequest(request: String, id: String, params: Map<String, String> = emptyMap()): String {
        val inner = JSONObject().put("request", request)
        for ((key, value) in params) inner.put(key, value)
        inner.put("id", id).put("request_id", id)
        val outer = JSONObject()
            .put("event", "art_app_request")
            .put("to", "host")
            .put("data", inner.toString())
        return JSONObject().put("method", "ms.channel.emit").put("params", outer).toString()
    }

    /** One click of a remote key. Only clicks are ever sent; never a press and hold. */
    fun remoteKeyClick(key: String): String {
        val params = JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey")
        return JSONObject().put("method", "ms.remote.control").put("params", params).toString()
    }

    /** A frame from the TV. A frame without `event` counts as event `*`. */
    class Frame(val event: String, val json: JSONObject)

    fun parseFrame(text: String): Frame? {
        val json = jsonObjectOrNull(text) ?: return null
        return Frame(json.stringOrNull("event") ?: "*", json)
    }

    /** The token in `ms.channel.connect`, if the TV issued one. */
    fun tokenOf(frame: Frame): String? = frame.json.optJSONObject("data")?.stringOrNull("token")

    /** The reason of an `ms.error` frame. */
    fun errorMessageOf(frame: Frame): String? =
        frame.json.optJSONObject("data")?.stringOrNull("message") ?: frame.json.stringOrNull("data")

    /** The art payload of a `d2d_service_message`: `data` is a JSON string (an object is accepted). */
    fun d2dPayload(frame: Frame): JSONObject? {
        if (frame.event != EVENT_D2D) return null
        val data: Any? = frame.json.opt("data")
        return when (data) {
            is JSONObject -> data
            is String -> jsonObjectOrNull(data)
            else -> null
        }
    }

    /** The id that ties a reply to its request: `request_id`, else `id`. */
    fun correlationId(payload: JSONObject): String? =
        payload.stringOrNull("request_id") ?: payload.stringOrNull("id")

    fun subEvent(payload: JSONObject): String = payload.stringOrNull("event") ?: "*"

    fun isError(payload: JSONObject): Boolean = subEvent(payload) == "error"

    /** The art app's own announcement that Art Mode changed, sent to every connected client. */
    const val EVENT_ART_MODE_CHANGED = "art_mode_changed"

    /** The Art Mode an `art_mode_changed` announcement reports (`on` / `off`), else null. */
    fun announcedArtMode(payload: JSONObject): String? =
        if (subEvent(payload) == EVENT_ART_MODE_CHANGED) {
            payload.stringOrNull("status") ?: payload.stringOrNull("value")
        } else {
            null
        }

    /** `error_code` as a number; the TV may send it as `"-2"` or `-2`. */
    fun errorCode(payload: JSONObject): Int? {
        val value: Any? = payload.opt("error_code")
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    /** The name of the request an error answers, from `request_data` (a JSON string or object). */
    fun errorRequestName(payload: JSONObject): String? {
        val data: Any? = payload.opt("request_data")
        return when (data) {
            is JSONObject -> data.stringOrNull("request")
            is String -> jsonObjectOrNull(data)?.stringOrNull("request")
            else -> null
        }
    }

    /**
     * The leading JSON object of a binary frame. Older art APIs send JSON followed by binary
     * data; the tail is ignored. Scanning bytes is safe for UTF-8 because multi-byte sequences
     * never contain ASCII braces, quotes or backslashes.
     */
    fun leadingJson(bytes: ByteArray): String? {
        val start = bytes.indexOf('{'.code.toByte())
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until bytes.size) {
            val c = bytes[i].toInt().toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return String(bytes, start, i - start + 1, Charsets.UTF_8)
                }
            }
        }
        return null
    }
}
