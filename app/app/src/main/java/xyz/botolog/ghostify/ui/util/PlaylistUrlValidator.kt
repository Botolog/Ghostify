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
    data class Valid(val playlistId: String, val origin: PlaylistOrigin) : PlaylistUrlResult
    data class Invalid(val message: String) : PlaylistUrlResult
}

object PlaylistUrlValidator {

    fun validate(input: String?): PlaylistUrlResult {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) {
            return PlaylistUrlResult.Invalid("Enter a playlist link")
        }

        // --- Spotify URI form: spotify:playlist:<id> ---
        if (text.startsWith("spotify:playlist:")) {
            return extractSpotifyId(text.removePrefix("spotify:playlist:"))
        }

        val uri = runCatching { URI(text) }.getOrNull()
        val host = uri?.host?.lowercase() ?: return PlaylistUrlResult.Invalid("That doesn't look like a Spotify or YouTube link")

        // --- YouTube playlist URLs ---
        if (host.contains("youtube.com") || host == "youtu.be") {
            return validateYouTubePlaylist(text, uri)
        }

        // --- Spotify open URL form ---
        if (host.endsWith("spotify.com")) {
            return validateSpotifyPlaylist(uri)
        }

        return PlaylistUrlResult.Invalid("That doesn't look like a Spotify or YouTube link")
    }

    private fun validateYouTubePlaylist(
        text: String,
        uri: URI,
    ): PlaylistUrlResult {
        val query = uri.query ?: ""
        val listParam = query.split("&").firstOrNull { it.startsWith("list=") }
        if (listParam != null && listParam.length > 5) {
            return PlaylistUrlResult.Valid(text, PlaylistOrigin.YOUTUBE)
        }
        // youtu.be short playlist links
        val segments = (uri.path ?: "").split("/").filter { it.isNotBlank() }
        if (segments.size >= 2 && segments[0] == "playlist") {
            return PlaylistUrlResult.Valid(text, PlaylistOrigin.YOUTUBE)
        }
        return PlaylistUrlResult.Invalid("Not a YouTube playlist link — use a URL with ?list=<id>")
    }

    private fun validateSpotifyPlaylist(uri: URI): PlaylistUrlResult {
        val path = uri.path ?: ""
        val segments = path.split("/").filter { it.isNotBlank() }

        if (segments.isEmpty()) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        if (segments[0] != "playlist") {
            return PlaylistUrlResult.Invalid("Not a playlist link — use a Spotify or YouTube playlist URL")
        }
        if (segments.size < 2) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        return extractSpotifyId(segments[1])
    }

    private fun extractSpotifyId(rawId: String): PlaylistUrlResult {
        val id = rawId.trim()
        if (id.isEmpty() || id.any { !it.isLetterOrDigit() }) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        if (id.length < 15) {
            return PlaylistUrlResult.Invalid("That doesn't look like a valid Spotify playlist")
        }
        return PlaylistUrlResult.Valid(id, PlaylistOrigin.SPOTIFY)
    }
}
