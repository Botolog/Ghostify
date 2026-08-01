package com.ghostify.ui.contract

import com.ghostify.ui.model.PlaylistPreview
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel contract for the Add-playlist dialog.
 *
 * The dialog performs first-line URL validation locally (see
 * [com.ghostify.ui.util.PlaylistUrlValidator]) and only calls [fetch] with a validated
 * playlist id; the contract owns the fetch / preview / save flow.
 */
interface AddPlaylistContract {

    val state: StateFlow<AddPlaylistUiState>

    fun onUrlChange(url: String)

    /** Validated id obtained from the URL. */
    fun fetch(playlistId: String)

    fun onSave()

    fun onDismiss()

    data class AddPlaylistUiState(
        val url: String = "",
        val isFetching: Boolean = false,
        val preview: PlaylistPreview? = null,
        /** Readable fetch error (private / network / not found). */
        val fetchError: String? = null,
        /** Set when this playlist is already saved — shown as a non-blocking warning. */
        val duplicateWarning: String? = null,
        /** True only after a successful fetch, which is what enables Save. */
        val canSave: Boolean = false,
    )
}
