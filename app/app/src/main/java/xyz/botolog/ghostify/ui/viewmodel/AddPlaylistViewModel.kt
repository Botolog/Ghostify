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
 *
 * @property validator function that validates a raw URL string.
 * @property bridge bridge to the Python playlist metadata fetcher.
 * @property repo playlist and song persistence layer.
 * @property settings app-wide settings repository.
 * @property downloads download orchestration layer.
 * @property onClosed callback invoked when the dialog is dismissed or save completes.
 * @property newId function that generates a new unique id.
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
        resetFetchedData()
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
            handleFetchResult(playlistId, result)
        }
    }

    override fun onSave() {
        Timber.i("AddPlaylistViewModel.onSave: START")
        val metadata = fetchedMetadata ?: return
        val playlistId = fetchedPlaylistId ?: return
        if (_state.value.isFetching) return
        launch {
            try {
                savePlaylist(playlistId, metadata)
            } catch (e: Exception) {
                Timber.e(e, "AddPlaylistViewModel.onSave: FAILED")
                _state.update {
                    it.copy(fetchError = e.message ?: DEFAULT_SAVE_ERROR)
                }
            }
        }
    }

    override fun onDismiss() {
        Timber.i("AddPlaylistViewModel.onDismiss: START")
        _state.value = AddPlaylistUiState()
        resetFetchedData()
        onClosed()
    }

    /**
     * Resets all fetched-data fields back to their defaults.
     */
    private fun resetFetchedData() {
        fetchedPlaylistId = null
        fetchedMetadata = null
        fetchedOrigin = PlaylistOrigin.SPOTIFY
    }

    /**
     * Dispatches the result of a metadata fetch to the appropriate state update.
     *
     * @param playlistId the id that was fetched.
     * @param result the raw fetch result from the bridge.
     */
    private suspend fun handleFetchResult(playlistId: String, result: PlaylistFetchResult) {
        when (result) {
            is PlaylistFetchResult.Success -> onFetchSuccess(playlistId, result.metadata)
            is PlaylistFetchResult.Failure -> {
                Timber.e(
                    "AddPlaylistViewModel.fetch: FAILED: ${result.error.code} - ${result.error.message}",
                )
                _state.update {
                    it.copy(
                        isFetching = false,
                        fetchError = listOfNotNull(
                            result.error.message,
                            result.error.retryHint,
                        ).joinToString(NEWLINE),
                    )
                }
            }
        }
    }

    /**
     * Updates state after a successful metadata fetch, including duplicate detection.
     *
     * @param playlistId the playlist id that was fetched.
     * @param metadata the fetched playlist metadata.
     */
    private suspend fun onFetchSuccess(playlistId: String, metadata: PlaylistMetadata) {
        fetchedPlaylistId = playlistId
        fetchedMetadata = metadata
        val isDuplicate = checkForDuplicate(playlistId)
        _state.update {
            it.copy(
                isFetching = false,
                preview = metadata.toPreview(),
                duplicateWarning = if (isDuplicate) DUPLICATE_WARNING_TEXT else null,
                canSave = true,
            )
        }
    }

    /**
     * Checks whether the playlist already exists locally.
     *
     * @param playlistId the Spotify id or YouTube playlist id to look up.
     * @return `true` if a matching record is found.
     */
    private suspend fun checkForDuplicate(playlistId: String): Boolean = when (fetchedOrigin) {
        PlaylistOrigin.SPOTIFY -> repo.getBySpotifyId(playlistId) != null
        PlaylistOrigin.YOUTUBE -> repo.getByYtPlaylistId(playlistId) != null
    }

    /**
     * Builds the playlist and song entities, persists them, and triggers auto-download.
     *
     * @param playlistId the Spotify id or YouTube playlist id.
     * @param metadata the full metadata to persist.
     */
    private suspend fun savePlaylist(playlistId: String, metadata: PlaylistMetadata) {
        val playlistEntityId = newId()
        val playlist = buildPlaylistEntity(playlistEntityId, playlistId, metadata)
        val songs = buildSongEntities(playlistEntityId, metadata)
        repo.savePlaylistWithSongs(playlist, songs)
        maybeTriggerAutoDownload(playlistEntityId)
        resetStateAfterSave()
    }

    /**
     * Constructs a [PlaylistEntity] from the fetched metadata.
     *
     * @param entityId the local database primary key.
     * @param remoteId the Spotify id or YouTube playlist id.
     * @param metadata the fetched playlist metadata.
     */
    private fun buildPlaylistEntity(
        entityId: String,
        remoteId: String,
        metadata: PlaylistMetadata,
    ): PlaylistEntity = PlaylistEntity(
        id = entityId,
        spotifyId = remoteId,
        name = metadata.name,
        owner = metadata.owner,
        coverUrl = metadata.coverUrl,
        trackCount = metadata.trackCount,
        status = PlaylistStatus.NEW,
        createdAt = System.currentTimeMillis(),
        lastSyncedAt = System.currentTimeMillis(),
        origin = fetchedOrigin,
    )

    /**
     * Builds a [SongEntity] for each track in the fetched metadata.
     *
     * @param playlistEntityId the parent playlist's database id.
     * @param metadata the fetched playlist metadata containing the track list.
     */
    private fun buildSongEntities(
        playlistEntityId: String,
        metadata: PlaylistMetadata,
    ): List<SongEntity> = metadata.tracks.map { track ->
        SongEntity(
            id = newId(),
            playlistId = playlistEntityId,
            spotifyId = track.spotifyId,
            title = track.title,
            artists = track.artists,
            album = track.album,
            durationMs = track.durationMs.toInt(),
            coverUrl = track.coverUrl,
            ytId = track.ytId,
            status = SongStatus.PENDING,
            position = track.position,
        )
    }

    /**
     * Triggers auto-download if the setting is enabled.
     *
     * @param playlistEntityId the playlist to download.
     */
    private suspend fun maybeTriggerAutoDownload(playlistEntityId: String) {
        if (settings.getAutoDownload()) {
            downloads.downloadAll(playlistEntityId)
        }
    }

    /**
     * Resets transient state after a successful save and notifies the close callback.
     */
    private fun resetStateAfterSave() {
        _state.value = AddPlaylistUiState()
        resetFetchedData()
        onClosed()
    }

    companion object {
        /** Default error message when the save operation fails without a specific message. */
        private const val DEFAULT_SAVE_ERROR = "Could not save the playlist."

        /** Warning text shown when the playlist already exists locally. */
        private const val DUPLICATE_WARNING_TEXT = "This playlist is already saved."

        /** Separator used when joining fetch error messages. */
        private const val NEWLINE = "\n"
    }
}
