@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.botolog.ghostify.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState

object MiniPlayerTestTags {
    const val BAR = "mini_player"
    const val COVER = "mini_cover"
    const val TITLE = "mini_title"
    const val ARTIST = "mini_artist"
    const val SEEK = "mini_seek"
    const val PREV = "mini_prev"
    const val PLAY = "mini_play"
    const val NEXT = "mini_next"
    const val SHUFFLE = "mini_shuffle"
}

/**
 * Spotify-style persistent now-playing bar rendered at the bottom of every screen
 * while something is loaded.
 *
 * Compact controls:
 * - a draggable progress bar to change the current timestamp ([PlayerContract.seekTo]);
 * - previous / play-pause / next transport;
 * - a shuffle toggle. Shuffle only reorders tracks within the current queue, which the
 *   player builds from a single playlist's DOWNLOADED songs — so it always stays in
 *   playlist bounds.
 *
 * Renders nothing when [PlayerUiState.empty] is true (nothing loaded).
 */
@Composable
fun MiniPlayer(
    contract: PlayerContract,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    val nowPlaying = state.nowPlaying
    if (state.empty || nowPlaying == null) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MiniPlayerTestTags.BAR),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            MiniSeekBar(state = state, contract = contract)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = nowPlaying.coverUrl,
                    contentDescription = "Album art",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .testTag(MiniPlayerTestTags.COVER),
                    contentScale = ContentScale.Crop,
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nowPlaying.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(MiniPlayerTestTags.TITLE),
                    )
                    Text(
                        text = nowPlaying.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(MiniPlayerTestTags.ARTIST),
                    )
                }

                IconToggleButton(
                    checked = state.shuffle,
                    onCheckedChange = { contract.toggleShuffle() },
                    modifier = Modifier.testTag(MiniPlayerTestTags.SHUFFLE),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }

                IconButton(
                    onClick = contract::previous,
                    modifier = Modifier.testTag(MiniPlayerTestTags.PREV),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(28.dp),
                    )
                }

                IconButton(
                    onClick = contract::togglePlay,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag(MiniPlayerTestTags.PLAY),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }

                IconButton(
                    onClick = contract::next,
                    modifier = Modifier.testTag(MiniPlayerTestTags.NEXT),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

/**
 * Thin seek strip across the top of the bar: a draggable slider when the duration is
 * known, an indeterminate progress line otherwise (duration not yet resolved).
 */
@Composable
private fun MiniSeekBar(state: PlayerUiState, contract: PlayerContract) {
    if (state.durationMs > 0) {
        val maxMs = state.durationMs
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Slider(
            value = state.positionMs.coerceIn(0L, maxMs).toFloat(),
            onValueChange = { contract.seekTo(it.toLong()) },
            valueRange = 0f..maxMs.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 14.dp)
                .testTag(MiniPlayerTestTags.SEEK),
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    thumbTrackGapSize = 0.dp,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    ),
                )
            },
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    thumbSize = DpSize(4.dp, 4.dp),
                )
            },
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
