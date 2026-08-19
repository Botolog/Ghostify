package com.ghostify.ui.viewmodel

import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.download.DownloadManager
import com.ghostify.download.DownloadProgress
import com.ghostify.download.DownloadRunState
import com.ghostify.download.DownloadStatus
import com.ghostify.ui.contract.LibraryContract
import com.ghostify.ui.contract.LibraryContract.LibraryUiState
import com.ghostify.ui.model.PlaylistUi
import com.ghostify.ui.model.PlaylistStatus as UiPlaylistStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * Backs the Library screen.
 *
 * Rows come from [PlaylistRepository.observePlaylists] (newest first); the live
 * download badges (downloaded count / progress percent / status) are merged from
 * each playlist's [DownloadManager.observeProgress] stream so the library stays
 * correct before, during and after a download run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: PlaylistRepository,
    private val downloads: DownloadManager,
) : ContractViewModel(), LibraryContract {

    private val _state = MutableStateFlow(LibraryUiState())
    override val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        Timber.i("LibraryViewModel: init")
        launch {
            repo.observePlaylists()
                .flatMapLatest { playlists ->
                    if (playlists.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(playlists.map { downloads.observeProgress(it.id) }) { progress ->
                            playlists.mapIndexed { i, playlist ->
                                mapPlaylist(playlist, progress[i])
                            }
                        }
                    }
                }
                .catch { e ->
                    Timber.e(e, "LibraryViewModel: playlist stream FAILED")
                    _state.update { it.copy(loading = false) }
                }
                .collect { rows ->
                    _state.update { it.copy(playlists = rows, loading = false) }
                }
        }
    }

    override fun onAddClick() {
        Timber.i("LibraryViewModel.onAddClick: START")
        _state.update { it.copy(isAddDialogOpen = true) }
    }

    override fun onOpenSettings() {
        Timber.i("LibraryViewModel.onOpenSettings: START")
        // Navigation to Settings is owned by the screen's callback; nothing to do.
    }

    override fun reorderPlaylist(playlistId: String, newSortOrder: Int) {
        Timber.i("LibraryViewModel.reorderPlaylist: START $playlistId -> $newSortOrder")
        launch { repo.reorderPlaylist(playlistId, newSortOrder) }
    }

    override fun deletePlaylist(playlistId: String) {
        Timber.i("LibraryViewModel.deletePlaylist: START $playlistId")
        launch { repo.deletePlaylist(playlistId) }
    }

    override fun shutdownApp() {
        Timber.i("LibraryViewModel.shutdownApp: START")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Non-contract hook used by the Add-dialog wiring to close itself. */
    fun closeAddDialog() {
        Timber.i("LibraryViewModel.closeAddDialog: START")
        _state.update { it.copy(isAddDialogOpen = false) }
    }

    private fun mapPlaylist(p: PlaylistEntity, progress: DownloadProgress): PlaylistUi {
        val perSong = progress.perSong
        val downloaded = perSong.values.count { it.status == DownloadStatus.DOWNLOADED }
        val running = progress.state == DownloadRunState.RUNNING
        return PlaylistUi(
            id = p.id,
            name = p.name,
            owner = p.owner.orEmpty(),
            coverUrl = p.coverUrl,
            trackCount = p.trackCount,
            downloadedCount = downloaded,
            status = if (running) UiPlaylistStatus.DOWNLOADING else p.status.toUi(),
            origin = p.origin,
            progressPercent = if (running) progress.overallPercent.toInt() else null,
            lastSyncedAt = p.lastSyncedAt,
        )
    }
}
