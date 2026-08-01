package com.ghostify.ui.util

import java.net.URI

/**
 * First-line URL validation for the Add-playlist dialog.
 *
 * This is intentionally a thin, defensive UI guard (paste a URL -> immediate feedback)
 * that only extracts a Spotify playlist id. The authoritative parser lives in the
 * `url-parser` component; this mirrors its rules for the common cases so the dialog
 * never performs a network call on obvious garbage.
 *
 * Pure Kotlin (JVM testable).
 */
sealed interface PlaylistUrlResult {
    data class Valid(val playlistId: String) : PlaylistUrlResult
    data class Invalid(val message: String) : PlaylistUrlResult
}

object PlaylistUrlValidator {

    fun validate(input: String?): PlaylistUrlResult {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) {
            return PlaylistUrlResult.Invalid("Enter a Spotify playlist link")
        }

        // URI form: spotify:playlist:<id>
        if (text.startsWith("spotify:playlist:")) {
            return extractId(text.removePrefix("spotify:playlist:"))
        }

        // Full/open URL forms. Accept either open.spotify.com/playlist/<id> or
        // spotify.link short links (resolution happens in the fetch layer).
        val uri = runCatching { URI(text) }.getOrNull()
        val host = uri?.host?.lowercase() ?: return PlaylistUrlResult.Invalid("That doesn't look like a Spotify link")

        if (!host.endsWith("spotify.com")) {
            return PlaylistUrlResult.Invalid("That doesn't look like a Spotify link")
        }

        val path = uri.path ?: ""
        val segments = path.split("/").filter { it.isNotBlank() }

        if (segments.isEmpty()) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        if (segments[0] != "playlist") {
            return PlaylistUrlResult.Invalid("Not a playlist link — use a Spotify playlist URL")
        }
        if (segments.size < 2) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        return extractId(segments[1])
    }

    private fun extractId(rawId: String): PlaylistUrlResult {
        val id = rawId.trim()
        if (id.isEmpty() || id.any { !it.isLetterOrDigit() }) {
            return PlaylistUrlResult.Invalid("Malformed link — no playlist id found")
        }
        if (id.length < 15) {
            return PlaylistUrlResult.Invalid("That doesn't look like a valid Spotify playlist")
        }
        return PlaylistUrlResult.Valid(id)
    }
}
