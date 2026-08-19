package com.ghostify.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghostify.ui.contract.LibraryContract
import com.ghostify.ui.contract.LibraryContract.LibraryUiState
import com.ghostify.ui.model.PlaylistOrigin
import com.ghostify.ui.model.PlaylistUi
import com.ghostify.ui.util.ProgressBadge
import com.ghostify.ui.util.TimeFormat

object LibraryTestTags {
    const val LIST = "library_list"
    const val EMPTY = "library_empty"
    const val FAB = "library_fab"
    const val SETTINGS = "library_settings"
    const val COVER = "playlist_cover"
    const val NAME = "playlist_name"
    const val COUNT = "playlist_count"
    const val BADGE = "playlist_badge"
    const val SYNC_TIME = "playlist_sync_time"

    fun item(playlistId: String) = "playlist_item_$playlistId"
}

/**
 * Library home screen.
 *
 * @param onOpenPlaylist navigation callback — wired to the NavController by the app root.
 * @param addDialog slot rendered whenever [LibraryUiState.isAddDialogOpen] is true; the app
 *   root supplies the real [com.ghostify.ui.add.AddPlaylistDialog], tests supply a fake host.
 * @param now injected clock for deterministic "last synced" rendering in tests.
 */
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Library") },
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

                else -> PlaylistList(state = state, onOpenPlaylist = onOpenPlaylist, now = now)
            }
        }
    }

    if (state.isAddDialogOpen) {
        addDialog()
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

@Composable
private fun PlaylistList(
    state: LibraryUiState,
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
            PlaylistRow(
                playlist = playlist,
                onClick = { onOpenPlaylist(playlist.id) },
                now = now,
            )
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistUi,
    onClick: () -> Unit,
    now: () -> Long,
) {
    val originTint = when (playlist.origin) {
        PlaylistOrigin.SPOTIFY -> Color(0x0834E876)
        PlaylistOrigin.YOUTUBE -> Color(0x08E53935)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(originTint)
            .testTag(LibraryTestTags.item(playlist.id))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LibraryTestTags.item(playlist.id))
            .clickable(onClick = onClick)
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
    }
}

@Composable
private fun DownloadBadge(playlist: PlaylistUi) {
    val badge = ProgressBadge.progressToBadge(
        inFlight = playlist.status == com.ghostify.ui.model.PlaylistStatus.DOWNLOADING,
        progressPercent = playlist.progressPercent,
        downloaded = playlist.downloadedCount,
        total = playlist.trackCount,
    ) ?: return

    Column(
        modifier = Modifier.testTag(LibraryTestTags.BADGE),
        horizontalAlignment = Alignment.End,
    ) {
        if (playlist.status == com.ghostify.ui.model.PlaylistStatus.DOWNLOADING) {
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
