package xyz.botolog.ghostify.ui.playlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract.PlaylistDetailUiState
import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.util.DurationFormat
import xyz.botolog.ghostify.ui.util.InAppSnackbarHost
import xyz.botolog.ghostify.ui.util.InAppSnackbarState
import xyz.botolog.ghostify.ui.util.InAppSnackbarTestTags
import xyz.botolog.ghostify.ui.util.coverImageModel
import xyz.botolog.ghostify.ui.util.rememberInAppSnackbarState
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
    const val SORT = "detail_sort"
    const val SORT_SHEET = "detail_sort_sheet"
    const val SORT_SHEET_CONTENT = "detail_sort_sheet_content"
    const val SORT_DIRECTION = "detail_sort_direction"
    const val SORT_DIRECTION_SWITCH = "detail_sort_direction_switch"
    const val QUEUE_FEEDBACK = InAppSnackbarTestTags.MESSAGE

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

    fun sortOption(option: PlaylistSortOption) = "detail_sort_${option.name.lowercase()}"
}

private val COVER_SIZE = 72.dp
private val COVER_SIZE_COLLAPSED = 36.dp
private val COLLAPSE_THRESHOLD = 200
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
    val sortMode by contract.sortMode.collectAsState()
    val context = LocalContext.current
    val snackbarState = rememberInAppSnackbarState()
    var showMenu by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var draftSortOptionName by rememberSaveable {
        mutableStateOf(sortMode.option.name)
    }
    var draftSortDescending by rememberSaveable { mutableStateOf(sortMode.descending) }
    val draftSortOption = remember(draftSortOptionName) {
        PlaylistSortOption.entries.firstOrNull { it.name == draftSortOptionName }
            ?: sortMode.option
    }

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

    if (showSortSheet) {
        PlaylistSortBottomSheet(
            selectedOption = draftSortOption,
            descending = draftSortDescending,
            onOptionSelected = { draftSortOptionName = it.name },
            onDescendingChanged = { draftSortDescending = it },
            onDismiss = {
                contract.commitSort(
                    PlaylistSortSpec(option = draftSortOption, descending = draftSortDescending),
                )
                showSortSheet = false
            },
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
                            text = { Text("Sort") },
                            onClick = {
                                showMenu = false
                                draftSortOptionName = sortMode.option.name
                                draftSortDescending = sortMode.descending
                                showSortSheet = true
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.SwapVert, contentDescription = null)
                            },
                            modifier = Modifier.testTag(PlaylistDetailTestTags.SORT),
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
        Box(modifier = Modifier.fillMaxSize()) {
            PlaylistDetailContent(
                state = state,
                contract = contract,
                padding = padding,
                highlightSongId = highlightSongId,
                snackbarState = snackbarState,
            )
            InAppSnackbarHost(
                state = snackbarState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
    snackbarState: InAppSnackbarState? = null,
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
            else -> PlaylistContent(
                state = state,
                contract = contract,
                highlightSongId = highlightSongId,
                snackbarState = snackbarState,
            )
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
 * Full playlist content: collapsing header with cover art and buttons, optional syncing indicator, and track list.
 */
@Composable
private fun PlaylistContent(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
    highlightSongId: String? = null,
    snackbarState: InAppSnackbarState? = null,
) {
    val listState = rememberLazyListState()
    // Tracks render in the order stored in the database: a committed sort rewrites
    // `songs.position`, so the list is never re-ordered locally.
    val tracks = state.tracks

    val collapseProgress by remember {
        derivedStateOf {
            val scrollInfo = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            if (scrollInfo.first == 0) {
                (scrollInfo.second.toFloat() / COLLAPSE_THRESHOLD).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }

    val coverSize by animateDpAsState(
        targetValue = COVER_SIZE * (1f - collapseProgress) + COVER_SIZE_COLLAPSED * collapseProgress,
        animationSpec = tween(durationMillis = 50),
        label = "coverSize",
    )

    val titleScale by remember {
        derivedStateOf { 1f - (1f - 16f / 22f) * collapseProgress }
    }

    val showSubtitle by remember {
        derivedStateOf { collapseProgress < 0.5f }
    }

    val showCollapsed by remember {
        derivedStateOf { collapseProgress > 0.8f }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(PlaylistDetailTestTags.TRACK_LIST),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header") {
                ExpandedHeader(
                    state = state,
                    contract = contract,
                    coverSize = coverSize,
                    titleScale = titleScale,
                    showSubtitle = showSubtitle,
                )
            }
            item(key = "syncing") {
                SyncingIndicator(isSyncing = state.isSyncing)
            }
            items(
                tracks,
                key = { it.id },
                contentType = { "track_${it.status}" },
            ) { track ->
                SwipeToAddTrackRow(
                    onAddToQueue = {
                        contract.addToQueue(track.id)
                        snackbarState?.showAddedToQueue(track.title)
                    },
                    onClick = {
                        when {
                            track.status == SongStatus.DOWNLOADED -> contract.playFromSong(track.id)
                            track.status == SongStatus.FAILED -> contract.retryTrack(track.id)
                            else -> contract.downloadSong(track.id)
                        }
                    },
                    enabled = track.status == SongStatus.DOWNLOADED,
                ) {
                    TrackRow(
                        track = track,
                        isHighlighted = track.id == highlightSongId,
                    )
                }
            }
        }

        if (showCollapsed) {
            CollapsedBar(
                state = state,
                contract = contract,
                collapseProgress = collapseProgress,
            )
        }
    }
}

/**
 * The playlist artwork, used by both the expanded header and the collapsed bar.
 *
 * The model is resolved by [coverImageModel], so an extracted local cover is preferred
 * but never shadows the remote URL when the local file is gone, and the resolution is
 * remembered because this composable recomposes on every frame while the header collapses.
 * A neutral placeholder keeps the artwork slot visible when neither source is usable.
 *
 * @param state the playlist state carrying the cover sources.
 * @param coverSize the rendered edge length of the square artwork.
 * @param cornerRadius the artwork corner radius.
 * @param contentDescription accessibility label, or `null` for a decorative image.
 * @param modifier optional modifier applied to the artwork.
 */
@Composable
private fun PlaylistCoverImage(
    state: PlaylistDetailUiState,
    coverSize: Dp,
    cornerRadius: Dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val model = remember(state.coverArtLocalPath, state.coverUrl) {
        coverImageModel(state.coverArtLocalPath, state.coverUrl)
    }
    val empty = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier
            .size(coverSize)
            .clip(RoundedCornerShape(cornerRadius)),
        contentScale = ContentScale.Crop,
        placeholder = empty,
        error = empty,
        fallback = empty,
    )
}

/**
 * Expanded header shown when scrolled to top: cover art, title, subtitle, and horizontal buttons.
 */
@Composable
private fun ExpandedHeader(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
    coverSize: androidx.compose.ui.unit.Dp,
    titleScale: Float,
    showSubtitle: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCoverImage(
                state = state,
                coverSize = coverSize,
                cornerRadius = 8.dp,
                contentDescription = "Playlist cover",
                modifier = Modifier.testTag(PlaylistDetailTestTags.COVER),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.name,
                    modifier = Modifier.graphicsLayer {
                        scaleX = titleScale
                        scaleY = titleScale
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = showSubtitle) {
                    Text(
                        text = "${state.trackCount} tracks \u00b7 ${state.downloadedCount} downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularPlayButton(
                enabled = state.downloadedCount > 0 && !state.isSyncing,
                onClick = contract::playAll,
            )
            CircularDownloadButton(
                isDownloading = state.isDownloadingAll,
                progress = state.downloadAllProgress,
                enabled = state.trackCount > 0 && !state.isSyncing,
                onClick = contract::downloadAll,
            )
            CircularSyncButton(
                isSyncing = state.isSyncing,
                enabled = !state.isDownloadingAll,
                onClick = contract::sync,
            )
        }
    }
}

@Composable
private fun CircularPlayButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .testTag(PlaylistDetailTestTags.PLAY_ALL),
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Play all",
            tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CircularDownloadButton(
    isDownloading: Boolean,
    progress: Int?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .testTag(PlaylistDetailTestTags.DOWNLOAD_ALL),
    ) {
        if (isDownloading) {
            CircularProgressIndicator(
                progress = { ((progress ?: 0) / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 4.dp,
                strokeCap = StrokeCap.Round,
            )
            Text(
                text = "${progress ?: 0}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "Download all",
                    tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun CircularSyncButton(isSyncing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp),
    ) {
        if (isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .testTag(PlaylistDetailTestTags.SYNCING),
                strokeWidth = 3.dp,
            )
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                    .testTag(PlaylistDetailTestTags.RESYNC),
            ) {
                Icon(
                    Icons.Filled.Sync,
                    contentDescription = "Sync",
                    tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * Collapsed bar shown at top when scrolled down: small cover art, title, and horizontal buttons.
 */
@Composable
private fun CollapsedBar(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
    collapseProgress: Float,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistCoverImage(
                state = state,
                coverSize = COVER_SIZE_COLLAPSED,
                cornerRadius = 4.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = contract::playAll,
                enabled = state.downloadedCount > 0 && !state.isSyncing,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play all",
                    modifier = Modifier.size(20.dp),
                )
            }

            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                if (state.isDownloadingAll) {
                    CircularProgressIndicator(
                        progress = { ((state.downloadAllProgress ?: 0) / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Text(
                        text = "${state.downloadAllProgress ?: 0}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    IconButton(
                        onClick = contract::downloadAll,
                        enabled = state.trackCount > 0 && !state.isSyncing,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Download all",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            IconButton(
                onClick = contract::sync,
                enabled = !state.isSyncing && !state.isDownloadingAll,
                modifier = Modifier.size(36.dp),
            ) {
                if (state.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = "Sync",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
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
 * A single track row showing title, artists, duration, and status. Tap action depends on
 * the download status: play if downloaded, download if pending, retry if failed.
 */
@Composable
private fun TrackRow(
    track: TrackUi,
    isHighlighted: Boolean = false,
) {
    val isFailed = track.status == SongStatus.FAILED
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = remember(track.coverArtLocalPath, track.coverUrl) {
                coverImageModel(track.coverArtLocalPath, track.coverUrl)
            },
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )

        Spacer(modifier = Modifier.width(12.dp))

        TrackInfoColumn(track = track, isFailed = isFailed, modifier = Modifier.weight(1f))

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
 * Track title, artists, and a contextual status hint.
 *
 * Downloaded rows show no hint at all — the check icon and the row tap already say the
 * track is ready — which leaves the remaining text vertically centred against the
 * artwork, the duration and the status icon.
 *
 * @param track the track to describe.
 * @param isFailed whether the track's last download attempt failed.
 * @param modifier optional modifier applied to the text column.
 */
@Composable
private fun TrackInfoColumn(
    track: TrackUi,
    isFailed: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
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
            track.status != SongStatus.DOWNLOADED -> Text(
                text = "Tap to download",
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
