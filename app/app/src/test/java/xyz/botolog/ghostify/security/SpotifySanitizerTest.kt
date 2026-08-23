package xyz.botolog.ghostify.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotifySanitizerTest {

    @Before
    fun setUp() {
        if (timber.log.Timber.treeCount == 0) {
            timber.log.Timber.plant(timber.log.Timber.DebugTree())
        }
    }

    // --- OPEN_SPOTIFY URL sanitization - PROD ---

    @Test
    fun prod_strips_track_url_to_bare_resource() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:track", out)
    }

    @Test
    fun prod_strips_playlist_url() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:playlist", out)
    }

    @Test
    fun prod_strips_album_url() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/album/abc123def456",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:album", out)
    }

    @Test
    fun prod_strips_artist_url() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/artist/abcXYZ123456",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:artist", out)
    }

    @Test
    fun prod_strips_episode_url() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/episode/abc123def456",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:episode", out)
    }

    @Test
    fun prod_strips_show_url() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/show/abc123def456",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:show", out)
    }

    @Test
    fun prod_strips_query_params() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/track/abc123?si=xyz&dl_branch=1",
            SanitizeLevel.PROD,
        )
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("si="))
        assertFalse(out.contains("dl_branch="))
    }

    @Test
    fun prod_strips_intl_prefix() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/intl-en/track/abcXYZ123",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:track", out)
    }

    @Test
    fun prod_strips_url_with_fragment() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/track/abcXYZ123#section",
            SanitizeLevel.PROD,
        )
        assertFalse(out.contains("abcXYZ123"))
    }

    // --- OPEN_SPOTIFY URL sanitization - DEBUG ---

    @Test
    fun debug_keeps_track_id() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
            SanitizeLevel.DEBUG,
        )
        assertTrue(out.contains("4cOdK2wGLETKBW3PvgPWqT"))
        assertTrue(out.contains("open.spotify.com/track/"))
    }

    @Test
    fun debug_keeps_playlist_id() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
            SanitizeLevel.DEBUG,
        )
        assertTrue(out.contains("37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun debug_strips_query_params() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/track/abc123?si=xyz",
            SanitizeLevel.DEBUG,
        )
        assertTrue(out.contains("abc123"))
        assertFalse(out.contains("si=xyz"))
    }

    @Test
    fun debug_strips_fragment() {
        val out = SpotifySanitizer.sanitize(
            "https://open.spotify.com/track/abc123#section",
            SanitizeLevel.DEBUG,
        )
        assertTrue(out.contains("abc123"))
        assertFalse(out.contains("#section"))
    }

    // --- SPOTIFY_APP_LINK ---

    @Test
    fun prod_strips_app_link() {
        val out = SpotifySanitizer.sanitize(
            "https://spotify.app.link/abcXYZ123",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:link", out)
    }

    @Test
    fun debug_keeps_app_link() {
        val input = "https://spotify.app.link/abcXYZ123"
        val out = SpotifySanitizer.sanitize(input, SanitizeLevel.DEBUG)
        assertEquals(input, out)
    }

    // --- YOUTUBE_URL ---

    @Test
    fun prod_reduces_youtube_watch_url() {
        val out = SpotifySanitizer.sanitize(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            SanitizeLevel.PROD,
        )
        assertEquals("youtube:video", out)
    }

    @Test
    fun prod_reduces_youtube_shorts_url() {
        val out = SpotifySanitizer.sanitize(
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            SanitizeLevel.PROD,
        )
        assertEquals("youtube:video", out)
    }

    @Test
    fun prod_reduces_youtube_embed_url() {
        val out = SpotifySanitizer.sanitize(
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            SanitizeLevel.PROD,
        )
        assertEquals("youtube:video", out)
    }

    @Test
    fun prod_reduces_mobile_youtube() {
        val out = SpotifySanitizer.sanitize(
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            SanitizeLevel.PROD,
        )
        assertEquals("youtube:video", out)
    }

    @Test
    fun debug_keeps_youtube_id() {
        val out = SpotifySanitizer.sanitize(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            SanitizeLevel.DEBUG,
        )
        assertEquals("youtube:video:dQw4w9WgXcQ", out)
    }

    @Test
    fun debug_keeps_youtube_shorts_id() {
        val out = SpotifySanitizer.sanitize(
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            SanitizeLevel.DEBUG,
        )
        assertEquals("youtube:video:dQw4w9WgXcQ", out)
    }

    // --- YOUTUBE_SHORT (youtu.be) ---

    @Test
    fun prod_reduces_youtu_be() {
        val out = SpotifySanitizer.sanitize(
            "https://youtu.be/dQw4w9WgXcQ",
            SanitizeLevel.PROD,
        )
        assertEquals("youtube:video", out)
    }

    @Test
    fun debug_keeps_youtu_be_id() {
        val out = SpotifySanitizer.sanitize(
            "https://youtu.be/dQw4w9WgXcQ",
            SanitizeLevel.DEBUG,
        )
        assertEquals("youtube:video:dQw4w9WgXcQ", out)
    }

    // --- BARE_SPOTIFY_URI ---

    @Test
    fun prod_strips_bare_track_uri() {
        val out = SpotifySanitizer.sanitize(
            "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:track", out)
    }

    @Test
    fun prod_strips_bare_playlist_uri() {
        val out = SpotifySanitizer.sanitize(
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:playlist", out)
    }

    @Test
    fun prod_strips_bare_album_uri() {
        val out = SpotifySanitizer.sanitize(
            "spotify:album:abc123def456",
            SanitizeLevel.PROD,
        )
        assertEquals("spotify:album", out)
    }

    @Test
    fun debug_keeps_bare_uri_unchanged() {
        val input = "spotify:track:4cOdK2wGLETKBW3PvgPWqT"
        val out = SpotifySanitizer.sanitize(input, SanitizeLevel.DEBUG)
        assertEquals(input, out)
    }

    // --- Multiple URLs in one text ---

    @Test
    fun prod_sanitize_multiple_urls() {
        val text = """Link 1: https://open.spotify.com/track/abc123
Link 2: https://open.spotify.com/playlist/def456
Link 3: https://www.youtube.com/watch?v=xyz789"""
        val out = SpotifySanitizer.sanitize(text, SanitizeLevel.PROD)
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("def456"))
        assertFalse(out.contains("xyz789"))
        assertTrue(out.contains("spotify:track"))
        assertTrue(out.contains("spotify:playlist"))
        assertTrue(out.contains("youtube:video"))
    }

    @Test
    fun debug_sanitize_multiple_urls() {
        val text = """Link 1: https://open.spotify.com/track/abc123
Link 2: https://www.youtube.com/watch?v=xyz789"""
        val out = SpotifySanitizer.sanitize(text, SanitizeLevel.DEBUG)
        assertTrue(out.contains("abc123"))
        assertTrue(out.contains("xyz789"))
    }

    // --- No URLs: text unchanged ---

    @Test
    fun sanitize_unchanged_when_no_urls() {
        val text = "This is a normal message with no URLs."
        assertEquals(text, SpotifySanitizer.sanitize(text, SanitizeLevel.PROD))
        assertEquals(text, SpotifySanitizer.sanitize(text, SanitizeLevel.DEBUG))
    }

    @Test
    fun sanitize_empty_string() {
        assertEquals("", SpotifySanitizer.sanitize("", SanitizeLevel.PROD))
        assertEquals("", SpotifySanitizer.sanitize("", SanitizeLevel.DEBUG))
    }

    // --- Idempotent ---

    @Test
    fun sanitize_is_idempotent_prod() {
        val text = "https://open.spotify.com/track/abcXYZ123"
        val once = SpotifySanitizer.sanitize(text, SanitizeLevel.PROD)
        val twice = SpotifySanitizer.sanitize(once, SanitizeLevel.PROD)
        assertEquals(once, twice)
    }

    // --- trackRef ---

    @Test
    fun trackRef_returns_null_in_prod() {
        assertNull(SpotifySanitizer.trackRef("Song", "Artist", "https://open.spotify.com/track/abc", SanitizeLevel.PROD))
    }

    @Test
    fun trackRef_returns_ref_with_id_in_debug() {
        val ref = SpotifySanitizer.trackRef("Song Title", "Artist", "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", SanitizeLevel.DEBUG)
        assertNotNull(ref)
        assertTrue(ref!!.contains("Song Title"))
        assertTrue(ref.contains("Artist"))
        assertTrue(ref.contains("4cOdK2wGLETKBW3PvgPWqT"))
        assertTrue(ref.contains(" - "))
        assertTrue(ref.contains("["))
        assertTrue(ref.contains("]"))
    }

    @Test
    fun trackRef_truncates_long_title() {
        val longTitle = "A Very Long Song Title That Goes On And On And Should Be Truncated At Forty Characters"
        val ref = SpotifySanitizer.trackRef(longTitle, "Artist", "https://open.spotify.com/track/abc123", SanitizeLevel.DEBUG)
        assertNotNull(ref)
        assertTrue(ref!!.length < longTitle.length + 50)
    }

    @Test
    fun trackRef_truncates_long_artist() {
        val longArtist = "A Very Long Artist Name That Goes On And On And Should Be Truncated At Forty Characters"
        val ref = SpotifySanitizer.trackRef("Song", longArtist, "https://open.spotify.com/track/abc123", SanitizeLevel.DEBUG)
        assertNotNull(ref)
    }

    @Test
    fun trackRef_handles_null_title() {
        val ref = SpotifySanitizer.trackRef(null, "Artist", "https://open.spotify.com/track/abc123", SanitizeLevel.DEBUG)
        assertNotNull(ref)
        assertTrue(ref!!.contains("?"))
    }

    @Test
    fun trackRef_handles_null_artists() {
        val ref = SpotifySanitizer.trackRef("Song", null, "https://open.spotify.com/track/abc123", SanitizeLevel.DEBUG)
        assertNotNull(ref)
        assertTrue(ref!!.contains("Song"))
        assertTrue(ref.contains("?"))
    }

    @Test
    fun trackRef_handles_null_url() {
        val ref = SpotifySanitizer.trackRef("Song", "Artist", null, SanitizeLevel.DEBUG)
        assertNotNull(ref)
        assertTrue(ref!!.contains("Song"))
        assertTrue(ref.contains("Artist"))
        assertFalse(ref.contains("["))
    }

    @Test
    fun trackRef_handles_empty_strings() {
        val ref = SpotifySanitizer.trackRef("", "", "", SanitizeLevel.DEBUG)
        assertNotNull(ref)
    }

    // --- shortIdOf ---

    @Test
    fun shortIdOf_extracts_track_id() {
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", SpotifySanitizer.shortIdOf("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
    }

    @Test
    fun shortIdOf_extracts_playlist_id() {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifySanitizer.shortIdOf("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun shortIdOf_strips_query_params() {
        assertEquals("abc123", SpotifySanitizer.shortIdOf("https://open.spotify.com/track/abc123?si=xyz"))
    }

    @Test
    fun shortIdOf_strips_fragment() {
        assertEquals("abc123", SpotifySanitizer.shortIdOf("https://open.spotify.com/track/abc123#section"))
    }

    @Test
    fun shortIdOf_handles_intl_prefix() {
        assertEquals("abcXYZ123", SpotifySanitizer.shortIdOf("https://open.spotify.com/intl-en/track/abcXYZ123"))
    }

    @Test
    fun shortIdOf_returns_null_for_null_input() {
        assertNull(SpotifySanitizer.shortIdOf(null))
    }

    @Test
    fun shortIdOf_returns_null_for_empty_string() {
        assertNull(SpotifySanitizer.shortIdOf(""))
    }

    @Test
    fun shortIdOf_returns_null_for_non_spotify_url() {
        assertNull(SpotifySanitizer.shortIdOf("https://example.com/track/abc123"))
    }

    @Test
    fun shortIdOf_returns_null_for_bare_text() {
        assertNull(SpotifySanitizer.shortIdOf("not a url"))
    }

    @Test
    fun shortIdOf_handles_trailing_slash() {
        assertEquals("abc123", SpotifySanitizer.shortIdOf("https://open.spotify.com/track/abc123/"))
    }
}
