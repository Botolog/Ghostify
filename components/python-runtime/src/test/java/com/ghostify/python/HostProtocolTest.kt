package com.ghostify.python

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM verification of the bridge wire codec: value round-trips, the response
 * envelope, typed error propagation and malformed-input handling.
 */
class HostProtocolTest {

    @Test
    fun `round-trips every supported value type`() {
        val value: Any? = mapOf(
            "text" to "hello \"world\" \u00e9\u4e2d\n",
            "count" to 42,
            "ratio" to 1.5,
            "flag" to true,
            "nothing" to null,
            "list" to listOf(1, "two", false, null, mapOf("k" to "v")),
            "empty_list" to emptyList<Any?>(),
            "empty_map" to emptyMap<String, Any?>(),
        )
        val encoded = HostProtocol.encodeValue(value)
        val parsed = HostProtocol.normalize(JSONObject("""{"v":$encoded}""").opt("v"))
        assertEquals(value, parsed)
    }

    @Test
    fun `encodes ok envelope with meta`() {
        val json = HostProtocol.encodeOkWithMeta(
            """{"probe":true,"version":"3.11.9"}""",
            HostMeta(interpreterId = "id-1", hostUptimeMs = 1234, abi = "arm64-v8a"),
        )
        val root = JSONObject(json)
        assertEquals("id-1", root.getJSONObject("meta").getString("interpreter_id"))
        assertEquals(1234L, root.getJSONObject("meta").getLong("host_uptime_ms"))
        assertEquals(true, root.getJSONObject("ok").getBoolean("probe"))
        assertEquals("3.11.9", root.getJSONObject("ok").getString("version"))
    }

    @Test
    fun `parses python error response into typed fields`() {
        val json = HostProtocol.encodeError(
            kind = "PythonException",
            type = "RuntimeError",
            message = "boom",
            traceback = "Traceback (most recent call last):...",
        )
        try {
            HostProtocol.parseResponse(json)
            fail("expected a PythonError to be thrown")
        } catch (e: PythonError.PythonExceptionInfo) {
            assertEquals("RuntimeError", e.pythonType)
            assertEquals("boom", e.pythonMessage)
            assertTrue(e.pythonTraceback.startsWith("Traceback"))
        }
    }

    @Test
    fun `parses host error response`() {
        val json = HostProtocol.encodeError(kind = "Timeout", type = "Timeout", message = "call exceeded limit")
        try {
            HostProtocol.parseResponse(json)
            fail("expected a PythonError to be thrown")
        } catch (e: PythonError.HostErrorInfo) {
            assertEquals("Timeout", e.kind)
            assertEquals("call exceeded limit", e.hostMessage)
        }
    }

    @Test
    fun `rejects malformed request json`() {
        try {
            HostProtocol.parseRequest("{not json")
            fail("expected PythonError.InvalidRequest")
        } catch (e: PythonError.InvalidRequest) {
            // expected
        }
    }

    @Test
    fun `rejects malformed response json`() {
        try {
            HostProtocol.parseResponse("[]nope")
            fail("expected PythonError.InvalidRequest")
        } catch (e: PythonError.InvalidRequest) {
            // expected
        }
    }

    @Test
    fun `encodes and round-trips a request`() {
        val json = HostProtocol.encodeRequest("ghostify_dl", "fetch_playlist", listOf("37i9dQZF1DX", 2))
        val request = HostProtocol.parseRequest(json)
        assertEquals("ghostify_dl", request.module)
        assertEquals("fetch_playlist", request.method)
        val parsedArgs = HostProtocol.normalize(JSONObject("""{"a":${request.argsJson}}""").getJSONArray("a").toList() as Any?)
        assertEquals(listOf<Any?>("37i9dQZF1DX", 2), parsedArgs)
    }

    @Test
    fun `missing ok parses as null value`() {
        val response = HostProtocol.parseResponse("""{"ok":null,"meta":{"interpreter_id":"x","host_uptime_ms":0,"abi":"a"}}""")
        assertNull(response.ok)
        assertEquals("x", response.meta?.interpreterId)
    }
}
