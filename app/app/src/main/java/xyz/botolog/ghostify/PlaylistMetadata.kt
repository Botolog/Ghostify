package xyz.botolog.ghostify.python

import xyz.botolog.ghostify.data.model.PlaylistOrigin

/**
 * Full metadata for a playlist (Spotify or YouTube), as returned by the
 * Python bridge.
 *
 * Mirrors the Room `playlists` + `songs` tables (PROJECT.md §4): the app saves
 * [tracks] in order and uses `spotifyId` as the authoritative track identity.
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
            val rawTracks = map["tracks"] as? List<*> ?: emptyList<Any?>()
            val tracks = ArrayList<PlaylistTrack>(rawTracks.size)
            for (entry in rawTracks) {
                val trackMap = entry as? Map<*, *> ?: continue
                val track = PlaylistTrack.fromMap(trackMap) ?: continue
                tracks.add(track)
            }
            return PlaylistMetadata(
                name = map["name"] as? String ?: "",
                owner = map["owner"] as? String ?: "",
                coverUrl = map["cover_url"] as? String,
                description = map["description"] as? String,
                trackCount = (map["track_count"] as? Number)?.toInt() ?: tracks.size,
                tracks = tracks,
                origin = PlaylistOrigin.fromWire(map["origin"] as? String),
            )
        }
    }
}

/**
 * A single track in playlist order.
 *
 * @param position 0-based index in the playlist. Duplicates are preserved:
 *   a repeated `spotifyId` appears once per occurrence, each with its own
 *   position (T-013).
 * @param artists artists joined with ", " — matches the Room `songs.artists`
 *   column ("Artist A, Artist B").
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
        fun fromMap(map: Map<*, *>): PlaylistTrack? {
            val spotifyId = map["spotify_id"] as? String
                ?: return null // authoritative identity must be present
            return PlaylistTrack(
                position = (map["position"] as? Number)?.toInt() ?: 0,
                spotifyId = spotifyId,
                title = map["title"] as? String ?: "",
                artists = map["artists"] as? String ?: "",
                album = map["album"] as? String ?: "",
                durationMs = (map["duration_ms"] as? Number)?.toLong() ?: 0L,
                coverUrl = map["cover_url"] as? String,
                ytId = map["yt_id"] as? String
            )
        }
    }
}
