package xyz.botolog.ghostify.data.model

/**
 * Where a playlist was discovered / fetched from.
 *
 * Drives UI tinting (Spotify = green, YouTube = red) and download routing
 * (Spotify tracks resolve via spotdl search; YouTube tracks download directly
 * from the video URL stored as [PlaylistEntity.spotifyId]).
 */
enum class PlaylistOrigin {
    SPOTIFY,
    YOUTUBE,
    ;

    companion object {
        fun fromWire(value: String?): PlaylistOrigin = when (value) {
            "YOUTUBE" -> YOUTUBE
            else -> SPOTIFY
        }
    }
}
