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

/**
 * Add-playlist dialog: paste URL -> local validation -> fetch metadata -> preview -> save.
 *
 * @param validator injected for tests; defaults to the real [PlaylistUrlValidator].
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
            Column {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { contract.onUrlChange(it) },
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

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    state.isFetching -> FetchingRow()

                    state.fetchError != null -> Text(
                        text = state.fetchError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(AddPlaylistTestTags.FETCH_ERROR),
                    )

                    state.preview != null -> PlaylistPreviewCard(state.preview!!)
                }

                state.duplicateWarning?.let {
                    Spacer(modifier = Modifier.height(8.dp))
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

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        when (val result = validator(state.url)) {
                            is PlaylistUrlResult.Invalid -> urlError = result.message
                            is PlaylistUrlResult.Valid -> {
                                urlError = null
                                contract.fetch(result.playlistId, result.origin)
                            }
                        }
                    },
                    enabled = state.url.isNotBlank() && !state.isFetching && state.preview == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AddPlaylistTestTags.FETCH_BUTTON),
                ) {
                    Text("Fetch metadata")
                }
            }
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

@Composable
private fun FetchingRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag(AddPlaylistTestTags.LOADING),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text("Fetching playlist metadata…", style = MaterialTheme.typography.bodyMedium)
    }
}

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
        Spacer(modifier = Modifier.width(12.dp))
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
