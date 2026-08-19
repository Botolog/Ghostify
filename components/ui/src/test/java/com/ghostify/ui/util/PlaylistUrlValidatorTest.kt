package com.ghostify.ui.util

import com.ghostify.data.model.PlaylistOrigin
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
        assertEquals(PlaylistUrlResult.Valid(id, PlaylistOrigin.SPOTIFY), result)
    }

    @Test
    fun `URI form extracts id`() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val result = PlaylistUrlValidator.validate("spotify:playlist:$id")
        assertEquals(PlaylistUrlResult.Valid(id, PlaylistOrigin.SPOTIFY), result)
    }

    @Test
    fun `trailing slash and query params still extract id`() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/playlist/$id/?si=abc123&utm_source=copy")
        assertEquals(PlaylistUrlResult.Valid(id, PlaylistOrigin.SPOTIFY), result)
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
    fun `youtube playlist URL accepted`() {
        val url = "https://youtube.com/playlist?list=PLOHoVaTp8R7dqEEhhaOG7yfDoj_y2BrZ4"
        val result = PlaylistUrlValidator.validate(url)
        assertTrue(result is PlaylistUrlResult.Valid)
        assertEquals((result as PlaylistUrlResult.Valid).origin, PlaylistOrigin.YOUTUBE)
        assertEquals(result.playlistId, url)
    }

    @Test
    fun `youtube playlist URL with www accepted`() {
        val url = "https://www.youtube.com/playlist?list=PLOHoVaTp8R7dqEEhhaOG7yfDoj_y2BrZ4"
        val result = PlaylistUrlValidator.validate(url)
        assertTrue(result is PlaylistUrlResult.Valid)
        assertEquals((result as PlaylistUrlResult.Valid).origin, PlaylistOrigin.YOUTUBE)
    }

    @Test
    fun `youtube playlist without list param rejected`() {
        assertTrue(
            PlaylistUrlValidator.validate("https://youtube.com/playlist") is PlaylistUrlResult.Invalid
        )
    }

    @Test
    fun `non-playlist youtube URL rejected`() {
        assertTrue(
            PlaylistUrlValidator.validate("https://youtube.com/watch?v=abc") is PlaylistUrlResult.Invalid
        )
    }

    @Test
    fun `garbage rejected without crashing`() {
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/playlist/../../etc/passwd") is PlaylistUrlResult.Invalid)
        assertTrue(PlaylistUrlValidator.validate("not a url at all") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `short spotify id rejected`() {
        assertTrue(PlaylistUrlValidator.validate("https://open.spotify.com/playlist/ab12") is PlaylistUrlResult.Invalid)
    }

    @Test
    fun `empty error message says enter playlist link`() {
        val result = PlaylistUrlValidator.validate("")
        assertTrue(result is PlaylistUrlResult.Invalid)
        assertEquals("Enter a playlist link", (result as PlaylistUrlResult.Invalid).message)
    }
}
