package com.ghostify.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-171 — All network calls are HTTPS.
 *
 * [HttpPolicy] is the runtime gate: code must call [HttpPolicy.requireHttps]
 * (or a client that does) before any connection, so a URL built at runtime —
 * e.g. a user-pasted playlist URL — is rejected before bytes leave the device.
 * The manifest (usesCleartextTraffic="false") and the static repo scan
 * (SecurityScanTest) cover the build-time side.
 */
class HttpPolicyTest {

    @Test
    fun `accepts https urls`() {
        assertTrue(HttpPolicy.isHttps("https://open.spotify.com/playlist/abc"))
        assertTrue(HttpPolicy.isHttps("HTTPS://api.example.com/v1"))
        assertTrue(HttpPolicy.isHttps("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects cleartext and malformed urls`() {
        assertFalse(HttpPolicy.isHttps("http://open.spotify.com/playlist/abc"))
        assertFalse(HttpPolicy.isHttps("ftp://files.example.com/x"))
        assertFalse(HttpPolicy.isHttps("not-a-url"))
        assertFalse(HttpPolicy.isHttps(""))
    }

    @Test
    fun `requireHttps throws on cleartext`() {
        assertFailsWith<HttpsRequiredException> { HttpPolicy.requireHttps("http://open.spotify.com/track/abc") }
    }

    @Test
    fun `requireHttps returns normalized url for https input`() {
        assertEquals("https://api.example.com/x", HttpPolicy.requireHttps("https://api.example.com/x"))
    }

    @Test
    fun `strips credentials embedded in a url`() {
        val cleaned = HttpPolicy.stripUserInfo("https://user:p%40ss@api.example.com/path?q=1")
        assertFalse(cleaned.contains("p%40ss"), "password leaked: $cleaned")
        assertFalse(cleaned.contains("user"), "userinfo retained: $cleaned")
        assertTrue(cleaned.startsWith("https://api.example.com/path?q=1"))
    }

    @Test
    fun `fake network client refuses to fetch cleartext`() {
        val client = FakeHttpClient()
        assertFailsWith<HttpsRequiredException> { client.get("http://cover.example/art") }
        assertEquals("https://cover.example/art", client.get("https://cover.example/art"))
    }

    /** Stand-in for the app's OkHttp/HttpURLConnection layer: every request
     *  passes through HttpPolicy.requireHttps before being dispatched. */
    private class FakeHttpClient {
        fun get(url: String): String = HttpPolicy.requireHttps(url)
    }
}
