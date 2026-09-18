package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.model.SongStatus as DataSongStatus
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.download.DownloadRunState
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.sync.SyncException
import xyz.botolog.ghostify.sync.SyncUseCase
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract.PlaylistDetailUiState
import xyz.botolog.ghostify.ui.model.SongStatus as UiSongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 *
 * @property playlistId the database id of the playlist to display.
 * @property repo playlist persistence layer.
 * @property songRepo song persistence layer.
 * @property downloads download orchestration layer.
 * @property syncer re-sync use case for playlists.
 * @property player media playback controller.
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

    private val _deleted = MutableSharedFlow<Unit>()
    override val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    /** Tracks whether a sync operation is currently in progress. */
    private val syncing = MutableStateFlow(false)

    /** Holds the most recent load or sync error message. */
    private val error = MutableStateFlow<String?>(null)

    /** Cached tracks list — only rebuilt when the underlying songs change. */
    private var cachedTracks: List<TrackUi> = emptyList()
    private var lastSongsHash: Int = 0
    private var cachedTotalDurationMs: Long = 0L
    private var cachedTotalFileSizeBytes: Long = 0L
    private val fileSizeCache = mutableMapOf<String, Long>()

    init {
        Timber.i("PlaylistDetailViewModel: init")
        launch {
            combine(
                repo.observePlaylist(playlistId),
                repo.observeSongs(playlistId),
                downloads.observeProgress(playlistId),
                syncing,
                error,
            ) { playlist, songs, progress, isSyncing, loadError ->
                val songsHash = songs.hashCode()
                if (songsHash != lastSongsHash) {
                    lastSongsHash = songsHash
                    cachedTracks = songs.sortedBy { it.position }.map { it.toTrackUi() }
                    cachedTotalDurationMs = songs.sumOf { it.durationMs.toLong() }
                    cachedTotalFileSizeBytes = withContext(Dispatchers.IO) {
                        songs.sumOf { song ->
                            song.fileSize ?: fileSizeCache.getOrPut(song.id) {
                                song.filePath?.let { path ->
                                    try {
                                        val file = File(path)
                                        if (file.exists()) file.length() else 0L
                                    } catch (_: Exception) {
                                        0L
                                    }
                                } ?: 0L
                            }
                        }
                    }
                }
                mapToUi(playlist, progress, isSyncing, loadError)
            }
                .distinctUntilChanged()
                .catch { e ->
                    Timber.e(e, "PlaylistDetailViewModel: playlist stream FAILED")
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message ?: PLAYLIST_LOAD_ERROR,
                        )
                    }
                }
                .collect { _state.value = it }
        }
    }

    override fun downloadAll() {
        Timber.i("PlaylistDetailViewModel.downloadAll: START")
        downloads.downloadAll(playlistId)
    }

    override fun sync() {
        Timber.i("PlaylistDetailViewModel.sync: START")
        if (syncing.value) return
        launch {
            syncing.value = true
            try {
                val result = syncer.syncPlaylist(playlistId)
                if (result.added > 0 || result.requeued > 0) {
                    downloads.downloadAll(playlistId)
                }
                error.value = null
            } catch (e: SyncException) {
                Timber.e(e, "PlaylistDetailViewModel.sync: FAILED")
                error.value = e.message ?: SYNC_FAILED_ERROR
            } catch (e: Exception) {
                Timber.e(e, "PlaylistDetailViewModel.sync: FAILED")
                error.value = e.message ?: SYNC_FAILED_ERROR
            } finally {
                syncing.value = false
            }
        }
    }

    override fun playAll() {
        Timber.i("PlaylistDetailViewModel.playAll: START")
        launch {
            val songs = getDownloadedSongs()
            player.playPlaylist(songs)
        }
    }

    override fun retryTrack(trackId: String) {
        Timber.i("PlaylistDetailViewModel.retryTrack: START")
        launch {
            val song = songRepo.getSong(trackId) ?: return@launch
            if (song.playlistId != playlistId) return@launch
            songRepo.setStatus(listOf(trackId), DataSongStatus.PENDING)
            downloads.downloadAll(playlistId)
        }
    }

    override fun playFromSong(songId: String) {
        Timber.i("PlaylistDetailViewModel.playFromSong: START $songId")
        launch {
            val songs = getDownloadedSongs()
            player.playPlaylist(songs, startSongId = songId)
        }
    }

    override fun downloadSong(trackId: String) {
        Timber.i("PlaylistDetailViewModel.downloadSong: START $trackId")
        launch {
            songRepo.setStatus(listOf(trackId), DataSongStatus.PENDING)
            downloads.downloadAll(playlistId)
        }
    }

    override fun reorderSong(songId: String, newPosition: Int) {
        Timber.i("PlaylistDetailViewModel.reorderSong: START $songId -> $newPosition")
        launch { repo.reorderSong(songId, newPosition) }
    }

    override fun deleteSong(trackId: String) {
        Timber.i("PlaylistDetailViewModel.deleteSong: START $trackId")
        launch {
            val song = songRepo.getSong(trackId) ?: return@launch
            songRepo.delete(song)
            repo.refreshTrackCount(playlistId)
        }
    }

    override fun deletePlaylist() {
        Timber.i("PlaylistDetailViewModel.deletePlaylist: START $playlistId")
        launch {
            repo.deletePlaylist(playlistId)
            _deleted.emit(Unit)
        }
    }

    override fun renamePlaylist(newName: String) {
        Timber.i("PlaylistDetailViewModel.renamePlaylist: START $playlistId -> $newName")
        launch {
            val playlist = repo.getPlaylist(playlistId) ?: return@launch
            repo.updatePlaylist(playlist.copy(name = newName))
        }
    }

    /**
     * Returns all downloaded songs in this playlist, sorted by position.
     */
    private suspend fun getDownloadedSongs() = songRepo.getSongs(playlistId)
        .filter { it.status == DataSongStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }
        .sortedBy { it.position }
        .map { it.toPlayerSong() }

    /**
     * Builds a [PlaylistDetailUiState] by merging database data, download progress,
     * and transient flags into a single snapshot.
     *
     * @param playlist the playlist entity, or `null` if not found.
     * @param songs the full list of songs in the playlist.
     * @param progress the current download progress, or `null` when idle.
     * @param isSyncing whether a sync operation is in progress.
     * @param loadError the most recent error message, or `null`.
     */
    private fun mapToUi(
        playlist: xyz.botolog.ghostify.data.db.entity.PlaylistEntity?,
        progress: xyz.botolog.ghostify.download.DownloadProgress?,
        isSyncing: Boolean,
        loadError: String?,
    ): PlaylistDetailUiState {
        if (playlist == null) {
            return PlaylistDetailUiState(
                playlistId = playlistId,
                loading = false,
                error = loadError ?: PLAYLIST_NOT_FOUND_ERROR,
            )
        }
        val downloaded = cachedTracks.count { it.status == UiSongStatus.DOWNLOADED }
        val isRunning = progress?.state == DownloadRunState.RUNNING
        return PlaylistDetailUiState(
            playlistId = playlistId,
            name = playlist.name,
            coverUrl = playlist.coverUrl,
            tracks = cachedTracks,
            downloadedCount = downloaded,
            trackCount = playlist.trackCount,
            loading = false,
            isDownloadingAll = isRunning,
            downloadAllProgress = if (isRunning) progress?.overallPercent?.toInt() else null,
            isSyncing = isSyncing,
            error = loadError,
            origin = playlist.origin.name,
            createdAt = playlist.createdAt,
            lastSyncedAt = playlist.lastSyncedAt,
            owner = playlist.owner ?: "",
            spotifyId = playlist.spotifyId,
            totalDurationMs = cachedTotalDurationMs,
            totalFileSizeBytes = cachedTotalFileSizeBytes,
        )
    }

    companion object {
        /** Error message shown when the playlist cannot be loaded from the database. */
        private const val PLAYLIST_NOT_FOUND_ERROR = "Playlist not found."

        /** Error message shown when the playlist stream fails. */
        private const val PLAYLIST_LOAD_ERROR = "Could not load the playlist."

        /** Error message shown when a sync operation fails without a specific message. */
        private const val SYNC_FAILED_ERROR = "Sync failed."
    }
}
