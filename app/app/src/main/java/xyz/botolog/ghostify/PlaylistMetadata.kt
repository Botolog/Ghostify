package xyz.botolog.ghostify.python

import xyz.botolog.ghostify.data.model.PlaylistOrigin

/**
 * Full metadata for a playlist (Spotify or YouTube), as returned by the
 * Python bridge.
 *
 * Mirrors the Room `playlists` + `songs` tables (PROJECT.md §4): the app saves
 * [tracks] in order and uses `spotifyId` as the authoritative track identity.
 *
 * @param name the playlist name.
 * @param owner the playlist owner's display name.
 * @param coverUrl URL to the playlist's cover image, or null if unavailable.
 * @param description optional playlist description.
 * @param trackCount total number of tracks in the playlist.
 * @param tracks ordered list of tracks in the playlist.
 * @param origin where the playlist originated from (Spotify or YouTube).
 */
data class PlaylistMetadata(
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val description: String?,
    val trackCount: Int,
    val tracks: List<PlaylistTrack>,
    val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
) {
    companion object {
        /**
         * Parses the wire dict returned by `ghostify_dl.fetch_playlist` or
         * `ghostify_dl.fetch_playlist_youtube`.
         *
         * The map is the deep-converted Java form of the Python result dict
         * (`Map<String, Any?>` with String/Number/Boolean/List/Map leaves).
         * Parsing is defensive: every field falls back to a safe default and
         * malformed track entries are skipped rather than crashing.
         */
        fun fromMap(map: Map<String, Any?>): PlaylistMetadata {
            val rawTracks = map[KEY_TRACKS] as? List<*> ?: emptyList<Any?>()
            val tracks = parseTracks(rawTracks)
            return PlaylistMetadata(
                name = map[KEY_NAME] as? String ?: "",
                owner = map[KEY_OWNER] as? String ?: "",
                coverUrl = map[KEY_COVER_URL] as? String,
                description = map[KEY_DESCRIPTION] as? String,
                trackCount = (map[KEY_TRACK_COUNT] as? Number)?.toInt() ?: tracks.size,
                tracks = tracks,
                origin = PlaylistOrigin.fromWire(map[KEY_ORIGIN] as? String),
            )
        }

        private fun parseTracks(rawTracks: List<*>): List<PlaylistTrack> {
            val result = ArrayList<PlaylistTrack>(rawTracks.size)
            for (entry in rawTracks) {
                val trackMap = entry as? Map<*, *> ?: continue
                val track = PlaylistTrack.fromMap(trackMap) ?: continue
                result.add(track)
            }
            return result
        }

        private const val KEY_TRACKS = "tracks"
        private const val KEY_NAME = "name"
        private const val KEY_OWNER = "owner"
        private const val KEY_COVER_URL = "cover_url"
        private const val KEY_DESCRIPTION = "description"
        private const val KEY_TRACK_COUNT = "track_count"
        private const val KEY_ORIGIN = "origin"
    }
}

/**
 * A single track in playlist order.
 *
 * @param position 0-based index in the playlist. Duplicates are preserved:
 *   a repeated `spotifyId` appears once per occurrence, each with its own
 *   position (T-013).
 * @param spotifyId the track's Spotify ID (authoritative identity).
 * @param title the track title.
 * @param artists artists joined with ", " — matches the Room `songs.artists`
 *   column ("Artist A, Artist B").
 * @param album the album name.
 * @param durationMs track duration in milliseconds.
 * @param coverUrl URL to the track's cover image, or null if unavailable.
 * @param ytId resolved YouTube id, or null when the track has no resolvable
 *   match (T-020) or when resolution is disabled.
 */
data class PlaylistTrack(
    val position: Int,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val ytId: String?
) {
    companion object {
        private const val KEY_SPOTIFY_ID = "spotify_id"
        private const val KEY_POSITION = "position"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTISTS = "artists"
        private const val KEY_ALBUM = "album"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_COVER_URL = "cover_url"
        private const val KEY_YT_ID = "yt_id"

        fun fromMap(map: Map<*, *>): PlaylistTrack? {
            val spotifyId = map[KEY_SPOTIFY_ID] as? String
                ?: return null // authoritative identity must be present
            return PlaylistTrack(
                position = (map[KEY_POSITION] as? Number)?.toInt() ?: 0,
                spotifyId = spotifyId,
                title = map[KEY_TITLE] as? String ?: "",
                artists = map[KEY_ARTISTS] as? String ?: "",
                album = map[KEY_ALBUM] as? String ?: "",
                durationMs = (map[KEY_DURATION_MS] as? Number)?.toLong() ?: 0L,
                coverUrl = map[KEY_COVER_URL] as? String,
                ytId = map[KEY_YT_ID] as? String,
            )
        }
    }
}
