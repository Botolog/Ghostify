package com.ghostify.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Backs the Library (home) screen.
 *
 * State is derived from [PlaylistRepository.observePlaylists] and exposed as a
 * single [StateFlow]. The VM's only job on this screen is presentation
 * mapping + ordering: playlists are exposed **sorted by creation date,
 * newest first** (T-109).
 *
 * Loading / Empty / Error / Content are distinct states so the UI renders
 * correct placeholders (T-112). The upstream flow is collected inside
 * [viewModelScope], so the subscription dies with the VM — no leak.
 */
class LibraryViewModel(
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observePlaylists()
                .map(::mapList)
                .catch { e -> emit(LibraryUiState.Error(e.message ?: "Could not load your library.")) }
                .collect { _uiState.value = it }
        }
    }

    private fun mapList(playlists: List<PlaylistSummary>): LibraryUiState {
        // Newest first (T-109). Stable for equal timestamps via secondary key.
        val sorted = playlists.sortedWith(compareByDescending<PlaylistSummary> { it.createdAt }.thenBy { it.id })
        return if (sorted.isEmpty()) LibraryUiState.Empty else LibraryUiState.Content(sorted)
    }
}
