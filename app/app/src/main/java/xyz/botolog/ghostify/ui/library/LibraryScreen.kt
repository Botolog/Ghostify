package xyz.botolog.ghostify.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.LibraryContract
import xyz.botolog.ghostify.ui.contract.LibraryContract.LibraryUiState
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.ui.model.PlaylistUi
import xyz.botolog.ghostify.ui.util.ProgressBadge
import xyz.botolog.ghostify.ui.util.TimeFormat
import kotlinx.coroutines.launch

object LibraryTestTags {
    const val LIST = "library_list"
    const val EMPTY = "library_empty"
    const val FAB = "library_fab"
    const val SETTINGS = "library_settings"
    const val SHUTDOWN = "library_shutdown"
    const val COVER = "playlist_cover"
    const val NAME = "playlist_name"
    const val COUNT = "playlist_count"
    const val BADGE = "playlist_badge"
    const val SYNC_TIME = "playlist_sync_time"

    fun item(playlistId: String) = "playlist_item_$playlistId"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    contract: LibraryContract,
    onOpenPlaylist: (String) -> Unit,
    onOpenSettings: () -> Unit,
    addDialog: @Composable () -> Unit = {},
    now: () -> Long = { System.currentTimeMillis() },
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    var showShutdownDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    IconButton(
                        onClick = { showShutdownDialog = true },
                        modifier = Modifier.testTag(LibraryTestTags.SHUTDOWN),
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Shutdown")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            contract.onOpenSettings()
                            onOpenSettings()
                        },
                        modifier = Modifier.testTag(LibraryTestTags.SETTINGS),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = contract::onAddClick,
                modifier = Modifier.testTag(LibraryTestTags.FAB),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add playlist")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.playlists.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.playlists.isEmpty() -> EmptyLibrary()

                else -> PlaylistList(
                    state = state,
                    contract = contract,
                    onOpenPlaylist = onOpenPlaylist,
                    now = now,
                )
            }
        }
    }

    if (state.isAddDialogOpen) {
        addDialog()
    }

    if (showShutdownDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            title = { Text("Shutdown") },
            text = { Text("Close Ghostify completely? Playback will stop.") },
            confirmButton = {
                TextButton(onClick = {
                    showShutdownDialog = false
                    contract.shutdownApp()
                }) {
                    Text("Shutdown")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(LibraryTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No playlists yet",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap + to add your first Spotify playlist.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistList(
    state: LibraryUiState,
    contract: LibraryContract,
    onOpenPlaylist: (String) -> Unit,
    now: () -> Long,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(LibraryTestTags.LIST),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        items(state.playlists, key = { it.id }) { playlist ->
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) {
                        contract.deletePlaylist(playlist.id)
                        true
                    } else false
                }
            )

            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                enableDismissFromStartToEnd = false,
            ) {
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenPlaylist(playlist.id) },
                    onMoveUp = {
                        val idx = state.playlists.indexOfFirst { it.id == playlist.id }
                        if (idx > 0) {
                            val above = state.playlists[idx - 1]
                            contract.reorderPlaylist(playlist.id, idx - 1)
                            contract.reorderPlaylist(above.id, idx)
                        }
                    },
                    onMoveDown = {
                        val idx = state.playlists.indexOfFirst { it.id == playlist.id }
                        if (idx < state.playlists.lastIndex) {
                            val below = state.playlists[idx + 1]
                            contract.reorderPlaylist(playlist.id, idx + 1)
                            contract.reorderPlaylist(below.id, idx)
                        }
                    },
                    isFirst = state.playlists.firstOrNull()?.id == playlist.id,
                    isLast = state.playlists.lastOrNull()?.id == playlist.id,
                    now = now,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistUi,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
    now: () -> Long,
) {
    var showMenu by remember { mutableStateOf(false) }

    val originTint = when (playlist.origin) {
        PlaylistOrigin.SPOTIFY -> Color(0x0834E876)
        PlaylistOrigin.YOUTUBE -> Color(0x08E53935)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(originTint)
            .testTag(LibraryTestTags.item(playlist.id))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showMenu = true },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = playlist.coverUrl,
                contentDescription = "Cover of ${playlist.name}",
                modifier = Modifier
                    .size(56.dp)
                    .testTag(LibraryTestTags.COVER),
                contentScale = ContentScale.Crop,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(LibraryTestTags.NAME),
            )
            Text(
                text = "${playlist.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(LibraryTestTags.COUNT),
            )
            Text(
                text = "Synced ${TimeFormat.formatRelative(playlist.lastSyncedAt, now())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.testTag(LibraryTestTags.SYNC_TIME),
            )
        }

        DownloadBadge(playlist = playlist)

        Spacer(modifier = Modifier.width(4.dp))

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Move up") },
                    onClick = { showMenu = false; onMoveUp() },
                    enabled = !isFirst,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Move down") },
                    onClick = { showMenu = false; onMoveDown() },
                    enabled = !isLast,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun DownloadBadge(playlist: PlaylistUi) {
    val badge = ProgressBadge.progressToBadge(
        inFlight = playlist.status == xyz.botolog.ghostify.ui.model.PlaylistStatus.DOWNLOADING,
        progressPercent = playlist.progressPercent,
        downloaded = playlist.downloadedCount,
        total = playlist.trackCount,
    ) ?: return

    Column(
        modifier = Modifier.testTag(LibraryTestTags.BADGE),
        horizontalAlignment = Alignment.End,
    ) {
        if (playlist.status == xyz.botolog.ghostify.ui.model.PlaylistStatus.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { badge.percent / 100f },
                modifier = Modifier
                    .width(72.dp)
                    .height(6.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge.percent == 100) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = badge.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
