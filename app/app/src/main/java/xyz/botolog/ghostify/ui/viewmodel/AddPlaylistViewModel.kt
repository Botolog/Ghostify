package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.data.model.PlaylistStatus
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.data.repo.PlaylistRepository
import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.python.PlaylistFetchResult
import xyz.botolog.ghostify.python.PlaylistMetadata
import xyz.botolog.ghostify.python.PlaylistMetadataBridge
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract.AddPlaylistUiState
import xyz.botolog.ghostify.ui.util.PlaylistUrlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Drives the "Add playlist" flow: paste URL -> validate -> fetch metadata ->
 * preview -> save. No downloads happen here unless auto-download is on.
 *
 * The dialog performs first-line URL validation and only calls [fetch] with a
 * validated playlist id + origin; this VM owns the fetch / preview / save flow
 * and the duplicate warning.
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

    /** Spotify id / YouTube URL the current preview was fetched for. */
    private var fetchedPlaylistId: String? = null

    /** Origin of the current preview. */
    private var fetchedOrigin: PlaylistOrigin = PlaylistOrigin.SPOTIFY

    /** Full metadata of the current preview, kept for the save step. */
    private var fetchedMetadata: PlaylistMetadata? = null

    override fun onUrlChange(url: String) {
        Timber.i("AddPlaylistViewModel.onUrlChange: START")
        fetchedPlaylistId = null
        fetchedMetadata = null
        fetchedOrigin = PlaylistOrigin.SPOTIFY
        _state.update {
            it.copy(
                url = url,
                origin = PlaylistOrigin.SPOTIFY,
                isFetching = false,
                preview = null,
                fetchError = null,
                duplicateWarning = null,
                canSave = false,
            )
        }
    }

    override fun fetch(playlistId: String, origin: PlaylistOrigin) {
        Timber.i("AddPlaylistViewModel.fetch: START id=$playlistId origin=$origin")
        if (_state.value.isFetching) return
        fetchedOrigin = origin
        launch {
            _state.update {
                it.copy(
                    isFetching = true,
                    origin = origin,
                    fetchError = null,
                    preview = null,
                    duplicateWarning = null,
                    canSave = false,
                )
            }
            val result = withContext(Dispatchers.IO) {
                bridge.fetchPlaylistBlocking(playlistId, origin)
            }
            when (result) {
                is PlaylistFetchResult.Success -> onFetchSuccess(playlistId, result.metadata)
                is PlaylistFetchResult.Failure -> {
                    Timber.e("AddPlaylistViewModel.fetch: FAILED: ${result.error.code} - ${result.error.message}")
                    _state.update {
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
    }

    private suspend fun onFetchSuccess(playlistId: String, metadata: PlaylistMetadata) {
        fetchedPlaylistId = playlistId
        fetchedMetadata = metadata
        val duplicate = when (fetchedOrigin) {
            PlaylistOrigin.SPOTIFY -> repo.getBySpotifyId(playlistId) != null
            PlaylistOrigin.YOUTUBE -> repo.getByYtPlaylistId(playlistId) != null
        }
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
        Timber.i("AddPlaylistViewModel.onSave: START")
        val metadata = fetchedMetadata ?: return
        val playlistId = fetchedPlaylistId ?: return
        if (_state.value.isFetching) return
        launch {
            try {
                val playlistEntityId = newId()
                val playlist = PlaylistEntity(
                    id = playlistEntityId,
                    spotifyId = if (fetchedOrigin == PlaylistOrigin.YOUTUBE) playlistId else playlistId,
                    name = metadata.name,
                    owner = metadata.owner,
                    coverUrl = metadata.coverUrl,
                    trackCount = metadata.trackCount,
                    status = PlaylistStatus.NEW,
                    createdAt = System.currentTimeMillis(),
                    origin = fetchedOrigin,
                )
                val songs = metadata.tracks.map { t ->
                    SongEntity(
                        id = newId(),
                        playlistId = playlistEntityId,
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
                    downloads.downloadAll(playlistEntityId)
                }
                onClosed()
            } catch (e: Exception) {
                Timber.e(e, "AddPlaylistViewModel.onSave: FAILED")
                _state.update {
                    it.copy(fetchError = e.message ?: "Could not save the playlist.")
                }
            }
        }
    }

    override fun onDismiss() {
        Timber.i("AddPlaylistViewModel.onDismiss: START")
        onClosed()
    }
}
