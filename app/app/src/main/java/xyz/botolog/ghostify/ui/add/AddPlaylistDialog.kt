package xyz.botolog.ghostify.ui.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract
import xyz.botolog.ghostify.ui.contract.AddPlaylistContract.AddPlaylistUiState
import xyz.botolog.ghostify.ui.model.PlaylistPreview
import xyz.botolog.ghostify.ui.util.PlaylistUrlResult
import xyz.botolog.ghostify.ui.util.PlaylistUrlValidator

/**
 * Test tag constants for the add-playlist dialog. Used by Compose UI tests to locate elements.
 */
object AddPlaylistTestTags {
    const val DIALOG = "add_dialog"
    const val URL_FIELD = "add_url_field"
    const val URL_ERROR = "add_url_error"
    const val FETCH_BUTTON = "add_fetch_button"
    const val LOADING = "add_loading"
    const val FETCH_ERROR = "add_fetch_error"
    const val PREVIEW = "add_preview"
    const val DUPLICATE_WARNING = "add_duplicate_warning"
    const val SAVE_BUTTON = "add_save_button"
    const val CANCEL_BUTTON = "add_cancel_button"
}

private val SPACER_HEIGHT_MEDIUM = 12.dp
private val SPACER_HEIGHT_SMALL = 8.dp

/**
 * Add-playlist dialog: paste URL -> local validation -> fetch metadata -> preview -> save.
 *
 * @param contract ViewModel contract driving the dialog state and actions.
 * @param validator injected URL validator; defaults to the real [PlaylistUrlValidator] for tests.
 * @param modifier optional modifier applied to the dialog root.
 */
@Composable
fun AddPlaylistDialog(
    contract: AddPlaylistContract,
    validator: (String) -> PlaylistUrlResult = PlaylistUrlValidator::validate,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    // Local validation error lives here (before any network call); fetch/duplicate errors
    // come from the contract so the flow stays a single source of truth once a fetch starts.
    var urlError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = contract::onDismiss,
        modifier = modifier.testTag(AddPlaylistTestTags.DIALOG),
        title = { Text("Add playlist") },
        text = {
            DialogContent(
                state = state,
                validator = validator,
                onUrlChange = contract::onUrlChange,
                onFetch = contract::fetch,
                urlError = urlError,
                onUrlErrorChange = { urlError = it },
            )
        },
        confirmButton = {
            Button(
                onClick = contract::onSave,
                enabled = state.canSave,
                modifier = Modifier.testTag(AddPlaylistTestTags.SAVE_BUTTON),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = contract::onDismiss,
                modifier = Modifier.testTag(AddPlaylistTestTags.CANCEL_BUTTON),
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Inner content of the add-playlist dialog, extracted for clarity.
 */
@Composable
private fun DialogContent(
    state: AddPlaylistUiState,
    validator: (String) -> PlaylistUrlResult,
    onUrlChange: (String) -> Unit,
    onFetch: (playlistId: String, origin: xyz.botolog.ghostify.data.model.PlaylistOrigin) -> Unit,
    urlError: String?,
    onUrlErrorChange: (String?) -> Unit,
) {
    Column {
        UrlInputField(
            url = state.url,
            onValueChange = onUrlChange,
            urlError = urlError,
        )

        Spacer(modifier = Modifier.height(SPACER_HEIGHT_MEDIUM))

        when {
            state.isFetching -> FetchingRow()

            state.fetchError != null -> Text(
                text = state.fetchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(AddPlaylistTestTags.FETCH_ERROR),
            )

            state.preview != null -> PlaylistPreviewCard(state.preview!!)
        }

        DuplicateWarning(warning = state.duplicateWarning)

        Spacer(modifier = Modifier.height(SPACER_HEIGHT_MEDIUM))

        FetchMetadataButton(
            url = state.url,
            isFetching = state.isFetching,
            hasPreview = state.preview != null,
            validator = validator,
            onUrlErrorChange = onUrlErrorChange,
            onFetch = onFetch,
        )
    }
}

/**
 * URL input field with optional validation error display.
 */
@Composable
private fun UrlInputField(
    url: String,
    onValueChange: (String) -> Unit,
    urlError: String?,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AddPlaylistTestTags.URL_FIELD),
        placeholder = { Text("Enter a playlist link") },
        singleLine = true,
        isError = urlError != null,
        supportingText = {
            urlError?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(AddPlaylistTestTags.URL_ERROR),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
}

/**
 * Row shown while playlist metadata is being fetched from the network.
 */
@Composable
private fun FetchingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(AddPlaylistTestTags.LOADING),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(SPACER_HEIGHT_MEDIUM))
        Text("Fetching playlist metadata…", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Displays the fetched playlist metadata as a preview card with cover art, name, and track count.
 */
@Composable
private fun PlaylistPreviewCard(preview: PlaylistPreview) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AddPlaylistTestTags.PREVIEW),
    ) {
        AsyncImage(
            model = preview.coverUrl,
            contentDescription = "Playlist cover",
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(SPACER_HEIGHT_MEDIUM))
        Column {
            Text(
                text = preview.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Text(
                text = "by ${preview.owner} · ${preview.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shows a duplicate warning row with a warning icon, if [warning] is non-null.
 */
@Composable
private fun DuplicateWarning(warning: String?) {
    warning?.let {
        Spacer(modifier = Modifier.height(SPACER_HEIGHT_SMALL))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag(AddPlaylistTestTags.DUPLICATE_WARNING),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * "Fetch metadata" button that triggers local validation and then a network fetch.
 */
@Composable
private fun FetchMetadataButton(
    url: String,
    isFetching: Boolean,
    hasPreview: Boolean,
    validator: (String) -> PlaylistUrlResult,
    onUrlErrorChange: (String?) -> Unit,
    onFetch: (playlistId: String, origin: xyz.botolog.ghostify.data.model.PlaylistOrigin) -> Unit,
) {
    OutlinedButton(
        onClick = {
            when (val result = validator(url)) {
                is PlaylistUrlResult.Invalid -> onUrlErrorChange(result.message)
                is PlaylistUrlResult.Valid -> {
                    onUrlErrorChange(null)
                    onFetch(result.playlistId, result.origin)
                }
            }
        },
        enabled = url.isNotBlank() && !isFetching && !hasPreview,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AddPlaylistTestTags.FETCH_BUTTON),
    ) {
        Text("Fetch metadata")
    }
}
