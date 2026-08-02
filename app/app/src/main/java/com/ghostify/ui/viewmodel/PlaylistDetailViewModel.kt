package com.ghostify.ui.viewmodel

import com.ghostify.data.model.SongStatus
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SongRepository
import com.ghostify.download.DownloadManager
import com.ghostify.download.DownloadRunState
import com.ghostify.player.PlayerController
import com.ghostify.sync.SyncException
import com.ghostify.sync.SyncUseCase
import com.ghostify.ui.contract.PlaylistDetailContract
import com.ghostify.ui.contract.PlaylistDetailContract.PlaylistDetailUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * Backs the Playlist detail screen: playlist metadata, the ordered track list
 * merged with live download progress, and the top actions (play / download /
 * re-sync / retry).
 *
 * Concurrency safety: "download all" is single-flight inside the [DownloadManager]
 * (a duplicate press is a benign no-op); "re-sync" is guarded by an in-VM flag and
 * is safe to run while a download is active (the SyncUseCase only enqueues PENDING
 * tracks); "play" only builds a queue from DOWNLOADED tracks.
 */
class PlaylistDetailViewModel(
    private val playlistId: String,
    private val repo: PlaylistRepository,
    private val songRepo: SongRepository,
    private val downloads: DownloadManager,
    private val syncer: SyncUseCase,
    private val player: PlayerController,
) : ContractViewModel(), PlaylistDetailContract {

    private val _state = MutableStateFlow(PlaylistDetailUiState(playlistId = playlistId))
    override val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    private val syncing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    init {
        launch {
            combine(
                repo.observePlaylist(playlistId),
                repo.observeSongs(playlistId),
                downloads.observeProgress(playlistId),
                syncing,
                error,
            ) { playlist, songs, progress, isSyncing, loadError ->
                mapToUi(playlist, songs, progress, isSyncing, loadError)
            }
                .catch { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "Could not load the playlist.")
                    }
                }
                .collect { _state.value = it }
        }
    }

    override fun downloadAll() {
        downloads.downloadAll(playlistId)
    }

    override fun sync() {
        if (syncing.value) return
        launch {
            syncing.value = true
            try {
                val result = syncer.syncPlaylist(playlistId)
                // New / re-queued tracks need downloading; a running run makes
                // this a no-op (single-flight inside the manager).
                if (result.added > 0 || result.requeued > 0) {
                    downloads.downloadAll(playlistId)
                }
                error.value = null
            } catch (e: SyncException) {
                error.value = e.message ?: "Sync failed."
            } catch (e: Exception) {
                error.value = e.message ?: "Sync failed."
            } finally {
                syncing.value = false
            }
        }
    }

    override fun playAll() {
        launch {
            val songs = songRepo.getSongs(playlistId)
                .filter { it.status == SongStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }
                .sortedBy { it.position }
                .map { it.toPlayerSong() }
            player.playPlaylist(songs)
        }
    }

    override fun retryTrack(trackId: String) {
        launch {
            val song = songRepo.getSong(trackId) ?: return@launch
            if (song.playlistId != playlistId) return@launch
            songRepo.setStatus(listOf(trackId), SongStatus.PENDING)
            downloads.downloadAll(playlistId)
        }
    }

    private fun mapToUi(
        playlist: com.ghostify.data.db.entity.PlaylistEntity?,
        songs: List<com.ghostify.data.db.entity.SongEntity>,
        progress: com.ghostify.download.DownloadProgress?,
        isSyncing: Boolean,
        loadError: String?,
    ): PlaylistDetailUiState {
        if (playlist == null) {
            return PlaylistDetailUiState(
                playlistId = playlistId,
                loading = false,
                error = loadError ?: "Playlist not found.",
            )
        }
        val sorted = songs.sortedBy { it.position }
        val downloaded = sorted.count { it.status == SongStatus.DOWNLOADED }
        val running = progress?.state == DownloadRunState.RUNNING
        return PlaylistDetailUiState(
            playlistId = playlistId,
            name = playlist.name,
            coverUrl = playlist.coverUrl,
            tracks = sorted.map { it.toTrackUi() },
            downloadedCount = downloaded,
            trackCount = playlist.trackCount,
            loading = false,
            isDownloadingAll = running,
            downloadAllProgress = if (running) progress?.overallPercent?.toInt() else null,
            isSyncing = isSyncing,
            error = loadError,
        )
    }
}
