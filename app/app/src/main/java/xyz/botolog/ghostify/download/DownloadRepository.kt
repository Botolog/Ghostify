package xyz.botolog.ghostify.download

import kotlinx.coroutines.flow.Flow

/**
 * The subset of the Room `songs` row that the download manager works with.
 *
 * @property id unique song identifier.
 * @property playlistId the playlist this song belongs to.
 * @property spotifyId Spotify track identifier (or YouTube video ID for YouTube-origin tracks).
 * @property title display title of the track.
 * @property artists artist names, comma-separated.
 * @property position 0-based index inside the playlist; source of truth for queue order (T-045).
 * @property status current download status.
 * @property filePath local file path if downloaded, `null` otherwise.
 * @property error error message if the download failed, `null` otherwise.
 * @property ytId resolved YouTube video ID (YouTube-origin tracks have this set).
 */
data class SongRecord(
    val id: String,
    val playlistId: String,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val position: Int,
    val status: DownloadStatus,
    val filePath: String? = null,
    val error: String? = null,
    val ytId: String? = null,
) {
    /**
     * True when this track originated from a YouTube playlist.
     * YouTube-origin rows have both [spotifyId] and [ytId] set to the same
     * YouTube video id; Spotify-origin rows have a 22-char Spotify id in
     * [spotifyId] and a distinct (or absent) YouTube id in [ytId].
     */
    val isYouTubeOrigin: Boolean
        get() = !ytId.isNullOrBlank() && spotifyId == ytId

    /** The URL the Python downloader should receive for this track. */
    val sourceUrl: String
        get() = if (isYouTubeOrigin) {
            "https://www.youtube.com/watch?v=$ytId"
        } else {
            "spotify:track:$spotifyId"
        }
}

/**
 * The subset of the Room `playlists` row the download manager needs.
 *
 * @property id unique playlist identifier.
 * @property status current playlist-level status.
 */
data class PlaylistRecord(
    val id: String,
    val status: PlaylistStatus,
)

/**
 * Contract the download manager needs from the Room repository (data layer).
 * Kept as an interface so the whole queue can be driven by fakes on the JVM and
 * so the real implementation can be provided by the `data/` package later.
 */
interface DownloadRepository {

    /**
     * Returns all songs of a playlist, ordered by playlist position (T-045).
     *
     * @param playlistId the playlist to query.
     */
    suspend fun songsFor(playlistId: String): List<SongRecord>

    /**
     * Returns a live stream of a playlist's songs, ordered by position.
     *
     * @param playlistId the playlist to observe.
     */
    fun observeSongs(playlistId: String): Flow<List<SongRecord>>

    /**
     * Returns a single song by its ID, or `null` if not found.
     *
     * @param songId the song to retrieve.
     */
    suspend fun getSong(songId: String): SongRecord?

    /**
     * Applies a [status] transition to one song. Throws [IllegalStateTransition]
     * for invalid jumps (T-044). [filePath] is only written when non-null so a
     * recovery reset never wipes a previously downloaded path.
     *
     * @param songId the song to update.
     * @param status the target status.
     * @param filePath optional file path to set on transition.
     * @param optional error message to set on transition.
     */
    suspend fun setStatus(
        songId: String,
        status: DownloadStatus,
        filePath: String? = null,
        error: String? = null,
    )

    /**
     * Bulk transition (used to mark a whole queue QUEUED / PENDING).
     *
     * @param songIds the songs to update.
     * @param status the target status.
     */
    suspend fun setStatuses(songIds: Collection<String>, status: DownloadStatus)

    /**
     * Returns the playlist-level status, or `null` if the playlist is unknown.
     *
     * @param playlistId the playlist to query.
     */
    suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus?

    /**
     * Sets the playlist-level status.
     *
     * @param playlistId the playlist to update.
     * @param status the target status.
     */
    suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus)

    /**
     * Returns all known playlist IDs.
     */
    suspend fun allPlaylistIds(): List<String>
}
