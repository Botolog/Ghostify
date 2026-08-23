package xyz.botolog.ghostify.ui.util

import xyz.botolog.ghostify.data.model.PlaylistOrigin
import java.net.URI

/**
 * First-line URL validation for the Add-playlist dialog.
 *
 * Accepts **both** Spotify playlist URLs and YouTube playlist URLs.
 * The returned [PlaylistUrlResult.Valid] carries the raw URL (for YouTube)
 * or the extracted Spotify id (for Spotify), together with the [PlaylistOrigin]
 * so callers know which fetch path to take.
 *
 * Pure Kotlin (JVM testable).
 */
sealed interface PlaylistUrlResult {

    /** A valid playlist URL with the resolved [playlistId] and [origin]. */
    data class Valid(val playlistId: String, val origin: PlaylistOrigin) : PlaylistUrlResult

    /** An invalid URL with a user-facing [message] explaining the problem. */
    data class Invalid(val message: String) : PlaylistUrlResult
}

/**
 * Validates and parses Spotify and YouTube playlist URLs.
 *
 * This is a pure Kotlin utility (JVM testable) with no Android or Compose dependencies.
 */
object PlaylistUrlValidator {

    private const val SPOTIFY_PLAYLIST_PREFIX = "spotify:playlist:"
    private const val YOUTUBE_HOST_KEYWORD = "youtube.com"
    private const val YOUTUBE_SHORT_HOST = "youtu.be"
    private const val SPOTIFY_DOMAIN_SUFFIX = "spotify.com"
    private const val PLAYLIST_PATH_SEGMENT = "playlist"
    private const val LIST_QUERY_PARAM_PREFIX = "list="
    private const val MIN_SPOTIFY_ID_LENGTH = 15
    private const val MIN_PATH_SEGMENTS_FOR_YOUTUBE_PLAYLIST = 2

    /**
     * Validates the given [input] string and returns a [PlaylistUrlResult].
     *
     * @param input raw URL string from the user; may be `null` or blank.
     * @return [PlaylistUrlResult.Valid] with the resolved id and origin, or
     *   [PlaylistUrlResult.Invalid] with a user-facing error message.
     */
    fun validate(input: String?): PlaylistUrlResult {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) {
            return PlaylistUrlResult.Invalid("Enter a playlist link")
        }

        // --- Spotify URI form: spotify:playlist:<id> ---
        if (text.startsWith(SPOTIFY_PLAYLIST_PREFIX)) {
            return extractSpotifyId(text.removePrefix(SPOTIFY_PLAYLIST_PREFIX))
        }

        val uri = runCatching { URI(text) }.getOrNull()
        val host = uri?.host?.lowercase()
            ?: return PlaylistUrlResult.Invalid("That doesn't look like a Spotify or YouTube link")

        // --- YouTube playlist URLs ---
        if (host.contains(YOUTUBE_HOST_KEYWORD) || host == YOUTUBE_SHORT_HOST) {
            return validateYouTubePlaylist(text, uri)
        }

        // --- Spotify open URL form ---
        if (host.endsWith(SPOTIFY_DOMAIN_SUFFIX)) {
            return validateSpotifyPlaylist(uri)
        }

        return PlaylistUrlResult.Invalid("That doesn't look like a Spotify or YouTube link")
    }

    /**
     * Validates a YouTube playlist URL by checking for a `list=` query parameter
     * or a `/playlist` path segment.
     */
    private fun validateYouTubePlaylist(
        text: String,
        uri: URI,
    ): PlaylistUrlResult {
        val query = uri.query ?: ""
        val listParam = query.split("&").firstOrNull { it.startsWith(LIST_QUERY_PARAM_PREFIX) }
        if (listParam != null && listParam.length > LIST_QUERY_PARAM_PREFIX.length) {
            return PlaylistUrlResult.Valid(text, PlaylistOrigin.YOUTUBE)
        }
        // youtu.be short playlist links
        val segments = (uri.path ?: "").split("/").filter { it.isNotBlank() }
        if (segments.size >= MIN_PATH_SEGMENTS_FOR_YOUTUBE_PLAYLIST && segments[0] == PLAYLIST_PATH_SEGMENT) {
            return PlaylistUrlResult.Valid(text, PlaylistOrigin.YOUTUBE)
        }
        return PlaylistUrlResult.Invalid("Not a YouTube playlist link — use a URL with ?list=<id>")
    }

    /**
     * Validates a Spotify open-URL playlist link by inspecting the path segments.
     */
    private fun validateSpotifyPlaylist(uri: URI): PlaylistUrlResult {
        val path = uri.path ?: ""
        val segments = path.split("/").filter { it.isNotBlank() }

        if (segments.isEmpty()) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        if (segments[0] != PLAYLIST_PATH_SEGMENT) {
            return PlaylistUrlResult.Invalid("Not a playlist link — use a Spotify or YouTube playlist URL")
        }
        if (segments.size < 2) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        return extractSpotifyId(segments[1])
    }

    /**
     * Extracts and validates a Spotify playlist ID from raw input.
     */
    private fun extractSpotifyId(rawId: String): PlaylistUrlResult {
        val id = rawId.trim()
        if (id.isEmpty() || id.any { !it.isLetterOrDigit() }) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        if (id.length < MIN_SPOTIFY_ID_LENGTH) {
            return PlaylistUrlResult.Invalid("That doesn't look like a valid Spotify playlist")
        }
        return PlaylistUrlResult.Valid(id, PlaylistOrigin.SPOTIFY)
    }
}
