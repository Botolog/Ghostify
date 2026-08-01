package com.ghostify

import com.ghostify.SpotifyUrlParser.ParseResult
import com.ghostify.SpotifyUrlParser.RejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyUrlParserTest {

    private class FakeResolver(private val map: Map<String, String>) : ShortUrlResolver {
        override fun resolve(shortUrl: String): String? = map[shortUrl]
    }

    private val parser = SpotifyUrlParser(FakeResolver(emptyMap()))

    // --- T-001: full open.spotify.com URL ------------------------------------

    @Test
    fun t001_fullUrlExtractsPlaylistId() {
        val result = parser.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t001_httpSchemeAlsoWorks() {
        val result = parser.parse("http://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    // --- T-002: URI form ------------------------------------------------------

    @Test
    fun t002_uriFormExtractsPlaylistId() {
        val result = parser.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    // --- T-003: short URL resolution (needs network; logic verified here) ----

    @Test
    fun t003_shortUrlResolvesToPlaylistId() {
        val short = "https://spotify.link/AbC1dE2fG3"
        val long = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val resolverParser = SpotifyUrlParser(FakeResolver(mapOf(short to long)))

        val result = resolverParser.parse(short)

        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t003_spotifyFiShortUrlAlsoResolves() {
        val short = "https://spoti.fi/xyzABC"
        val long = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val resolverParser = SpotifyUrlParser(FakeResolver(mapOf(short to long)))

        val result = resolverParser.parse(short)

        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t003_unresolvableShortUrlIsRejected() {
        val result = parser.parse("https://spotify.link/doesNotResolve")
        assertRejected(result, RejectionReason.RESOLUTION_FAILED)
    }

    @Test
    fun t003_resolvedNonPlaylistUrlIsRejected() {
        val short = "https://spotify.link/abc"
        val long = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"
        val resolverParser = SpotifyUrlParser(FakeResolver(mapOf(short to long)))

        assertRejected(resolverParser.parse(short), RejectionReason.NOT_A_PLAYLIST)
    }

    @Test
    fun t003_infiniteShortLinkChainIsDepthLimited() {
        val a = "https://spotify.link/a"
        val b = "https://spotify.link/b"
        val long = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val resolverParser = SpotifyUrlParser(FakeResolver(mapOf(a to b, b to long)))

        val result = resolverParser.parse(a)

        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    // --- T-004: trailing slash / query params ---------------------------------

    @Test
    fun t004_trailingSlashIsIgnored() {
        val result = parser.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M/")
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t004_queryParamsAreIgnored() {
        val result = parser.parse(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123&utm_source=copy-link"
        )
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t004_queryParamsAndFragmentAreIgnored() {
        val result = parser.parse(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc#section1"
        )
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    // --- T-005: wrong resource type --------------------------------------------

    @Test
    fun t005_albumUrlIsRejected() {
        val result = parser.parse("https://open.spotify.com/album/1DFixLWuPkv3KT3TnV35m3")
        assertRejected(result, RejectionReason.NOT_A_PLAYLIST)
    }

    @Test
    fun t005_trackUrlIsRejected() {
        val result = parser.parse("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
        assertRejected(result, RejectionReason.NOT_A_PLAYLIST)
    }

    @Test
    fun t005_uriAlbumIsRejected() {
        val result = parser.parse("spotify:album:1DFixLWuPkv3KT3TnV35m3")
        assertRejected(result, RejectionReason.NOT_A_PLAYLIST)
    }

    // --- T-006: garbage / empty / null -----------------------------------------

    @Test
    fun t006_emptyStringIsRejected() {
        assertRejected(parser.parse(""), RejectionReason.EMPTY_INPUT)
    }

    @Test
    fun t006_blankStringIsRejected() {
        assertRejected(parser.parse("   "), RejectionReason.EMPTY_INPUT)
    }

    @Test
    fun t006_nullIsRejected() {
        assertRejected(parser.parse(null), RejectionReason.EMPTY_INPUT)
    }

    @Test
    fun t006_garbageStringIsRejected() {
        assertRejected(parser.parse("asdkj qwergklj 12435"), RejectionReason.INVALID_URI)
    }

    @Test
    fun t006_randomTextIsRejected() {
        assertRejected(parser.parse("not-a-url-at-all"), RejectionReason.INVALID_URI)
    }

    // --- T-007: malformed URL ---------------------------------------------------

    @Test
    fun t007_playlistPathWithoutIdIsRejected() {
        val result = parser.parse("https://open.spotify.com/playlist/")
        assertRejected(result, RejectionReason.MISSING_PLAYLIST_ID)
    }

    @Test
    fun t007_playlistPathNoSlashIsRejected() {
        val result = parser.parse("https://open.spotify.com/playlist")
        assertRejected(result, RejectionReason.MISSING_PLAYLIST_ID)
    }

    @Test
    fun t007_bareHostIsRejected() {
        assertRejected(parser.parse("https://open.spotify.com"), RejectionReason.MALFORMED)
    }

    @Test
    fun t007_missingSchemeIsRejected() {
        assertRejected(
            parser.parse("open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"),
            RejectionReason.INVALID_URI
        )
    }

    // --- T-008: path traversal / encoded chars (no crash, safe rejection) ------

    @Test
    fun t008_pathTraversalIsRejected() {
        val result = parser.parse("https://open.spotify.com/playlist/../../etc/passwd")
        assertRejected(result, RejectionReason.MALFORMED)
    }

    @Test
    fun t008_encodedTraversalIsRejected() {
        val result = parser.parse("https://open.spotify.com/playlist/%2e%2e%2f%2e%2e%2fetc")
        assertRejected(result, RejectionReason.INVALID_PLAYLIST_ID)
    }

    @Test
    fun t008_encodedSlashInIdIsRejected() {
        val result = parser.parse("https://open.spotify.com/playlist/37i9dQZF1DX%2fetc")
        assertRejected(result, RejectionReason.INVALID_PLAYLIST_ID)
    }

    @Test
    fun t008_percentGarbageDoesNotCrash() {
        assertTrue(parser.parse("https://open.spotify.com/playlist/%zz99") is ParseResult.Rejected)
    }

    // --- T-009: unrelated domain -------------------------------------------------

    @Test
    fun t009_youtubePlaylistIsRejected() {
        val result = parser.parse("https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE")
        assertRejected(result, RejectionReason.UNSUPPORTED_DOMAIN)
    }

    @Test
    fun t009_youtubePathIsRejected() {
        assertRejected(
            parser.parse("https://youtube.com/playlist/abc123"),
            RejectionReason.UNSUPPORTED_DOMAIN
        )
    }

    @Test
    fun t009_googleIsRejected() {
        assertRejected(
            parser.parse("https://www.google.com/search?q=playlist"),
            RejectionReason.UNSUPPORTED_DOMAIN
        )
    }

    // --- T-010 (supplemental): legacy user playlist URL form --------------------

    @Test
    fun t010_legacyUserPlaylistUrlExtractsId() {
        val result = parser.parse("https://open.spotify.com/user/spotify/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t010_legacyUserPlaylistUrlWithQueryIsParsed() {
        val result = parser.parse(
            "https://open.spotify.com/user/spotify/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"
        )
        assertEquals(ParseResult.Success("37i9dQZF1DXcBWIGoYBM5M"), result)
    }

    @Test
    fun t010_legacyMalformedUserPathIsRejected() {
        assertRejected(
            parser.parse("https://open.spotify.com/user/spotify"),
            RejectionReason.NOT_A_PLAYLIST
        )
    }

    // --- T-011 (supplemental): short-link HTML fallback extraction --------------

    @Test
    fun t011_htmlFallbackExtractsSpotifyUrl() {
        val html = "<html><body><a href=\"https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc&amp;utm=1\">x</a></body></html>"
        assertEquals(
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc&utm=1",
            HttpShortUrlResolver.extractSpotifyOpenUrl(html)
        )
    }

    @Test
    fun t011_htmlFallbackWithoutSchemePrependsHttps() {
        val html = "window.location='open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC'"
        assertEquals(
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            HttpShortUrlResolver.extractSpotifyOpenUrl(html)
        )
    }

    @Test
    fun t011_htmlFallbackReturnsNullWhenAbsent() {
        assertEquals(null, HttpShortUrlResolver.extractSpotifyOpenUrl("<html>no link here</html>"))
    }

    @Test
    fun t011_intentFallbackUrlIsExtractedAndDecoded() {
        val resolver = HttpShortUrlResolver()
        val intent = "intent://playlist/5zPbGYMyILmVCty6TJ9mOO?si=x#Intent;scheme=spotify;" +
            "S.browser_fallback_url=https%3A%2F%2Fopen.spotify.com%2Fplaylist%2F5zPbGYMyILmVCty6TJ9mOO" +
            "%3Fsi%3Dx%26utm_source%3Dcopy-link;S.market_referrer=link_click_id%3D1;end"
        assertEquals(
            "https://open.spotify.com/playlist/5zPbGYMyILmVCty6TJ9mOO?si=x&utm_source=copy-link",
            resolver.extractFromIntentUrl(intent)
        )
    }

    @Test
    fun t011_intentWithoutFallbackReturnsNull() {
        assertEquals(
            null,
            HttpShortUrlResolver().extractFromIntentUrl("intent://playlist/x#Intent;scheme=spotify;end")
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun assertRejected(result: ParseResult, reason: RejectionReason) {
        val rejected = result as? ParseResult.Rejected
        assertEquals("Expected rejection but got: $result", reason, rejected?.reason)
    }
}
