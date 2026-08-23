package xyz.botolog.ghostify.data.model

/**
 * Where a playlist was discovered / fetched from.
 *
 * Drives UI tinting (Spotify = green, YouTube = red) and download routing
 * (Spotify tracks resolve via spotdl search; YouTube tracks download directly
 * from the video URL stored as `spotify_id`).
 */
enum class PlaylistOrigin {
    /** Playlist sourced from the Spotify Web API. */
    SPOTIFY,

    /** Playlist sourced from a YouTube channel or playlist URL. */
    YOUTUBE;

    companion object {

        private const val WIRE_YOUTUBE = "YOUTUBE"

        /**
         * Parses the wire representation (e.g. from the database) into
         * [PlaylistOrigin]. Defaults to [SPOTIFY] for unknown values.
         */
        fun fromWire(value: String?): PlaylistOrigin = when (value) {
            WIRE_YOUTUBE -> YOUTUBE
            else -> SPOTIFY
        }
    }
}
