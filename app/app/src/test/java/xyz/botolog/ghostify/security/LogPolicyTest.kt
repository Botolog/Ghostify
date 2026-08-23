package xyz.botolog.ghostify.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogPolicyTest {

    @Before
    fun setUp() {
        if (timber.log.Timber.treeCount == 0) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }
    }

    private fun policy(debug: Boolean, sink: (LogLevel, String, String) -> Unit): LogPolicy =
        LogPolicy.setup(debug, sink)

    // --- verbose / debug messages suppressed in prod ---

    @Test
    fun prod_drops_verbose_message() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.v("tag", "verbose detail")
        assertTrue(captured.isEmpty())
    }

    @Test
    fun prod_drops_debug_message() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.d("tag", "debug detail")
        assertTrue(captured.isEmpty())
    }

    @Test
    fun prod_emits_info() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("tag", "info survives")
        assertEquals("info survives", captured.toString())
    }

    @Test
    fun prod_emits_warn() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.w("tag", "warn survives")
        assertEquals("warn survives", captured.toString())
    }

    @Test
    fun prod_emits_error() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.e("tag", "error survives")
        assertEquals("error survives", captured.toString())
    }

    @Test
    fun debug_emits_verbose() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.v("tag", "verbose in debug")
        assertEquals("verbose in debug", captured.toString())
    }

    @Test
    fun debug_emits_debug() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.d("tag", "debug in debug")
        assertEquals("debug in debug", captured.toString())
    }

    // --- Spotify URL sanitization ---

    @Test
    fun prod_strips_spotify_url_to_bare_resource() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("tag", "opened playlist https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=deadbeef")
        val out = captured.toString()
        assertTrue("resource not preserved in prod: $out", "spotify:playlist" in out)
        assertFalse("full URL/id leaked: $out", out.contains("37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse("query params leaked: $out", out.contains("si=deadbeef"))
    }

    @Test
    fun prod_strips_spotify_track_url() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("tag", "playing https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc")
        val out = captured.toString()
        assertTrue("spotify:track" in out)
        assertFalse(out.contains("4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun prod_strips_spotify_album_url() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("tag", "album https://open.spotify.com/album/abc123def456")
        val out = captured.toString()
        assertTrue("spotify:album" in out)
        assertFalse(out.contains("abc123def456"))
    }

    @Test
    fun prod_strips_spotify_artist_url() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("tag", "artist https://open.spotify.com/artist/abcXYZ123456")
        val out = captured.toString()
        assertTrue("spotify:artist" in out)
        assertFalse(out.contains("abcXYZ123456"))
    }

    @Test
    fun debug_keeps_short_id_for_spotify() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("tag", "resolved https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc")
        val out = captured.toString()
        assertTrue("debug should keep short id: $out", out.contains("4cOdK2wGLETKBW3PvgPWqT"))
        assertFalse("query params must still be stripped: $out", out.contains("?si=abc"))
    }

    // --- sensitive flag ---

    @Test
    fun sensitive_message_prod_strict_even_in_debug() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("tag", "synced track https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", sensitive = true)
        val out = captured.toString()
        assertFalse("sensitive id leaked in debug: $out", out.contains("4cOdK2wGLETKBW3PvgPWqT"))
        assertTrue("spotify:track" in out)
    }

    @Test
    fun non_sensitive_in_debug_keeps_id() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("tag", "resolved https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc", sensitive = false)
        val out = captured.toString()
        assertTrue(out.contains("4cOdK2wGLETKBW3PvgPWqT"))
        assertFalse(out.contains("?si=abc"))
    }

    // --- Secret redaction through LogPolicy ---

    @Test
    fun secret_redacted_before_reaching_sink() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.e("tag", "failed auth with client_secret = abcXYZsupersecret123")
        val out = captured.toString()
        assertFalse("secret reached the sink: $out", out.contains("abcXYZsupersecret123"))
        assertTrue("redaction marker missing: $out", out.contains("<REDACTED:"))
    }

    @Test
    fun bearer_token_redacted_in_log() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.e("tag", "auth failed: Bearer eyJhbGciOiJIUzI1NiJ9.token.value")
        val out = captured.toString()
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9.token.value"))
        assertTrue(out.contains("<REDACTED:auth>"))
    }

    // --- YouTube URL sanitization ---

    @Test
    fun prod_reduces_youtube_url() {
        val captured = StringBuilder()
        val p = policy(debug = false) { _, _, msg -> captured.append(msg) }
        p.i("tag", "download candidate https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(captured.toString().contains("youtube:video"))
        assertFalse(captured.toString().contains("dQw4w9WgXcQ"))
    }

    @Test
    fun debug_keeps_youtube_id() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("tag", "download https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(captured.toString().contains("youtube:video:dQw4w9WgXcQ"))
    }

    // --- debugTrackRef ---

    @Test
    fun debugTrackRef_returns_null_in_prod() {
        val prod = policy(debug = false) { _, _, _ -> }
        assertNull(prod.debugTrackRef("Song", "Artist", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun debugTrackRef_returns_ref_with_id_in_debug() {
        val debug = policy(debug = true) { _, _, _ -> }
        val ref = debug.debugTrackRef("Song Title", "Artist", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        assertNotNull(ref)
        assertTrue("Song Title" in ref!!)
        assertFalse("debugTrackRef must never embed the URL", ref.contains("open.spotify.com"))
        assertTrue("short id useful in debug: $ref", ref.contains("4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun debugTrackRef_handles_null_title() {
        val debug = policy(debug = true) { _, _, _ -> }
        val ref = debug.debugTrackRef(null, "Artist", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")
        assertNotNull(ref)
        assertTrue(ref!!.contains("?"))
        assertTrue(ref.contains("Artist"))
    }

    @Test
    fun debugTrackRef_handles_null_artists() {
        val debug = policy(debug = true) { _, _, _ -> }
        val ref = debug.debugTrackRef("Song Title", null, "https://open.spotify.com/track/abc123")
        assertNotNull(ref)
        assertTrue(ref!!.contains("Song Title"))
        assertTrue(ref.contains("?"))
    }

    @Test
    fun debugTrackRef_handles_null_url() {
        val debug = policy(debug = true) { _, _, _ -> }
        val ref = debug.debugTrackRef("Song", "Artist", null)
        assertNotNull(ref)
        assertTrue(ref!!.contains("Song"))
        assertTrue(ref.contains("Artist"))
    }

    // --- Companion log() ---

    @Test
    fun companion_log_delegates_to_instance() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        LogPolicy.log(LogLevel.INFO, "tag", "companion works")
        assertEquals("companion works", captured.toString())
    }

    // --- Default sink fallback ---

    @Test
    fun setup_returns_log_policy_instance() {
        val p = policy(debug = false) { _, _, _ -> }
        assertNotNull(p)
    }

    @Test
    fun log_with_empty_message_is_safe() {
        val captured = StringBuilder()
        val p = policy(debug = true) { _, _, msg -> captured.append(msg) }
        p.i("tag", "")
        assertEquals("", captured.toString())
    }
}
