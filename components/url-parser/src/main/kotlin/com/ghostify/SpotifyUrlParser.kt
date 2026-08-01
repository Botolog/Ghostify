package com.ghostify

import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses user-pasted input into a valid Spotify playlist id.
 *
 * Accepted forms:
 *  - `https://open.spotify.com/playlist/<id>[?...][/#...]`
 *  - `https://open.spotify.com/user/<user>/playlist/<id>` (legacy share links)
 *  - `spotify:playlist:<id>`
 *  - `https://spotify.link/<code>` / `https://spoti.fi/<code>` (resolved via [shortUrlResolver])
 *
 * Anything else is rejected with a typed [RejectionReason] so the caller can show
 * a precise message. Parsing is fully defensive: hosts are whitelisted, the playlist
 * id is validated against a strict charset, path traversal and percent-encoded
 * characters never reach the caller, and short-link resolution is depth-limited.
 *
 * The parser is immutable and stateless (aside from the injected resolver), so it is
 * safe to share. Short-link resolution performs network I/O and must be called from a
 * background thread / coroutine (e.g. `Dispatchers.IO`), never the main thread.
 */
class SpotifyUrlParser(
    private val shortUrlResolver: ShortUrlResolver = HttpShortUrlResolver()
) {

    sealed class ParseResult {
        data class Success(val playlistId: String) : ParseResult()
        data class Rejected(val reason: RejectionReason) : ParseResult()
    }

    enum class RejectionReason(val userMessage: String) {
        EMPTY_INPUT("Please paste a Spotify playlist link."),
        INVALID_URI("That doesn't look like a valid link."),
        UNSUPPORTED_DOMAIN("Only Spotify links are supported."),
        NOT_A_PLAYLIST("That link isn't a Spotify playlist. Only playlists are supported."),
        MALFORMED("The playlist link is malformed."),
        MISSING_PLAYLIST_ID("The playlist link is missing its playlist id."),
        INVALID_PLAYLIST_ID("The playlist id doesn't look valid."),
        RESOLUTION_FAILED("Couldn't resolve that short link.")
    }

    fun parse(input: String?): ParseResult {
        if (input == null) return ParseResult.Rejected(RejectionReason.EMPTY_INPUT)
        return parseInternal(input.trim(), depth = 0)
    }

    private fun parseInternal(input: String, depth: Int): ParseResult {
        if (input.isEmpty()) return ParseResult.Rejected(RejectionReason.EMPTY_INPUT)
        if (input.startsWith(SCHEME_PREFIX)) return parseUriForm(input)
        return parseUrl(input, depth)
    }

    /** Handles `spotify:playlist:<id>` and rejects other `spotify:*` types. */
    private fun parseUriForm(input: String): ParseResult {
        val match = URI_FORM_REGEX.matchEntire(input)
        if (match != null) {
            val id = match.groupValues[1]
            return if (isValidPlaylistId(id)) {
                ParseResult.Success(id)
            } else {
                ParseResult.Rejected(RejectionReason.INVALID_PLAYLIST_ID)
            }
        }
        return if (input.startsWith(SCHEME_PREFIX) && !input.startsWith(PLAYLIST_SCHEME_PREFIX)) {
            ParseResult.Rejected(RejectionReason.NOT_A_PLAYLIST)
        } else {
            ParseResult.Rejected(RejectionReason.INVALID_URI)
        }
    }

    private fun parseUrl(input: String, depth: Int): ParseResult {
        val uri = runCatching { URI(input) }.getOrNull()
            ?: return ParseResult.Rejected(RejectionReason.INVALID_URI)

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return ParseResult.Rejected(RejectionReason.INVALID_URI)
        }

        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return ParseResult.Rejected(RejectionReason.INVALID_URI)

        return when (host) {
            HOST_OPEN -> extractFromOpenUrl(uri)
            HOST_SHORT_LINK, HOST_SPOTIFY_SHORT -> resolveShortUrl(input, depth)
            else -> ParseResult.Rejected(RejectionReason.UNSUPPORTED_DOMAIN)
        }
    }

    /** Resolves a short link, then re-parses the resulting URL (depth-limited). */
    private fun resolveShortUrl(input: String, depth: Int): ParseResult {
        if (depth >= MAX_RESOLVE_DEPTH) {
            return ParseResult.Rejected(RejectionReason.RESOLUTION_FAILED)
        }
        val resolved = shortUrlResolver.resolve(input)?.trim()
        if (resolved.isNullOrEmpty()) {
            return ParseResult.Rejected(RejectionReason.RESOLUTION_FAILED)
        }
        return parseInternal(resolved, depth + 1)
    }

    /**
     * Extracts the id from `open.spotify.com/<resource>/<id>` and the legacy
     * `open.spotify.com/user/<user>/playlist/<id>` form.
     *
     * Uses the raw (undecoded) path so percent-encoded characters can never be
     * interpreted as traversal; the id charset check rejects them regardless.
     */
    private fun extractFromOpenUrl(uri: URI): ParseResult {
        val rawPath = uri.rawPath ?: return ParseResult.Rejected(RejectionReason.MALFORMED)
        val segments = rawPath.split('/').filter { it.isNotEmpty() }

        if (segments.isEmpty()) {
            return ParseResult.Rejected(RejectionReason.MALFORMED)
        }
        if (segments.size == 1 && segments[0] == "playlist") {
            return ParseResult.Rejected(RejectionReason.MISSING_PLAYLIST_ID)
        }

        val resourceIndex = when {
            segments.size == 2 && segments[0] == "playlist" -> 0
            segments.size == 4 && segments[0] == "user" && segments[2] == "playlist" -> 2
            else -> return ParseResult.Rejected(
                if (segments[0] == "playlist") {
                    RejectionReason.MALFORMED
                } else {
                    RejectionReason.NOT_A_PLAYLIST
                }
            )
        }

        val id = segments[resourceIndex + 1]
        return if (isValidPlaylistId(id)) {
            ParseResult.Success(id)
        } else {
            ParseResult.Rejected(RejectionReason.INVALID_PLAYLIST_ID)
        }
    }

    private fun isValidPlaylistId(id: String): Boolean = PLAYLIST_ID_REGEX.matches(id)

    companion object {
        private const val HOST_OPEN = "open.spotify.com"
        private const val HOST_SHORT_LINK = "spotify.link"
        private const val HOST_SPOTIFY_SHORT = "spoti.fi"
        private const val SCHEME_PREFIX = "spotify:"
        private const val PLAYLIST_SCHEME_PREFIX = "spotify:playlist:"
        private const val MAX_RESOLVE_DEPTH = 3

        // Spotify ids are 22-char base62 strings; keep a deliberately tolerant
        // range (1..64 of [A-Za-z0-9]) so we never reject a real id while still
        // making path traversal / encoded garbage impossible to pass through.
        private val PLAYLIST_ID_REGEX = Regex("^[A-Za-z0-9]{1,64}$")
        private val URI_FORM_REGEX = Regex("^spotify:playlist:([A-Za-z0-9]{1,64})$")
    }
}

