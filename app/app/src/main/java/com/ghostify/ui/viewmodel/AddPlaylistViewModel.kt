package com.ghostify.ui.viewmodel

import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.PlaylistStatus
import com.ghostify.data.model.SongStatus
import com.ghostify.data.repo.PlaylistRepository
import com.ghostify.data.repo.SettingsRepository
import com.ghostify.download.DownloadManager
import com.ghostify.python.PlaylistFetchResult
import com.ghostify.python.PlaylistMetadata
import com.ghostify.python.PlaylistMetadataBridge
import com.ghostify.ui.contract.AddPlaylistContract
import com.ghostify.ui.contract.AddPlaylistContract.AddPlaylistUiState
import com.ghostify.ui.util.PlaylistUrlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Drives the "Add playlist" flow: paste URL -> validate -> fetch metadata ->
 * preview -> save. No downloads happen here unless auto-download is on.
 *
 * The dialog performs first-line URL validation and only calls [fetch] with a
 * validated playlist id; this VM owns the fetch / preview / save flow and the
 * duplicate warning.
 */
class AddPlaylistViewModel(
    private val validator: (String) -> PlaylistUrlResult,
    private val bridge: PlaylistMetadataBridge,
    private val repo: PlaylistRepository,
    private val settings: SettingsRepository,
    private val downloads: DownloadManager,
    private val onClosed: () -> Unit,
    private val newId: () -> String,
) : ContractViewModel(), AddPlaylistContract {

    private val _state = MutableStateFlow(AddPlaylistUiState())
    override val state: StateFlow<AddPlaylistUiState> = _state.asStateFlow()

    /** Spotify id the current preview was fetched for. */
    private var fetchedSpotifyId: String? = null

    /** Full metadata of the current preview, kept for the save step. */
    private var fetchedMetadata: PlaylistMetadata? = null

    override fun onUrlChange(url: String) {
        fetchedSpotifyId = null
        fetchedMetadata = null
        _state.update {
            it.copy(
                url = url,
                isFetching = false,
                preview = null,
                fetchError = null,
                duplicateWarning = null,
                canSave = false,
            )
        }
    }

    override fun fetch(playlistId: String) {
        if (_state.value.isFetching) return
        launch {
            _state.update {
                it.copy(
                    isFetching = true,
                    fetchError = null,
                    preview = null,
                    duplicateWarning = null,
                    canSave = false,
                )
            }
            val result = withContext(Dispatchers.IO) {
                bridge.fetchPlaylistBlocking(playlistId)
            }
            when (result) {
                is PlaylistFetchResult.Success -> onFetchSuccess(playlistId, result.metadata)
                is PlaylistFetchResult.Failure -> _state.update {
                    it.copy(
                        isFetching = false,
                        fetchError = listOfNotNull(
                            result.error.message,
                            result.error.retryHint,
                        ).joinToString("\n"),
                    )
                }
            }
        }
    }

    private suspend fun onFetchSuccess(spotifyId: String, metadata: PlaylistMetadata) {
        fetchedSpotifyId = spotifyId
        fetchedMetadata = metadata
        val duplicate = repo.getBySpotifyId(spotifyId) != null
        _state.update {
            it.copy(
                isFetching = false,
                preview = metadata.toPreview(),
                duplicateWarning = if (duplicate) "This playlist is already saved." else null,
                canSave = true,
            )
        }
    }

    override fun onSave() {
        val metadata = fetchedMetadata ?: return
        val spotifyId = fetchedSpotifyId ?: return
        if (_state.value.isFetching) return
        launch {
            try {
                val playlistId = newId()
                val playlist = PlaylistEntity(
                    id = playlistId,
                    spotifyId = spotifyId,
                    name = metadata.name,
                    owner = metadata.owner,
                    coverUrl = metadata.coverUrl,
                    trackCount = metadata.trackCount,
                    status = PlaylistStatus.NEW,
                    createdAt = System.currentTimeMillis(),
                )
                val songs = metadata.tracks.map { t ->
                    SongEntity(
                        id = newId(),
                        playlistId = playlistId,
                        spotifyId = t.spotifyId,
                        title = t.title,
                        artists = t.artists,
                        album = t.album,
                        durationMs = t.durationMs.toInt(),
                        coverUrl = t.coverUrl,
                        ytId = t.ytId,
                        status = SongStatus.PENDING,
                        position = t.position,
                    )
                }
                repo.savePlaylistWithSongs(playlist, songs)
                if (settings.getAutoDownload()) {
                    downloads.downloadAll(playlistId)
                }
                onClosed()
            } catch (e: Exception) {
                _state.update {
                    it.copy(fetchError = e.message ?: "Could not save the playlist.")
                }
            }
        }
    }

    override fun onDismiss() {
        onClosed()
    }
}
