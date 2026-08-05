package com.ghostify.ui.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.ghostify.ui.contract.PlaylistDetailContract
import com.ghostify.ui.contract.PlaylistDetailContract.PlaylistDetailUiState
import com.ghostify.ui.model.SongStatus
import com.ghostify.ui.model.TrackUi
import com.ghostify.ui.util.DurationFormat

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

    fun track(id: String) = "track_item_$id"
    fun trackStatus(status: SongStatus) = "track_status_$status"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    contract: PlaylistDetailContract,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()

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
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
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
                    message = state.error!!,
                    onRetry = contract::sync,
                )

                else -> PlaylistContent(state = state, contract = contract)
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(PlaylistDetailTestTags.ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun PlaylistContent(
    state: PlaylistDetailUiState,
    contract: PlaylistDetailContract,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PlaylistHeader(state = state)
        ActionBar(state = state, contract = contract)

        if (state.isSyncing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(PlaylistDetailTestTags.SYNCING),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Syncing with Spotify…", style = MaterialTheme.typography.bodySmall)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(PlaylistDetailTestTags.TRACK_LIST),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(state.tracks, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    onPlay = { contract.playFromSong(track.id) },
                    onDownload = { contract.downloadSong(track.id) },
                    onRetry = { contract.retryTrack(track.id) },
                )
            }
        }
    }
}

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
                .size(72.dp)
                .testTag(PlaylistDetailTestTags.COVER),
            contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(state.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${state.trackCount} tracks · ${state.downloadedCount} downloaded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = contract::playAll,
            enabled = state.downloadedCount > 0 && !state.isSyncing,
            modifier = Modifier
                .weight(1f)
                .testTag(PlaylistDetailTestTags.PLAY_ALL),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Play")
        }

        if (state.isDownloadingAll) {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PlaylistDetailTestTags.DOWNLOAD_ALL),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Downloading ${state.downloadAllProgress ?: 0}%")
                    LinearProgressIndicator(
                        progress = { ((state.downloadAllProgress ?: 0) / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .testTag(PlaylistDetailTestTags.DOWNLOAD_PROGRESS),
                    )
                }
            }
        } else {
            OutlinedButton(
                onClick = contract::downloadAll,
                enabled = hasTracks && !state.isSyncing,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PlaylistDetailTestTags.DOWNLOAD_ALL),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Download")
            }
        }

        OutlinedButton(
            onClick = contract::sync,
            enabled = !state.isSyncing && !state.isDownloadingAll,
            modifier = Modifier
                .weight(1f)
                .testTag(PlaylistDetailTestTags.RESYNC),
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackUi,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    val isFailed = track.status == SongStatus.FAILED
    val isDownloaded = track.status == SongStatus.DOWNLOADED
    val rowBackground =
        if (isFailed) MaterialTheme.colorScheme.errorContainer else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .testTag(PlaylistDetailTestTags.track(track.id))
            .combinedClickable(
                onClick = {
                    when {
                        isDownloaded -> onPlay()
                        isFailed -> onRetry()
                        else -> onDownload()
                    }
                },
                onLongClick = onRetry,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
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

        Text(
            text = DurationFormat.format(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.width(8.dp))

        TrackStatusIcon(status = track.status)
    }
}

@Composable
private fun TrackStatusIcon(status: SongStatus) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .testTag(PlaylistDetailTestTags.trackStatus(status)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SongStatus.DOWNLOADED -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )

            SongStatus.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(20.dp))

            SongStatus.QUEUED -> Icon(
                Icons.Filled.HourglassEmpty,
                contentDescription = "Queued",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )

            SongStatus.PENDING -> Icon(
                Icons.Filled.Download,
                contentDescription = "Pending",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )

            SongStatus.FAILED -> Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Failed",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