/** Resolves a short Spotify link (`spotify.link` / `spoti.fi`) to its target URL. */
interface ShortUrlResolver {
    fun resolve(shortUrl: String): String?
}

/**
 * Default resolver backed by [HttpURLConnection].
 *
 * Spotify's `spotify.link` short links are not plain HTTP redirects. Live-verified
 * behaviour is that a browser-like User-Agent makes Spotify redirect
 * `spotify.link/<code>` → `spotify.app.link/...` (a Branch link) → an
 * `intent://` deep link whose `S.browser_fallback_url` parameter carries the real
 * `open.spotify.com/...` URL. `HttpURLConnection`'s automatic redirect following
 * throws on the non-http `intent://` location, so this resolver walks the chain
 * manually and additionally supports the older shape where the final page's HTML
 * embeds the target link.
 *
 * Returns null on any network / parse failure so the parser can surface a typed
 * rejection. Blocking I/O — call from a background thread.
 */
class HttpShortUrlResolver(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000
) : ShortUrlResolver {

    override fun resolve(shortUrl: String): String? = runCatching {
        walkRedirects(shortUrl)
    }.getOrNull()

    /** Follows http(s) redirects manually, up to [MAX_HOPS], and extracts the target. */
    private fun walkRedirects(start: String): String? {
        var current = start
        repeat(MAX_HOPS) {
            val connection = runCatching { URL(current).openConnection() as HttpURLConnection }
                .getOrNull() ?: return null
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("User-Agent", BROWSER_UA)
                if (connection.responseCode !in 200..399) return null

                val location = connection.getHeaderField("Location")
                if (location != null) {
                    val trimmed = location.trim()
                    if (trimmed.startsWith("intent://", ignoreCase = true)) {
                        return extractFromIntentUrl(trimmed)
                    }
                    current = resolveLocation(connection, trimmed) ?: return null
                    return@repeat
                }

                val finalUrl = connection.url.toString()
                if (OPEN_SPOTIFY_REGEX.containsMatchIn(finalUrl)) return finalUrl
                return extractSpotifyOpenUrl(readBody(connection))
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    /** Resolves a possibly-relative redirect location against the current URL. */
    private fun resolveLocation(connection: HttpURLConnection, location: String): String? =
        runCatching { connection.url.toURI().resolve(location).toString() }.getOrNull()

    /** Extracts the target from an `intent://...;S.browser_fallback_url=<urlencoded>;...;end`. */
    internal fun extractFromIntentUrl(intentUrl: String): String? {
        val encoded = INTENT_FALLBACK_URL_REGEX.find(intentUrl)?.groupValues?.get(1) ?: return null
        return runCatching {
            val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            if (OPEN_SPOTIFY_REGEX.containsMatchIn(decoded)) decoded else null
        }.getOrNull()
    }

    private fun readBody(connection: HttpURLConnection): String =
        connection.inputStream.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).readText()
        }

    companion object {
        private const val MAX_HOPS = 10

        // A common mobile-browser User-Agent. Spotify behaves like a browser for
        // these and routes short links through its Branch pipeline.
        internal val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        internal val OPEN_SPOTIFY_REGEX = Regex("https?://open\\.spotify\\.com")

        internal val INTENT_FALLBACK_URL_REGEX = Regex("S\\.browser_fallback_url=([^;]+)")

        /**
         * Best-effort scrape of an `open.spotify.com` URL out of a landing page.
         * Finds the first occurrence, works back to a nearby `https://` prefix
         * (or prepends one), and cuts the URL at the first HTML/whitespace delimiter.
         * `&amp;` entity is unescaped. Returns null when nothing is found.
         */
        internal fun extractSpotifyOpenUrl(html: String): String? {
            val idx = html.indexOf("open.spotify.com")
            if (idx == -1) return null

            val candidate = html.lastIndexOf("https://", idx).let { scheme ->
                if (scheme != -1 && idx - scheme <= 40) {
                    html.substring(scheme)
                } else {
                    "https://" + html.substring(idx)
                }
            }
            val end = candidate.indexOfFirst { it == '"' || it == '\'' || it == '<' || it.isWhitespace() }
                .let { if (it == -1) candidate.length else it }
            return candidate.substring(0, end).replace("&amp;", "&")
        }
    }
}
