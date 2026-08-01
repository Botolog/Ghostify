package com.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUrlValidatorTest {

    @Test
    fun `empty and blank input rejected`() {
        assertTrue(PlaylistUrlValidator.validate(null) is PlaylistUrlResult.Invalid)
        assertTrue(PlaylistUrlValidator.validate("") is PlaylistUrlResult.Invalid)
        assertTrue(PlaylistUrlValidator.validate("   ") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `full open spotify URL extracts id`() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/playlist/$id")
        assertEquals(PlaylistUrlResult.Valid(id), result)
    }

    @Test
    fun `URI form extracts id`() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val result = PlaylistUrlValidator.validate("spotify:playlist:$id")
        assertEquals(PlaylistUrlResult.Valid(id), result)
    }

    @Test
    fun `trailing slash and query params still extract id`() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/playlist/$id/?si=abc123&utm_source=copy")
        assertEquals(PlaylistUrlResult.Valid(id), result)
    }

    @Test
    fun `wrong type album or track rejected`() {
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/album/1") is PlaylistUrlResult.Invalid)
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/track/1") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `malformed playlist URL with no id rejected`() {
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/playlist/") is PlaylistUrlResult.Invalid)
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/playlist/!@#\$") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `unrelated domain rejected`() {
        assertTrue(PlaylistUrlValidator.validate("https://youtube.com/playlist/abc") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `garbage rejected without crashing`() {
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/playlist/../../etc/passwd") is PlaylistUrlResult.Invalid)
        assertTrue(PlaylistUrlValidator.validate("not a url at all") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `short id rejected`() {
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/playlist/ab12") is PlaylistUrlResult.Invalid)
    }
}
