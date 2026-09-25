package xyz.botolog.ghostify.ui.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistSortBottomSheet(
    selectedOption: PlaylistSortOption,
    descending: Boolean,
    onOptionSelected: (PlaylistSortOption) -> Unit,
    onDescendingChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(PlaylistDetailTestTags.SORT_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(PlaylistDetailTestTags.SORT_SHEET_CONTENT),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sort playlist",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
            Text(
                text = "Change the order shown here. Your saved playlist, playback, and downloads stay unchanged.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            PlaylistSortOption.entries.forEach { option ->
                PlaylistSortOptionRow(
                    option = option,
                    selected = option == selectedOption,
                    onClick = { onOptionSelected(option) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            SortDirectionRow(
                descending = descending,
                onDescendingChanged = onDescendingChanged,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlaylistSortOptionRow(
    option: PlaylistSortOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PlaylistDetailTestTags.sortOption(option))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = sortOptionIcon(option),
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = sortOptionLabel(option),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SortDirectionRow(
    descending: Boolean,
    onDescendingChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PlaylistDetailTestTags.SORT_DIRECTION)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (descending) "Descending" else "Ascending",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (descending) "Highest to lowest" else "Lowest to highest",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = descending,
            onCheckedChange = onDescendingChanged,
            modifier = Modifier.testTag(PlaylistDetailTestTags.SORT_DIRECTION_SWITCH),
        )
    }
}

private fun sortOptionIcon(option: PlaylistSortOption): ImageVector = when (option) {
    PlaylistSortOption.PLAYLIST_ORDER -> Icons.AutoMirrored.Filled.PlaylistPlay
    PlaylistSortOption.TITLE -> Icons.Filled.Title
    PlaylistSortOption.ARTIST -> Icons.Filled.Person
    PlaylistSortOption.ALBUM -> Icons.Filled.Album
    PlaylistSortOption.DURATION -> Icons.Filled.AccessTime
    PlaylistSortOption.DATE_ADDED -> Icons.Filled.CalendarToday
    PlaylistSortOption.DOWNLOADED_STATUS -> Icons.Filled.DownloadDone
}

private fun sortOptionLabel(option: PlaylistSortOption): String = when (option) {
    PlaylistSortOption.PLAYLIST_ORDER -> "Playlist order"
    PlaylistSortOption.TITLE -> "Title A–Z"
    PlaylistSortOption.ARTIST -> "Artist"
    PlaylistSortOption.ALBUM -> "Album"
    PlaylistSortOption.DURATION -> "Duration"
    PlaylistSortOption.DATE_ADDED -> "Date added"
    PlaylistSortOption.DOWNLOADED_STATUS -> "Downloaded status"
}
