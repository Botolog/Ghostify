package com.ghostify.viewmodel

import kotlinx.coroutines.flow.Flow

/**
 * Contracts the ViewModels depend on. Every dependency is an interface so the
 * whole state layer can be exercised with fakes on the JVM; the app wires the
 * real implementations (Room repository, DownloadManager, SyncUseCase,
 * PlaylistMetadataBridge, SpotifyUrlParser, Media3 controller) via Hilt.
 */

/** Persistence for playlists + tracks (Room `playlists` / `songs`). */
interface PlaylistRepository {

    /** All saved playlists. Ordering is the VM's job (newest first). */
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    /** Live view of one playlist; emits `null` once it is deleted. */
    fun observePlaylist(playlistId: String): Flow<PlaylistSummary?>

    /** Live view of a playlist's tracks. */
    fun observeTracks(playlistId: String): Flow<List<Track>>

    suspend fun playlist(playlistId: String): PlaylistSummary?

    suspend fun tracksFor(playlistId: String): List<Track>

    /** Saves a playlist + its tracks atomically; returns the playlist id. */
    suspend fun save(playlist: PlaylistSummary, tracks: List<Track>): String

    /** Deletes a playlist row (+ cascade tracks). Files are the deleter's job. */
    suspend fun delete(playlistId: String)
}

/** Per-playlist download orchestration (DownloadManager, PROJECT.md §6.1). */
interface DownloadController {

    /**
     * Starts a download run. Returns false if a run is already active for the
     * playlist (single-flight, T-054) — callers treat that as a benign no-op.
     */
    fun downloadAll(playlistId: String): Boolean

    fun retry(playlistId: String): Boolean

    fun cancel(playlistId: String)

    fun isRunning(playlistId: String): Boolean

    /**
     * Live progress; `null` means no run / no progress for this playlist.
     */
    fun observeProgress(playlistId: String): Flow<DownloadProgress?>
}

/** Re-sync / redownload diff (SyncUseCase, PROJECT.md §6.2). */
interface PlaylistSyncer {
    suspend fun sync(playlistId: String): SyncOutcome
}

sealed class SyncOutcome {
    data class Success(val added: Int, val removed: Int, val requeued: Int) : SyncOutcome()
    data class Failure(val message: String) : SyncOutcome()
}

/** URL → playlist-id validation (SpotifyUrlParser). */
interface PlaylistUrlParser {
    fun parse(input: String): UrlParseResult
}

sealed class UrlParseResult {
    data class Success(val spotifyId: String) : UrlParseResult()
    data class Rejected(val message: String) : UrlParseResult()
}

/** Spotify metadata fetch (PlaylistMetadataBridge) — metadata only, no download. */
interface PlaylistFetcher {
    suspend fun fetch(spotifyId: String): PlaylistFetchResult
}

sealed class PlaylistFetchResult {
    data class Success(val metadata: FetchedPlaylist) : PlaylistFetchResult()
    data class Failure(val message: String, val retryHint: String? = null) : PlaylistFetchResult()
}

data class FetchedPlaylist(
    val name: String,
    val owner: String,
    val coverUrl: String?,
    val trackCount: Int,
    val tracks: List<FetchedTrack>,
)

data class FetchedTrack(
    val position: Int,
    val spotifyId: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
)

/** Local MP3 store (file-management component). All ops are defensive. */
interface LocalFileStore {
    fun exists(path: String?): Boolean

    /** Deletes one file; deleting a missing file is a no-op. */
    suspend fun delete(path: String)

    /** Deletes every file owned by a playlist (incl. partial downloads). */
    suspend fun deletePlaylistFiles(playlistId: String)

    suspend fun listFiles(): List<String>
}

/** Media3 playback controller (player-core component). */
interface PlayerController {

    /** Live playback state; never throws — errors ride in [PlayerPlaybackState.error]. */
    fun observeState(): Flow<PlayerPlaybackState>

    /**
     * Builds the queue from the given (already DOWNLOADED) tracks and starts
     * playback. A filtered-empty queue yields [PlayerLoadResult.NothingToPlay].
     */
    suspend fun playPlaylist(playlistId: String, tracks: List<Track>): PlayerLoadResult

    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(enabled: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun setVolume(volume: Float)
}

enum class RepeatMode { OFF, ALL, ONE }

/** Snapshot of player internals, ready to be mapped onto UI state. */
data class PlayerPlaybackState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val currentTrackId: String? = null,
    val currentTrackTitle: String? = null,
    val currentTrackArtist: String? = null,
    val queueSize: Int = 0,
    val nothingToPlay: Boolean = false,
    val error: String? = null,
)

sealed class PlayerLoadResult {
    data object Ok : PlayerLoadResult()
    data object NothingToPlay : PlayerLoadResult()
    data class Failed(val message: String) : PlayerLoadResult()
}
