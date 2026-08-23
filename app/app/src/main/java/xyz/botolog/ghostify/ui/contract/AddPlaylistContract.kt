package xyz.botolog.ghostify.ui.contract

import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.ui.model.PlaylistPreview
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Add-playlist dialog.
 *
 * The dialog performs first-line URL validation locally (see
 * [xyz.botolog.ghostify.ui.util.PlaylistUrlValidator]) and only calls [fetch] with a validated
 * playlist id + origin; the contract owns the fetch / preview / save flow.
 */
interface AddPlaylistContract {

    /** Observable UI state for the Add-playlist dialog. */
    val state: StateFlow<AddPlaylistUiState>

    /**
     * Called when the user edits the URL text field.
     *
     * Resets any previous fetch state so the dialog returns to its initial appearance.
     *
     * @param url the raw URL string entered by the user.
     */
    fun onUrlChange(url: String)

    /**
     * Fetches playlist metadata from the remote source.
     *
     * @param playlistId validated Spotify id or YouTube URL.
     * @param origin the platform the playlist originates from.
     */
    fun fetch(playlistId: String, origin: PlaylistOrigin)

    /** Persists the fetched playlist and its songs to the local database. */
    fun onSave()

    /** Resets all state and closes the dialog. */
    fun onDismiss()

    /**
     * Immutable UI state for the Add-playlist dialog.
     *
     * @property url raw URL text from the input field.
     * @property origin detected platform of the playlist.
     * @property isFetching `true` while a remote metadata fetch is in progress.
     * @property preview playlist preview shown after a successful fetch.
     * @property fetchError human-readable error message when the fetch fails.
     * @property duplicateWarning non-blocking warning when the playlist already exists locally.
     * @property canSave `true` only after a successful fetch, enabling the Save button.
     */
    data class AddPlaylistUiState(
        val url: String = "",
        val origin: PlaylistOrigin = PlaylistOrigin.SPOTIFY,
        val isFetching: Boolean = false,
        val preview: PlaylistPreview? = null,
        val fetchError: String? = null,
        val duplicateWarning: String? = null,
        val canSave: Boolean = false,
    )
}
