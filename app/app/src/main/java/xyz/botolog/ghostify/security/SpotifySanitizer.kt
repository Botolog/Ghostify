package xyz.botolog.ghostify.security

import timber.log.Timber

/**
 * Sanitizes log lines against full Spotify URLs and track metadata (T-170).
 *
 * Two strictness levels, chosen by [LogPolicy] per build type and per message:
 *
 *  - **PROD** (`SanitizeLevel.PROD`): any full `open.spotify.com/...` URL is
 *    reduced to a bare resource reference (`spotify:track`) — the id and every
 *    query parameter are stripped. YouTube URLs (which spotdl/yt-dlp resolve
 *    and which can carry a leaked search query) are reduced to `youtube:video`.
 *  - **DEBUG** (`SanitizeLevel.DEBUG`): the short resource id is kept
 *    (`open.spotify.com/track/<id>`) so an engineer can correlate logs, but
 *    query params and paths are still removed. This is the "beyond debug need"
 *    allowance T-170 permits — never more than that.
 *
 * Track metadata (titles/artists) is never put in PROD logs by the policy:
 * [LogPolicy.debugTrackRef] only builds a compact reference in debug builds,
 * and [LogPolicy.log] with `sensitive = true` downgrades a debug message to
 * PROD strictness. This object is the single place URL stripping lives.
 */
object SpotifySanitizer {

    /** `https://open.spotify.com/<resource>/<id>[?si=..][#..]` (+ legacy `intl-<cc>` prefix). */
    private val OPEN_SPOTIFY = Regex(
        """(?i)\bhttps?://open\.spotify\.com/(?:intl-[a-z0-9]{2}/)?(playlist|album|artist|track|episode|show|user)/[A-Za-z0-9]+(?:[?&#][^\s"'<>]*)?(?=\s|$|["'<>;,)])""",
    )

    /** `https://spotify.app.link/<code>` (Branch link) — id is opaque, drop it. */
    private val SPOTIFY_APP_LINK = Regex(
        """(?i)\bhttps?://spotify\.app\.link/[A-Za-z0-9?&=.\-/_]+""",
    )

    /** `youtube.com/watch?v=<id>`, `youtube.com/shorts/<id>`, `youtube.com/embed/<id>`. */
    private val YOUTUBE_URL = Regex(
        """(?i)\bhttps?://(?:www\.|m\.)?youtube\.com/(?:watch\?(?:[^#\s]*?&)?v=|shorts/|embed/)([A-Za-z0-9_-]{6,})[^\s"'<>]*""",
    )

    /** `youtu.be/<id>` short links. */
    private val YOUTUBE_SHORT = Regex(
        """(?i)\bhttps?://youtu\.be/([A-Za-z0-9_-]{6,})[^\s"'<>]*""",
    )

    /** Bare `spotify:track:<id>` URIs — compact, but the id still identifies a track. */
    private val BARE_SPOTIFY_URI = Regex(
        """\bspotify:(?:playlist|album|artist|track|episode|show|user):[A-Za-z0-9]+""",
    )

    /**
     * Sanitize free-form [text] at [level]. Returns the text with every full
     * Spotify/YouTube URL replaced by a safe reference. Idempotent.
     */
    fun sanitize(text: String, level: SanitizeLevel): String {
        Timber.i("SpotifySanitizer.sanitize: START")
        var out = text
        out = OPEN_SPOTIFY.replace(out) { m ->
            val resource = m.groupValues[1]
            if (level == SanitizeLevel.PROD) "spotify:$resource"
            else {
                val id = m.value.substringAfterLast('/').substringBefore('?').substringBefore('#').trimEnd('/')
                "open.spotify.com/$resource/$id"
            }
        }
        out = SPOTIFY_APP_LINK.replace(out) { if (level == SanitizeLevel.PROD) "spotify:link" else it.value }
        out = YOUTUBE_URL.replace(out) { m ->
            val id = m.groupValues[1]
            if (level == SanitizeLevel.PROD) "youtube:video" else "youtube:video:$id"
        }
        out = YOUTUBE_SHORT.replace(out) { m ->
            val id = m.groupValues[1]
            if (level == SanitizeLevel.PROD) "youtube:video" else "youtube:video:$id"
        }
        out = BARE_SPOTIFY_URI.replace(out) { m ->
            if (level == SanitizeLevel.PROD) "spotify:${m.value.substringAfter(':').substringBefore(':')}" else m.value
        }
        Timber.i("SpotifySanitizer.sanitize: returning $out")
        return out
    }

    /**
     * Compact, URL-free reference to a track for debug logs.
     * Returns null at PROD strictness — metadata must never reach a prod log.
     * Keeps `title - artist [id]`, truncated, never the full URL.
     */
    fun trackRef(trackTitle: String?, artists: String?, spotifyUrl: String?, level: SanitizeLevel): String? {
        Timber.i("SpotifySanitizer.trackRef: START")
        if (level == SanitizeLevel.PROD) return null
        val title = (trackTitle ?: "?").trim().take(40)
        val artist = (artists ?: "?").trim().take(40)
        val id = shortIdOf(spotifyUrl)
        val result = if (id != null) "$title - $artist [$id]" else "$title - $artist"
        Timber.i("SpotifySanitizer.trackRef: returning $result")
        return result
    }

    /** The last path segment of a Spotify URL (the track/playlist id), or null. */
    fun shortIdOf(spotifyUrl: String?): String? {
        Timber.i("SpotifySanitizer.shortIdOf: START")
        if (spotifyUrl == null) return null
        val m = OPEN_SPOTIFY.find(spotifyUrl) ?: return null
        val result = m.value
            .substringAfterLast('/')
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .ifEmpty { null }
        Timber.i("SpotifySanitizer.shortIdOf: returning $result")
        return result
    }
}
