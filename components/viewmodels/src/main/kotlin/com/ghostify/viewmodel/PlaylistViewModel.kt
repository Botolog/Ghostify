package com.ghostify.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Orchestrates the delete-a-playlist flow.
 *
 * It is a small dedicated use case (not logic inline in the VM) so that the
 * exact "cancel + clean + drop rows" ordering is a single, unit-testable
 * unit. Order matters for T-117 ("no orphan files"):
 *
 *  1. **Cancel the active download run first.** The DownloadManager's cancel
 *     is cooperative and de-duplicated; a cancelled run leaves no zombie
 *     worker (T-049).
 *  2. **Delete every file the DB knows about.** Files for already-deleted
 *     rows or half-written partial downloads are covered by the sweep below.
 *  3. **Sweep the playlist's storage folder.** Because files are the
 *     by-product of a download, a mid-flight run could have written a file
 *     after step 2's snapshot; deleting the playlist's folder catches it, so
 *     nothing is left orphaned on disk.
 *  4. **Delete the rows last.** Rows are the source of truth (PROJECT.md
 *     §4); once they are gone no screen can resurrect the playlist.
 *
 * Every file operation is defensive (missing paths are no-ops), so the whole
 * flow never throws for data-race reasons — concurrent play/sync/delete stay
 * safe (T-116).
 */
class DeletePlaylistUseCase(
    private val downloads: DownloadController,
    private val repository: PlaylistRepository,
    private val files: LocalFileStore,
) {

    suspend operator fun invoke(playlistId: String) {
        downloads.cancel(playlistId)

        val tracked = repository.tracksFor(playlistId)
        tracked.mapNotNull { it.filePath }.distinct().forEach { path -> files.delete(path) }

        files.deletePlaylistFiles(playlistId)

        repository.delete(playlistId)
    }
}

/**
 * Backs the Playlist detail screen: playlist metadata, the ordered track list
 * merged with live download progress, and the top actions (play / download /
 * re-sync / delete).
 *
 * ## Concurrency safety (T-116)
 *  - **downloadAll** is single-flight inside the DownloadManager: a duplicate
 *    press returns `false` and is a benign no-op, never a crash.
 *  - **sync** is guarded by an in-VM `syncing` flag (one re-sync at a time)
 *    and is safe to run *while* a download is active: the SyncUseCase never
 *    touches QUEUED/DOWNLOADING rows and enqueues only PENDING ones.
 *  - **delete** is guarded by `deleting` and delegated to [DeletePlaylistUseCase],
 *    which cancels the run before touching disk.
 *  - **play** only builds a queue from DOWNLOADED tracks; an empty result is a
 *    `NothingToPlay` outcome, never a crash.
 *  - Every action runs in [viewModelScope]; exceptions are converted to
 *    [PlaylistUiState.Error], so no illegal-state exception can escape to the UI.
 */
class PlaylistViewModel(
    private val playlistId: String,
    private val repository: PlaylistRepository,
    private val downloads: DownloadController,
    private val syncer: PlaylistSyncer,
    private val deleteUseCase: DeletePlaylistUseCase,
    private val player: PlayerController,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private val syncing = MutableStateFlow(false)
    private val deleting = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(
                repository.observePlaylist(playlistId),
                repository.observeTracks(playlistId),
                downloads.observeProgress(playlistId),
                syncing,
                deleting,
            ) { playlist, tracks, progress, isSyncing, isDeleting ->
                mapToUi(playlist, tracks, progress, isSyncing, isDeleting)
            }
                .catch { e -> emit(PlaylistUiState.Error(e.message ?: "Could not load the playlist.")) }
                .collect { _uiState.value = it }
        }
    }

    private fun mapToUi(
        playlist: PlaylistSummary?,
        tracks: List<Track>,
        progress: DownloadProgress?,
        isSyncing: Boolean,
        isDeleting: Boolean,
    ): PlaylistUiState {
        if (playlist == null) return PlaylistUiState.NotFound
        if (tracks.isEmpty()) return PlaylistUiState.Empty
        val perSong = progress?.perSong.orEmpty()
        val trackUi = tracks.sortedBy { it.position }.map { t ->
            val live = perSong[t.id]
            TrackUi(
                id = t.id,
                title = t.title,
                artists = t.artists,
                durationMs = t.durationMs,
                status = live?.status ?: t.status,
                fraction = live?.fraction ?: if (t.status == SongStatus.DOWNLOADED) 1f else 0f,
                position = t.position,
            )
        }
        return PlaylistUiState.Content(playlist, trackUi, progress, isSyncing, isDeleting)
    }

    /** Starts "download all" (PENDING + FAILED). Duplicate press = no-op (T-054/T-116). */
    fun downloadAll() {
        try {
            downloads.downloadAll(playlistId)
        } catch (e: Exception) {
            _uiState.update { if (it is PlaylistUiState.Content) PlaylistUiState.Error(e.message ?: "Download failed.") else it }
        }
    }

    fun cancelDownloads() {
        try {
            downloads.cancel(playlistId)
        } catch (_: Exception) {
            // Cancellation is best-effort; never crash the screen (T-116).
        }
    }

    /** Re-sync / redownload diff. Safe to run while a download is active. */
    fun sync() {
        if (syncing.value) return
        viewModelScope.launch {
            syncing.value = true
            try {
                when (val outcome = syncer.sync(playlistId)) {
                    is SyncOutcome.Success -> {
                        // New / re-queued tracks need downloading; a running run
                        // makes this a no-op (single-flight).
                        if (outcome.added > 0 || outcome.requeued > 0) downloads.downloadAll(playlistId)
                    }
                    is SyncOutcome.Failure -> _uiState.update { PlaylistUiState.Error(outcome.message) }
                }
            } catch (e: Exception) {
                _uiState.update { PlaylistUiState.Error(e.message ?: "Sync failed.") }
            } finally {
                syncing.value = false
            }
        }
    }

    /** Plays only DOWNLOADED tracks, in playlist order. Empty queue → NothingToPlay. */
    fun play() {
        viewModelScope.launch {
            try {
                val downloaded = repository.tracksFor(playlistId)
                    .filter { it.status == SongStatus.DOWNLOADED }
                    .sortedBy { it.position }
                player.playPlaylist(playlistId, downloaded)
            } catch (e: Exception) {
                _uiState.update { PlaylistUiState.Error(e.message ?: "Could not start playback.") }
            }
        }
    }

    /** Deletes the playlist + files, cancelling any active download first (T-117). */
    fun delete() {
        if (deleting.value) return
        viewModelScope.launch {
            deleting.value = true
            try {
                deleteUseCase(playlistId)
            } catch (e: Exception) {
                _uiState.update { PlaylistUiState.Error(e.message ?: "Delete failed.") }
            } finally {
                deleting.value = false
            }
        }
    }
}
