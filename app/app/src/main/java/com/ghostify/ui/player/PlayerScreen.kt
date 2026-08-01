package com.ghostify.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghostify.ui.contract.PlayerContract
import com.ghostify.ui.contract.PlayerContract.PlayerUiState
import com.ghostify.ui.model.QueueItem
import com.ghostify.ui.util.DurationFormat
import com.ghostify.ui.util.RepeatMode

object PlayerTestTags {
    const val COVER = "player_cover"
    const val TITLE = "player_title"
    const val ARTIST = "player_artist"
    const val ALBUM = "player_album"
    const val SEEK_BAR = "player_seek_bar"
    const val POSITION_LABEL = "player_position"
    const val PREV = "player_prev"
    const val PLAY = "player_play"
    const val NEXT = "player_next"
    const val SHUFFLE = "player_shuffle"
    const val REPEAT = "player_repeat"
    const val REPEAT_LABEL = "player_repeat_label"
    const val VOLUME_SLIDER = "player_volume"
    const val VOLUME_LABEL = "player_volume_label"
    const val QUEUE_BUTTON = "player_queue_button"
    const val QUEUE_SHEET = "queue_sheet"
    const val EMPTY = "player_empty"

    fun queueItem(index: Int) = "queue_item_$index"
}

@Composable
fun PlayerScreen(
    contract: PlayerContract,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()

    if (state.empty) {
        EmptyPlayer(modifier = modifier)
        return
    }

    PlayerContent(state = state, contract = contract, modifier = modifier)
}

@Composable
private fun EmptyPlayer(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(PlayerTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Open a playlist and play a downloaded track.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerContent(
    state: PlayerUiState,
    contract: PlayerContract,
    modifier: Modifier,
) {
    val nowPlaying = state.nowPlaying
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        AsyncImage(
            model = nowPlaying?.coverUrl,
            contentDescription = "Album art",
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .testTag(PlayerTestTags.COVER),
            contentScale = ContentScale.Crop,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = nowPlaying?.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(PlayerTestTags.TITLE),
        )
        Text(
            text = nowPlaying?.artist.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(PlayerTestTags.ARTIST),
        )
        Text(
            text = nowPlaying?.album.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(PlayerTestTags.ALBUM),
        )

        Spacer(modifier = Modifier.height(16.dp))

        SeekBar(state = state, contract = contract)

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = DurationFormat.format(state.positionMs),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(PlayerTestTags.POSITION_LABEL),
            )
            Text(
                text = DurationFormat.format(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TransportControls(state = state, contract = contract)

        Spacer(modifier = Modifier.height(8.dp))

        VolumeControl(state = state, contract = contract)

        Spacer(modifier = Modifier.height(8.dp))

        IconButton(
            onClick = contract::toggleQueue,
            modifier = Modifier.testTag(PlayerTestTags.QUEUE_BUTTON),
        ) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
        }
    }

    if (state.queueOpen) {
        QueueSheet(contract = contract, queue = state.queue, onDismiss = contract::closeQueue)
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, contract: PlayerContract) {
    val maxMs = if (state.durationMs > 0) state.durationMs else 1L
    Slider(
        value = state.positionMs.coerceIn(0L, maxMs).toFloat(),
        onValueChange = { contract.seekTo(it.toLong()) },
        valueRange = 0f..maxMs.toFloat(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PlayerTestTags.SEEK_BAR),
    )
}

@Composable
private fun TransportControls(state: PlayerUiState, contract: PlayerContract) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconToggleButton(
            checked = state.shuffle,
            onCheckedChange = { contract.toggleShuffle() },
            modifier = Modifier.testTag(PlayerTestTags.SHUFFLE),
        ) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = "Shuffle",
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = contract::previous,
            modifier = Modifier.testTag(PlayerTestTags.PREV),
        ) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
        }

        IconButton(
            onClick = contract::togglePlay,
            modifier = Modifier
                .size(72.dp)
                .testTag(PlayerTestTags.PLAY),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(48.dp),
            )
        }

        IconButton(
            onClick = contract::next,
            modifier = Modifier.testTag(PlayerTestTags.NEXT),
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
        }

        IconToggleButton(
            checked = state.repeatMode != RepeatMode.OFF,
            onCheckedChange = { contract.cycleRepeat() },
            modifier = Modifier.testTag(PlayerTestTags.REPEAT),
        ) {
            Icon(
                imageVector = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = "Repeat: ${state.repeatMode.label}",
                tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VolumeControl(state: PlayerUiState, contract: PlayerContract) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp))
        Slider(
            value = state.volume.coerceIn(0f, 1f),
            onValueChange = contract::setVolume,
            valueRange = 0f..1f,
            modifier = Modifier
                .weight(1f)
                .testTag(PlayerTestTags.VOLUME_SLIDER),
        )
        Text(
            text = "${(state.volume * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(PlayerTestTags.VOLUME_LABEL),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    contract: PlayerContract,
    queue: List<QueueItem>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(PlayerTestTags.QUEUE_SHEET),
    ) {
        Text(
            text = "Next up",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(queue) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PlayerTestTags.queueItem(index))
                        .clickable { contract.jumpToQueueIndex(index) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (item.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = DurationFormat.format(item.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
