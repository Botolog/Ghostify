package com.ghostify.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives the "Add playlist" flow: paste URL → validate → fetch metadata →
 * preview → save. No downloads happen here (PROJECT.md §5).
 *
 * ## Flow guarantees
 *  - **Invalid URL** (T-114): the parser rejects it synchronously and the VM
 *    lands in [AddPlaylistUiState.Error] — the message is shown and, because
 *    [AddPlaylistUiState.dialogOpen] is still true, the dialog stays open.
 *  - **Success** (T-115): after a clean save the VM reaches
 *    [AddPlaylistUiState.Success] (dialog closes) and the row is written through
 *    the shared [PlaylistRepository], so the Library immediately reflects the
 *    new playlist.
 *  - **No double submit**: while [Fetching] or [Saving], [submit]/[save] are
 *    no-ops, so a double-tap can never start two fetches or two saves.
 *  - Editing the URL clears a previous error back to [Idle]; network/parse
 *    failures keep the dialog open with the last URL intact.
 */
class AddPlaylistViewModel(
    private val parser: PlaylistUrlParser,
    private val fetcher: PlaylistFetcher,
    private val repository: PlaylistRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val _uiState = MutableStateFlow<AddPlaylistUiState>(AddPlaylistUiState.Idle)
    val uiState: StateFlow<AddPlaylistUiState> = _uiState.asStateFlow()

    private val url = MutableStateFlow("")

    fun onUrlChange(newValue: String) {
        url.value = newValue
        // A fresh edit invalidates a previous error; keep the dialog open.
        if (_uiState.value is AddPlaylistUiState.Error) _uiState.value = AddPlaylistUiState.Idle
    }

    fun submit() {
        if (_uiState.value is AddPlaylistUiState.Fetching) return
        if (_uiState.value is AddPlaylistUiState.Saving) return

        _uiState.value = AddPlaylistUiState.Validating
        when (val parsed = parser.parse(url.value)) {
            is UrlParseResult.Rejected -> _uiState.value =
                AddPlaylistUiState.Error(parsed.message)
            is UrlParseResult.Success -> viewModelScope.launch { fetch(parsed.spotifyId) }
        }
    }

    private suspend fun fetch(spotifyId: String) {
        _uiState.value = AddPlaylistUiState.Fetching
        _uiState.value = when (val result = fetcher.fetch(spotifyId)) {
            is PlaylistFetchResult.Success -> AddPlaylistUiState.Preview(result.metadata, spotifyId)
            is PlaylistFetchResult.Failure -> AddPlaylistUiState.Error(result.message, result.retryHint)
        }
    }

    /** Saves the previewed playlist + tracks; failures keep the dialog open. */
    fun save() {
        val preview = _uiState.value as? AddPlaylistUiState.Preview ?: return
        if (_uiState.value is AddPlaylistUiState.Saving) return

        viewModelScope.launch {
            _uiState.value = AddPlaylistUiState.Saving
            try {
                val id = newId()
                val playlist = PlaylistSummary(
                    id = id,
                    spotifyId = preview.spotifyId,
                    name = preview.metadata.name,
                    owner = preview.metadata.owner,
                    coverUrl = preview.metadata.coverUrl,
                    trackCount = preview.metadata.trackCount,
                    status = PlaylistStatus.NEW,
                    createdAt = System.currentTimeMillis(),
                )
                val tracks = preview.metadata.tracks.map { t ->
                    Track(
                        id = newId(),
                        playlistId = id,
                        spotifyId = t.spotifyId,
                        title = t.title,
                        artists = t.artists,
                        album = t.album,
                        durationMs = t.durationMs.toInt(),
                        coverUrl = t.coverUrl,
                        status = SongStatus.PENDING,
                        position = t.position,
                    )
                }
                repository.save(playlist, tracks)
                _uiState.value = AddPlaylistUiState.Success
            } catch (e: Exception) {
                _uiState.value = AddPlaylistUiState.Error(e.message ?: "Could not save the playlist.")
            }
        }
    }
}
