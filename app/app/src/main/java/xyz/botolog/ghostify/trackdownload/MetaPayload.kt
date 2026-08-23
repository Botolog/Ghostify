package xyz.botolog.ghostify.trackdownload

import xyz.botolog.ghostify.download.SongRecord

/**
 * Separator used across the app for the Room `songs.artists` column
 * ("Artist A, Artist B"); see [xyz.botolog.ghostify.python.PlaylistTrack].
 */
internal const val ARTIST_JOIN_SEPARATOR = ", "

/**
 * Builds the flat `meta` payload handed to the Python downloader so it can skip
 * the per-track Spotify re-fetch when the metadata is already stored locally.
 *
 * Wire contract (keys are fixed — do not rename):
 *  - `"name"`: track title.
 *  - `"artists"`: [java.util.ArrayList] of individual artist names (NOT joined);
 *    split back from the Room column using [ARTIST_JOIN_SEPARATOR].
 *  - `"album"`: album name, `""` when unknown.
 *  - `"duration_sec"`: duration in seconds ([Double]); `0.0` when unknown.
 *  - `"image_url"`: artwork URL, or `null` (Chaquopy maps to Python `None`).
 *
 * Pure Kotlin — no Android, no I/O — so it stays JVM-unit-testable.
 *
 * @param song the stored song whose metadata should be forwarded.
 * @return an ordered map with exactly the five contract keys.
 */
internal fun buildMetaPayload(song: SongRecord): Map<String, Any?> = linkedMapOf(
    KEY_NAME to song.title,
    KEY_ARTISTS to ArrayList(
        song.artists.split(ARTIST_JOIN_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    ),
    KEY_ALBUM to (song.album?.takeIf { it.isNotBlank() } ?: ""),
    KEY_DURATION_SEC to song.durationMs / MILLIS_PER_SECOND,
    KEY_IMAGE_URL to song.coverUrl?.takeIf { it.isNotBlank() },
)

private const val KEY_NAME = "name"
private const val KEY_ARTISTS = "artists"
private const val KEY_ALBUM = "album"
private const val KEY_DURATION_SEC = "duration_sec"
private const val KEY_IMAGE_URL = "image_url"

/** Milliseconds per second, for the ms → s wire conversion. */
private const val MILLIS_PER_SECOND = 1000.0
