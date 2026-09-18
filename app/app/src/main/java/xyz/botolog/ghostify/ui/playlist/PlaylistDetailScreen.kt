package xyz.botolog.ghostify.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract.PlaylistDetailUiState
import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.util.DurationFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test tag constants for the playlist detail screen. Used by Compose UI tests to locate elements.
 */
object PlaylistDetailTestTags {
    const val COVER = "detail_cover"
    const val TRACK_LIST = "track_list"
    const val PLAY_ALL = "play_all"
    const val PLAY_ALL_HINT = "play_all_hint"
    const val DOWNLOAD_ALL = "download_all"
    const val RESYNC = "resync"
    const val SYNCING = "detail_syncing"
    const val DOWNLOAD_PROGRESS = "download_all_progress"
    const val LOADING = "detail_loading"
    const val ERROR = "detail_error"

    /**
     * Returns the test tag for a specific track row.
     *
     * @param id unique track identifier.
     */
    fun track(id: String) = "track_item_$id"

    /**
     * Returns the test tag for a track status icon.
     *
     * @param status the [SongStatus] to render a tag for.
     */
    fun trackStatus(status: SongStatus) = "track_status_$status"
}

private val COVER_SIZE = 72.dp
private val ICON_SIZE = 18.dp
private val STATUS_ICON_SIZE = 20.dp
private val TRACK_STATUS_BOX_SIZE = 24.dp
private val SYNC_INDICATOR_SIZE = 16.dp
private val HIGHLIGHT_DURATION_MS = 1500L

/**
 * Full screen showing playlist details including cover art, track list, and action buttons.
 *
 * @param contract ViewModel contract driving the playlist detail state and actions.
 * @param onBack callback invoked when the back button is tapped.
 * @param modifier optional modifier applied to the screen root.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    contract: PlaylistDetailContract,
    onBack: () -> Unit,
    highlightSongId: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        contract.deleted.collect {
            onBack()
        }
    }

    if (showInfoDialog) {
        PlaylistInfoDialog(
            state = state,
            onDismiss = { showInfoDialog = false },
        )
    }

    if (showRenameDialog) {
        PlaylistRenameDialog(
            currentName = state.name,
            onConfirm = { newName ->
                contract.renamePlaylist(newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    if (showDeleteDialog) {
        PlaylistDeleteDialog(
            playlistName = state.name,
            onConfirm = {
                contract.deletePlaylist()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Info") },
                            onClick = {
                                showMenu = false
                                showInfoDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Info, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                showRenameDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = {
                                showMenu = false
                                Toast.makeText(context, "Not implemented yet", Toast.LENGTH_SHORT).show()
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.Share, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        PlaylistDetailContent(
            state = state,
            contract = contract,
            padding = padding,
            highlightSongId = highlightSongId,
        )
    }
}

@Composable
private fun PlaylistInfoDialog(
    state: PlaylistDetailUiState,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlist Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Name", state.name)
                InfoRow("Owner", state.owner.ifBlank { "—" })
                InfoRow("Origin", state.origin)
                InfoRow("Tracks", "${state.trackCount}")
                InfoRow("Downloaded", "${state.downloadedCount}")
                if (state.totalDurationMs > 0) {
                    InfoRow("Total duration", formatDuration(state.totalDurationMs))
                }
                if (state.totalFileSizeBytes > 0) {
                    InfoRow("Total size", formatFileSize(state.totalFileSizeBytes))
                }
                if (state.createdAt > 0L) {
                    InfoRow("Created", dateFormat.format(Date(state.createdAt)))
                }
                if (state.lastSyncedAt != null && state.lastSyncedAt > 0L) {
                    InfoRow("Last synced", dateFormat.format(Date(state.lastSyncedAt)))
                }
                if (state.spotifyId.isNotBlank()) {
                    InfoRow("ID", state.spotifyId)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PlaylistRenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name != currentName,
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PlaylistDeleteDialog(
    playlistName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Playlist") },
        text = { Text("Are you sure you want to delete \"$playlistName\"? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun formatDuration(totalMs: Long): String {
    val totalSeconds = totalMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}

/**
 * Main content area of the playlist detail screen, switching between loading, error, and
 * content states.
 */
