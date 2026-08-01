package com.ghostify.download

import kotlinx.coroutines.flow.Flow

/** The subset of the Room `songs` row that the download manager works with. */
data class SongRecord(
    val id: String,
    val playlistId: String,
    val spotifyId: String,
    val title: String,
    val artists: String,
    /** 0-based index inside the playlist; source of truth for queue order (T-045). */
    val position: Int,
    val status: DownloadStatus,
    val filePath: String? = null,
    val error: String? = null,
)

/** The subset of the Room `playlists` row the download manager needs. */
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

    /** All songs of a playlist, ordered by playlist position (T-045). */
    suspend fun songsFor(playlistId: String): List<SongRecord>

    /** Live stream of a playlist's songs, ordered by position. */
    fun observeSongs(playlistId: String): Flow<List<SongRecord>>

    suspend fun getSong(songId: String): SongRecord?

    /**
     * Applies a [status] transition to one song. Throws [IllegalStateTransition]
     * for invalid jumps (T-044). [filePath] is only written when non-null so a
     * recovery reset never wipes a previously downloaded path.
     */
    suspend fun setStatus(
        songId: String,
        status: DownloadStatus,
        filePath: String? = null,
        error: String? = null,
    )

    /** Bulk transition (used to mark a whole queue QUEUED / PENDING). */
    suspend fun setStatuses(songIds: Collection<String>, status: DownloadStatus)

    suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus?

    suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus)

    suspend fun allPlaylistIds(): List<String>
}
