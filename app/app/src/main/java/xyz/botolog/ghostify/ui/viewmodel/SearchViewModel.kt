package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.ui.contract.SearchContract
import xyz.botolog.ghostify.ui.contract.SearchContract.SearchUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Backs the Search screen. Queries the Room database for playlists and songs
 * matching the user's search term, with a debounce to avoid hammering the DB
 * on every keystroke.
 *
 * @property playlistDao DAO for playlist queries.
 * @property songDao DAO for song queries.
 */
class SearchViewModel(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
) : ContractViewModel(), SearchContract {

    private val _state = MutableStateFlow(SearchUiState())
    override val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    override fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(playlistResults = emptyList(), songResults = emptyList(), isLoading = false) }
            return
        }
        searchJob = launch {
            _state.update { it.copy(isLoading = true) }
            delay(DEBOUNCE_MS)
            performSearch(query)
        }
    }

    override fun clearResults() {
        searchJob?.cancel()
        _state.update { SearchUiState() }
    }

    private suspend fun performSearch(query: String) {
        try {
            val playlists = playlistDao.searchByName(query).map { entity ->
                SearchContract.PlaylistSearchResult(
                    id = entity.id,
                    name = entity.name,
                    trackCount = entity.trackCount,
                    coverUrl = entity.coverUrl,
                )
            }

            val songs = songDao.searchByTitleOrArtist(query)
            val songResults = songs.mapNotNull { song ->
                val playlist = playlistDao.getById(song.playlistId) ?: return@mapNotNull null
                SearchContract.SongSearchResult(
                    songId = song.id,
                    title = song.title,
                    artists = song.artists,
                    playlistId = song.playlistId,
                    playlistName = playlist.name,
                    position = song.position,
                    coverUrl = song.coverUrl,
                )
            }

            _state.update {
                it.copy(
                    playlistResults = playlists,
                    songResults = songResults,
                    isLoading = false,
                )
            }
            Timber.i("SearchViewModel: found ${playlists.size} playlists, ${songResults.size} songs for '$query'")
        } catch (e: Exception) {
            Timber.e(e, "SearchViewModel: search failed for '$query'")
            _state.update { it.copy(isLoading = false) }
        }
    }

    private companion object {
        /** Debounce delay in milliseconds before triggering a search. */
        private const val DEBOUNCE_MS = 300L
    }
}