@Composable
private fun PlaylistDetailContent(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
    padding: PaddingValues,
    highlightSongId: String? = null,
) {
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        when {
            state.loading && state.tracks.isEmpty() -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag(PlaylistDetailTestTags.LOADING),
                )
            }
            state.error != null && state.tracks.isEmpty() -> ErrorState(
                message = state.error,
                onRetry = contract::sync,
            )
            else -> PlaylistContent(state = state, contract = contract, highlightSongId = highlightSongId)
        }
    }
}

/**
 * Error state with a message and retry button.
 */
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(PlaylistDetailTestTags.ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

/**
 * Full playlist content: header, action bar, optional syncing indicator, and track list.
 */
@Composable
private fun PlaylistContent(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
    highlightSongId: String? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PlaylistHeader(state = state)
        ActionBar(state = state, contract = contract)

        SyncingIndicator(isSyncing = state.isSyncing)

        TrackList(
            tracks = state.tracks,
            onPlaySong = contract::playFromSong,
            onDownloadSong = contract::downloadSong,
            onRetrySong = contract::retryTrack,
            highlightSongId = highlightSongId,
        )
    }
}

/**
 * Shows a syncing indicator row when a sync operation is in progress.
 */
@Composable
private fun SyncingIndicator(isSyncing: Boolean) {
    if (isSyncing) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(PlaylistDetailTestTags.SYNCING),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(SYNC_INDICATOR_SIZE))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Syncing with Spotify…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Header section with playlist cover art, name, and track count.
 */
@Composable
private fun PlaylistHeader(state: PlaylistDetailUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = state.coverUrl,
            contentDescription = "Playlist cover",
            modifier = Modifier
                .size(COVER_SIZE)
                .testTag(PlaylistDetailTestTags.COVER),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                state.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${state.trackCount} tracks · ${state.downloadedCount} downloaded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Action bar with Play, Download, and Sync buttons, plus a hint when no tracks are downloaded.
 */
@Composable
private fun ActionBar(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
) {
    val hasTracks = state.trackCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = contract::playAll,
            enabled = state.downloadedCount > 0 && !state.isSyncing,
            modifier = Modifier
                .weight(1f)
                .testTag(PlaylistDetailTestTags.PLAY_ALL),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Play")
        }

        DownloadAllButton(
            isDownloading = state.isDownloadingAll,
            progress = state.downloadAllProgress,
            hasTracks = hasTracks,
            isSyncing = state.isSyncing,
            onDownloadAll = contract::downloadAll,
            modifier = Modifier.weight(1f),
        )

        OutlinedButton(
            onClick = contract::sync,
            enabled = !state.isSyncing && !state.isDownloadingAll,
            modifier = Modifier
                .weight(1f)
                .testTag(PlaylistDetailTestTags.RESYNC),
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(modifier = Modifier.width(4.dp))
            Text(if (state.isSyncing) "Syncing…" else "Sync")
        }
    }

    if (state.downloadedCount == 0 && state.trackCount > 0) {
        Text(
            text = "No downloaded tracks yet — download some to play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(PlaylistDetailTestTags.PLAY_ALL_HINT),
        )
    }
}

/**
 * Download all button that shows progress while downloading, or an outlined button when idle.
 */
