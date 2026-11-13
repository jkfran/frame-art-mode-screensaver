package io.github.jkfran.frameartmode.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtProtocolTest {
    private val id = "6f1c2e0a-3b5d-4c8e-9f10-2a3b4c5d6e7f"

    @Test
    fun `art request envelope`() {
        val outer = JSONObject(ArtProtocol.artRequest("set_artmode_status", id, mapOf("value" to "on")))
        assertEquals("ms.channel.emit", outer.getString("method"))
        val params = outer.getJSONObject("params")
        assertEquals("art_app_request", params.getString("event"))
        assertEquals("host", params.getString("to"))
        // data is a JSON string, not a nested object
        assertTrue(params.get("data") is String)
        val inner = JSONObject(params.getString("data"))
        assertEquals("set_artmode_status", inner.getString("request"))
        assertEquals("on", inner.getString("value"))
        assertEquals(id, inner.getString("id"))
        assertEquals(id, inner.getString("request_id"))
        assertEquals(setOf("request", "value", "id", "request_id"), inner.keys().asSequence().toSet())
    }

    @Test
    fun `get request carries no value`() {
        val inner = JSONObject(JSONObject(ArtProtocol.artRequest("get_artmode_status", id)).getJSONObject("params").getString("data"))
        assertEquals(setOf("request", "id", "request_id"), inner.keys().asSequence().toSet())
    }

    @Test
    fun `power key is a single click`() {
        val outer = JSONObject(ArtProtocol.remoteKeyClick(ArtProtocol.KEY_POWER))
        assertEquals("ms.remote.control", outer.getString("method"))
        val params = outer.getJSONObject("params")
        assertEquals("Click", params.getString("Cmd"))
        assertEquals("KEY_POWER", params.getString("DataOfCmd"))
        assertEquals("false", params.getString("Option"))
        assertEquals("SendRemoteKey", params.getString("TypeOfRemote"))
    }

    @Test
    fun `frame without event counts as star`() {
        assertEquals("*", ArtProtocol.parseFrame("""{"data":{}}""")!!.event)
        assertNull(ArtProtocol.parseFrame("not json"))
    }

    @Test
    fun `token and error message`() {
        val connect = ArtProtocol.parseFrame("""{"event":"ms.channel.connect","data":{"token":"12345678"}}""")!!
        assertEquals("12345678", ArtProtocol.tokenOf(connect))
        val noToken = ArtProtocol.parseFrame("""{"event":"ms.channel.connect","data":{"id":"x"}}""")!!
        assertNull(ArtProtocol.tokenOf(noToken))
        val error = ArtProtocol.parseFrame("""{"event":"ms.error","data":{"message":"unrecognized method value : ms.remote.control"}}""")!!
        assertEquals("unrecognized method value : ms.remote.control", ArtProtocol.errorMessageOf(error))
    }

    @Test
    fun `d2d payload from a string or an object`() {
        val asString = ArtProtocol.parseFrame("""{"event":"d2d_service_message","data":"{\"id\":\"$id\",\"value\":\"off\"}"}""")!!
        assertEquals("off", ArtProtocol.d2dPayload(asString)!!.getString("value"))
        val asObject = ArtProtocol.parseFrame("""{"event":"d2d_service_message","data":{"id":"$id","value":"on"}}""")!!
        assertEquals("on", ArtProtocol.d2dPayload(asObject)!!.getString("value"))
        val invalid = ArtProtocol.parseFrame("""{"event":"d2d_service_message","data":"{not json"}""")!!
        assertNull(ArtProtocol.d2dPayload(invalid))
        val number = ArtProtocol.parseFrame("""{"event":"d2d_service_message","data":5}""")!!
        assertNull(ArtProtocol.d2dPayload(number))
        val other = ArtProtocol.parseFrame("""{"event":"ms.channel.clientConnect","data":{"id":"$id"}}""")!!
        assertNull(ArtProtocol.d2dPayload(other))
    }

    @Test
    fun `correlation prefers request_id`() {
        assertEquals("a", ArtProtocol.correlationId(JSONObject("""{"request_id":"a","id":"b"}""")))
        assertEquals("b", ArtProtocol.correlationId(JSONObject("""{"id":"b"}""")))
        assertNull(ArtProtocol.correlationId(JSONObject("""{"event":"art_mode_changed"}""")))
    }

    @Test
    fun `error code as string or number`() {
        assertEquals(-2, ArtProtocol.errorCode(JSONObject("""{"event":"error","error_code":"-2"}""")))
        assertEquals(-2, ArtProtocol.errorCode(JSONObject("""{"event":"error","error_code":-2}""")))
        assertEquals(-1, ArtProtocol.errorCode(JSONObject("""{"event":"error","error_code":" -1 "}""")))
        assertNull(ArtProtocol.errorCode(JSONObject("""{"event":"error"}""")))
        assertNull(ArtProtocol.errorCode(JSONObject("""{"event":"error","error_code":"x"}""")))
        assertTrue(ArtProtocol.isError(JSONObject("""{"event":"error"}""")))
        assertFalse(ArtProtocol.isError(JSONObject("""{"value":"on"}""")))
    }

    @Test
    fun `request name of an error`() {
        val asString = JSONObject().put("request_data", """{"request": "set_artmode_status", "value": "on"}""")
        assertEquals("set_artmode_status", ArtProtocol.errorRequestName(asString))
        val asObject = JSONObject().put("request_data", JSONObject().put("request", "get_artmode_status"))
        assertEquals("get_artmode_status", ArtProtocol.errorRequestName(asObject))
        assertNull(ArtProtocol.errorRequestName(JSONObject()))
    }

    @Test
    fun `leading json of a binary frame`() {
        val json = """{"event":"d2d_service_message","data":"{\"id\":\"x\",\"text\":\"} { é\"}"}"""
        val bytes = json.toByteArray() + byteArrayOf(0, 1, '}'.code.toByte(), 0x7f)
        assertEquals(json, ArtProtocol.leadingJson(bytes))
        assertEquals("{}", ArtProtocol.leadingJson("xx{}yy".toByteArray()))
        assertNull(ArtProtocol.leadingJson(byteArrayOf(0, 1, 2)))
        assertNull(ArtProtocol.leadingJson("{\"open\":".toByteArray()))
        assertNotNull(ArtProtocol.parseFrame(ArtProtocol.leadingJson(bytes)!!))
    }
}
