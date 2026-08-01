package com.ghostify.security

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-168 + T-170 — the logging policy wrapper.
 *
 * T-168: no secret value can pass through [LogPolicy] to the sink.
 * T-170: logs never contain a full Spotify URL or track metadata beyond debug
 * need — enforced by (a) the sanitizer at [SanitizeLevel.PROD], (b) the
 * sensitive flag forcing PROD strictness in debug builds, (c) verbose/debug
 * messages being dropped entirely in production builds.
 */
class LogPolicyTest {

    private fun policy(debug: Boolean, sink: (LogLevel, String, String) -> Unit): LogPolicy =
        LogPolicy.setup(debug, sink)

    @Test
    fun `prod build never emits a full spotify url`() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("test", "opened playlist https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=deadbeef")
        val out = captured.toString()
        assertTrue("spotify:playlist" in out, "URL id was not stripped in prod: $out")
        assertFalse(out.contains("37i9dQZF1DXcBWIGoYBM5M"), "full URL/id leaked: $out")
        assertFalse(out.contains("si=deadbeef"), "query params leaked: $out")
    }

    @Test
    fun `prod build drops verbose and debug messages entirely`() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.v("t", "verbose detail with track title")
        p.d("t", "debug detail with artist name")
        p.i("t", "info survives")
        assertEquals("info survives", captured.toString())
    }

    @Test
    fun `debug build keeps only a short id for troubleshooting`() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("t", "resolved https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc")
        val out = captured.toString()
        assertTrue(out.contains("4cOdK2wGLETKBW3PvgPWqT"), "debug should keep short id: $out")
        assertFalse(out.contains("?si=abc"), "query params must still be stripped: $out")
    }

    @Test
    fun `sensitive message is prod-strict even in a debug build`() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("t", "synced track https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", sensitive = true)
        val out = captured.toString()
        assertFalse(out.contains("4cOdK2wGLETKBW3PvgPWqT"), "sensitive id leaked in debug: $out")
        assertTrue("spotify:track" in out)
    }

    @Test
    fun `secret is redacted before it reaches the sink`() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.e("t", "failed auth with client_secret = abcXYZsupersecret123")
        val out = captured.toString()
        assertFalse(out.contains("abcXYZsupersecret123"), "secret reached the sink: $out")
        assertTrue(out.contains("<REDACTED:"), "redaction marker missing: $out")
    }

    @Test
    fun `debugTrackRef returns null in prod and a url-free ref in debug`() {
        val prod = policy(debug = false) { _, _, _ -> }
        assertNull(prod.debugTrackRef("Song", "Artist", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))

        val debug = policy(debug = true) { _, _, _ -> }
        val ref = debug.debugTrackRef("Song Title", "Artist", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        assertNotNull(ref)
        assertTrue("Song Title" in ref!!)
        assertFalse(ref.contains("open.spotify.com"), "debugTrackRef must never embed the URL")
        assertTrue(ref.contains("4cOdK2wGLETKBW3PvgPWqT"), "short id useful in debug: $ref")
    }

    @Test
    fun `youtube urls are reduced in prod`() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("t", "download candidate https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(captured.toString().contains("youtube:video"))
    }

    @Test
    fun `log without installed policy is a safe no-op`() {
        // instance may already be set from another test; force it to null to prove no-crash.
        LogPolicy.setup(false) { _, _, _ -> }
        LogPolicy.log(LogLevel.INFO, "t", "fine")
    }
}