@Composable
private fun DownloadAllButton(
    isDownloading: Boolean,
    progress: Int?,
    hasTracks: Boolean,
    isSyncing: Boolean,
    onDownloadAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isDownloading) {
        Button(
            onClick = {},
            enabled = false,
            modifier = modifier
                .testTag(PlaylistDetailTestTags.DOWNLOAD_ALL),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Downloading ${progress ?: 0}%")
                LinearProgressIndicator(
                    progress = { ((progress ?: 0) / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .testTag(PlaylistDetailTestTags.DOWNLOAD_PROGRESS),
                )
            }
        }
    } else {
        OutlinedButton(
            onClick = onDownloadAll,
            enabled = hasTracks && !isSyncing,
            modifier = modifier
                .testTag(PlaylistDetailTestTags.DOWNLOAD_ALL),
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Download")
        }
    }
}

/**
 * Scrollable list of tracks in the playlist.
 */
@Composable
private fun TrackList(
    tracks: List<TrackUi>,
    onPlaySong: (String) -> Unit,
    onDownloadSong: (String) -> Unit,
    onRetrySong: (String) -> Unit,
    highlightSongId: String? = null,
) {
    val listState = rememberLazyListState()
    var highlightedSongId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tracks, highlightSongId) {
        if (highlightSongId != null && tracks.isNotEmpty()) {
            val index = tracks.indexOfFirst { it.id == highlightSongId }
            if (index >= 0) {
                listState.scrollToItem(index)
                highlightedSongId = highlightSongId
                delay(HIGHLIGHT_DURATION_MS)
                highlightedSongId = null
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(PlaylistDetailTestTags.TRACK_LIST),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(tracks, key = { it.id }, contentType = { "track" }) { track ->
            TrackRow(
                track = track,
                isHighlighted = track.id == highlightedSongId,
                onPlay = { onPlaySong(track.id) },
                onDownload = { onDownloadSong(track.id) },
                onRetry = { onRetrySong(track.id) },
            )
        }
    }
}

/**
 * A single track row showing title, artists, duration, and status. Tap action depends on
 * the download status: play if downloaded, download if pending, retry if failed.
 */
@Composable
private fun TrackRow(
    track: TrackUi,
    isHighlighted: Boolean = false,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    val isFailed = track.status == SongStatus.FAILED
    val isDownloaded = track.status == SongStatus.DOWNLOADED
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val animatedBackground by animateColorAsState(
        targetValue = if (isHighlighted) highlightColor else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "highlight",
    )
    val rowBackground =
        if (isFailed) MaterialTheme.colorScheme.errorContainer else animatedBackground

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .testTag(PlaylistDetailTestTags.track(track.id))
            .clickable {
                when {
                    isDownloaded -> onPlay()
                    isFailed -> onRetry()
                    else -> onDownload()
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackInfoColumn(track = track, isFailed = isFailed, isDownloaded = isDownloaded, modifier = Modifier.weight(1f))

        Text(
            text = DurationFormat.format(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.width(8.dp))

        TrackStatusIcon(status = track.status)
    }
}

/**
 * Track title, artists, and contextual status hint (failed / tap to download / tap to play).
 */
@Composable
private fun TrackInfoColumn(
    track: TrackUi,
    isFailed: Boolean,
    isDownloaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = track.artists,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            isFailed -> Text(
                text = "Failed — tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            !isDownloaded -> Text(
                text = "Tap to download",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Text(
                text = "Tap to play from here",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Icon representing the current download status of a track.
 */
@Composable
private fun TrackStatusIcon(status: SongStatus) {
    Box(
        modifier = Modifier
            .size(TRACK_STATUS_BOX_SIZE)
            .testTag(PlaylistDetailTestTags.trackStatus(status)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SongStatus.DOWNLOADED -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(STATUS_ICON_SIZE),
            )
            SongStatus.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(STATUS_ICON_SIZE))
            SongStatus.QUEUED -> Icon(
                Icons.Filled.HourglassEmpty,
                contentDescription = "Queued",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(STATUS_ICON_SIZE),
            )
            SongStatus.PENDING -> Icon(
                Icons.Filled.Download,
                contentDescription = "Pending",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(STATUS_ICON_SIZE),
            )
            SongStatus.FAILED -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(STATUS_ICON_SIZE),
            )
        }
    }
}
