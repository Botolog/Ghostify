package xyz.botolog.ghostify.ui.contract

import xyz.botolog.ghostify.ui.model.PlaylistUi
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Library screen.
 *
 * The UI component depends only on this interface (implemented by the `viewmodels`
 * component in the real app, or by a fake in Compose UI tests). State is exposed as a
 * [StateFlow] so the screen recomposes live as the database / download flow updates it.
 */
interface LibraryContract {

    val state: StateFlow<LibraryUiState>

    /** User pressed the FAB → open the Add-playlist dialog. */
    fun onAddClick()

    /** User pressed the Settings action in the top bar. */
    fun onOpenSettings()

    /** Reorder a playlist to a new position in the list. */
    fun reorderPlaylist(playlistId: String, newSortOrder: Int)

    /** Delete a playlist and all its songs. */
    fun deletePlaylist(playlistId: String)

    /** Kill the entire app process. */
    fun shutdownApp()

    data class LibraryUiState(
        val playlists: List<PlaylistUi> = emptyList(),
        val loading: Boolean = true,
        val isAddDialogOpen: Boolean = false,
    )
}
