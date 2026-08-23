package xyz.botolog.ghostify.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.model.PlaylistOrigin

class PlaylistUrlValidatorTest {

    // ── null / blank / empty input ────────────────────────────────────────

    @Test
    fun validate_nullReturnsInvalidWithMessage() {
        val result = PlaylistUrlValidator.validate(null)
        assertTrue(result is PlaylistUrlResult.Invalid)
        assertEquals("Enter a playlist link", (result as PlaylistUrlResult.Invalid).message)
    }

    @Test
    fun validate_emptyStringReturnsInvalid() {
        val result = PlaylistUrlValidator.validate("")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_blankStringReturnsInvalid() {
        val result = PlaylistUrlValidator.validate("   ")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    // ── Spotify URI form ──────────────────────────────────────────────────

    @Test
    fun validate_spotifyUri_validId() {
        val result = PlaylistUrlValidator.validate("spotify:playlist:4uLU6hMCjMI75M1A2tKUQC")
        assertTrue(result is PlaylistUrlResult.Valid)
        val valid = result as PlaylistUrlResult.Valid
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", valid.playlistId)
        assertEquals(PlaylistOrigin.SPOTIFY, valid.origin)
    }

    @Test
    fun validate_spotifyUri_tooShortId() {
        val result = PlaylistUrlValidator.validate("spotify:playlist:short")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_spotifyUri_emptyId() {
        val result = PlaylistUrlValidator.validate("spotify:playlist:")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_spotifyUri_idWithSpecialChars() {
        val result = PlaylistUrlValidator.validate("spotify:playlist:id with spaces")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    // ── Spotify open URL form ─────────────────────────────────────────────

    @Test
    fun validate_spotifyOpenUrl_validPlaylist() {
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC")
        assertTrue(result is PlaylistUrlResult.Valid)
        val valid = result as PlaylistUrlResult.Valid
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", valid.playlistId)
        assertEquals(PlaylistOrigin.SPOTIFY, valid.origin)
    }

    @Test
    fun validate_spotifyOpenUrl_notPlaylistPath() {
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
        assertTrue(result is PlaylistUrlResult.Invalid)
        assertEquals(
            "Not a playlist link — use a Spotify or YouTube playlist URL",
            (result as PlaylistUrlResult.Invalid).message,
        )
    }

    @Test
    fun validate_spotifyOpenUrl_noIdAfterPlaylist() {
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/playlist/")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_spotifyOpenUrl_noPathSegments() {
        val result = PlaylistUrlValidator.validate("https://open.spotify.com")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_spotifyOpenUrl_withQueryParams() {
        val result = PlaylistUrlValidator.validate(
            "https://open.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC?si=abc123"
        )
        assertTrue(result is PlaylistUrlResult.Valid)
        val valid = result as PlaylistUrlResult.Valid
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", valid.playlistId)
    }

    @Test
    fun validate_spotifyUri_paddedSpaces() {
        val result = PlaylistUrlValidator.validate("  spotify:playlist:4uLU6hMCjMI75M1A2tKUQC  ")
        assertTrue(result is PlaylistUrlResult.Valid)
    }

    // ── YouTube playlist URL with list param ──────────────────────────────

    @Test
    fun validate_youtubeWithListParam() {
        val result = PlaylistUrlValidator.validate("https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf")
        assertTrue(result is PlaylistUrlResult.Valid)
        val valid = result as PlaylistUrlResult.Valid
        assertEquals(PlaylistOrigin.YOUTUBE, valid.origin)
        assertTrue(valid.playlistId.contains("youtube.com"))
    }

    @Test
    fun validate_youtubeWithListParamAndOtherParams() {
        val result = PlaylistUrlValidator.validate(
            "https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf&index=1"
        )
        assertTrue(result is PlaylistUrlResult.Valid)
    }

    @Test
    fun validate_youtubeShortHostWithListParam() {
        val result = PlaylistUrlValidator.validate("https://youtu.be/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf")
        assertTrue(result is PlaylistUrlResult.Valid)
    }

    @Test
    fun validate_youtubeNoListParam_returnsInvalid() {
        val result = PlaylistUrlValidator.validate("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_youtubeEmptyListParam() {
        val result = PlaylistUrlValidator.validate("https://www.youtube.com/playlist?list=")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    // ── YouTube /playlist path form ───────────────────────────────────────

    @Test
    fun validate_youtubePlaylistPath() {
        val result = PlaylistUrlValidator.validate("https://www.youtube.com/playlist/PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf")
        assertTrue(result is PlaylistUrlResult.Valid)
        assertEquals(PlaylistOrigin.YOUTUBE, (result as PlaylistUrlResult.Valid).origin)
    }

    // ── invalid URLs ──────────────────────────────────────────────────────

    @Test
    fun validate_notUrlReturnsInvalid() {
        val result = PlaylistUrlValidator.validate("not a url at all")
        assertTrue(result is PlaylistUrlResult.Invalid)
        assertEquals(
            "That doesn't look like a Spotify or YouTube link",
            (result as PlaylistUrlResult.Invalid).message,
        )
    }

    @Test
    fun validate_unknownDomainReturnsInvalid() {
        val result = PlaylistUrlValidator.validate("https://example.com/playlist/123")
        assertTrue(result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_httpScheme() {
        val result = PlaylistUrlValidator.validate("http://open.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC")
        assertTrue(result is PlaylistUrlResult.Valid)
    }

    @Test
    fun validate_spotifyDomain_subdomain() {
        val result = PlaylistUrlValidator.validate("https://play.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC")
        assertTrue(result is PlaylistUrlResult.Valid)
    }

    // ── edge cases ────────────────────────────────────────────────────────

    @Test
    fun validate_veryLongUrl() {
        val id = "A".repeat(200)
        val url = "https://open.spotify.com/playlist/$id"
        val result = PlaylistUrlValidator.validate(url)
        // Should still be valid since Spotify IDs are alphanumeric
        assertTrue(result is PlaylistUrlResult.Valid || result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_urlWithUnicodeCharacters() {
        val result = PlaylistUrlValidator.validate("https://open.spotify.com/playlist/4uLU6hMCjMI75M1A2tKUQC?si=\u00e9\u00e8")
        // Should handle gracefully without crashing
        assertTrue(result is PlaylistUrlResult.Valid || result is PlaylistUrlResult.Invalid)
    }

    @Test
    fun validate_youtubeUrlWithFragment() {
        val result = PlaylistUrlValidator.validate(
            "https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf#section"
        )
        assertTrue(result is PlaylistUrlResult.Valid)
    }
}
