package xyz.botolog.ghostify.ui.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Search screen.
 */
interface SearchContract {

    /** Observable UI state for the Search screen. */
    val state: StateFlow<SearchUiState>

    /**
     * Called when the search query text changes.
     *
     * @param query the current text in the search bar.
     */
    fun onQueryChange(query: String)

    /**
     * Clears the current search results and query.
     */
    fun clearResults()

    /**
     * Immutable UI state for the Search screen.
     *
     * @property query the current search text.
     * @property playlistResults playlists matching the query.
     * @property songResults songs matching the query, each with its parent playlist name.
     * @property isLoading `true` while a search is in progress.
     */
    data class SearchUiState(
        val query: String = "",
        val playlistResults: List<PlaylistSearchResult> = emptyList(),
        val songResults: List<SongSearchResult> = emptyList(),
        val isLoading: Boolean = false,
    )

    /**
     * A playlist matching the search query.
     *
     * @property id the playlist row id.
     * @property name the playlist name.
     * @property trackCount number of tracks in the playlist.
     */
    data class PlaylistSearchResult(
        val id: String,
        val name: String,
        val trackCount: Int,
        val coverUrl: String? = null,
    )

    /**
     * A song matching the search query, with its parent playlist context.
     *
     * @property songId the song row id.
     * @property title the song title.
     * @property artists the song artists.
     * @property playlistId the parent playlist id.
     * @property playlistName the parent playlist name.
     * @property position the song's position within the playlist.
     */
    data class SongSearchResult(
        val songId: String,
        val title: String,
        val artists: String,
        val playlistId: String,
        val playlistName: String,
        val position: Int,
        val coverUrl: String? = null,
    )
}
