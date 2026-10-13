@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.botolog.ghostify.ui.player

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.model.NowPlaying

/**
 * Test tag constants for the mini player. Used by Compose UI tests to locate elements.
 */
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

private val COVER_SIZE = 44.dp
private val BAR_HORIZONTAL_PADDING = 10.dp
private val BAR_VERTICAL_PADDING = 6.dp
private val SEEK_BAR_HEIGHT = 26.dp
private val SEEK_BAR_HORIZONTAL_PADDING = 14.dp
private val TRACK_HEIGHT = 4.dp
private val THUMB_SIZE = DpSize(4.dp, 4.dp)

/**
 * Asset URI for the empty-state cover (byte-identical copy of user-supplied test.svg,
 * relocated from res/drawable/ — AAPT does not support raw .svg in drawable/ — to
 * src/main/assets/no_song_cover.svg). Rendered via Coil + SvgDecoder (see
 * GhostifyApplication.newImageLoader).
 */
private const val EMPTY_COVER_ASSET_URI = "file:///android_asset/no_song_cover.svg"

/**
 * Spotify-style persistent now-playing bar rendered at the bottom of every screen.
 *
 * ALWAYS visible — even when nothing is loaded (fresh start, deleted playlist,
 * cleared queue, empty library). Empty state shows:
 * - title "No Song", subtitle "—",
 * - asset SVG [EMPTY_COVER_ASSET_URI] (user-supplied test.svg, byte-identical),
 * - prev/play-pause/next disabled with reduced alpha,
 * - shuffle toggle always enabled (persists for future [playPlaylist] randomization).
 *
 * Compact controls:
 * - a draggable progress bar to change the current timestamp ([PlayerContract.seekTo]);
 * - previous / play-pause / next transport;
 * - a shuffle toggle. Shuffle only reorders tracks within the current queue, which the
 *   player builds from a single playlist's DOWNLOADED songs — so it always stays in
 *   playlist bounds.
 *
 * @param contract ViewModel contract driving the player state and actions.
 * @param modifier optional modifier applied to the surface root.
 */
@Composable
fun MiniPlayer(
    contract: PlayerContract,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()
    val nowPlaying = state.nowPlaying
    val isEmpty = state.empty || nowPlaying == null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MiniPlayerTestTags.BAR),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            MiniSeekBar(state = state, contract = contract, isEmpty = isEmpty)
            MiniPlayerControls(
                state = state,
                contract = contract,
                nowPlaying = nowPlaying,
                isEmpty = isEmpty,
            )
        }
    }
}

/**
 * Row of mini player controls: cover art, title/artist, shuffle, and transport buttons.
 *
 * Empty state ([isEmpty]) shows "No Song" / "—" with the asset SVG
 * [EMPTY_COVER_ASSET_URI] (single asset, NO Compose Box overlay). Transport is disabled + dimmed; shuffle
 * stays enabled so its flag can seed the next queue build.
 */
@Composable
private fun MiniPlayerControls(
    state: PlayerUiState,
    contract: PlayerContract,
    nowPlaying: NowPlaying?,
    isEmpty: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BAR_HORIZONTAL_PADDING, vertical = BAR_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Coil painter for the SVG asset — used as placeholder/error/fallback so every
        // cover state (loading, error, null URL) renders the same user-supplied SVG,
        // never the old XML drawable.
        val emptyCoverPainter = rememberAsyncImagePainter(EMPTY_COVER_ASSET_URI)
        AsyncImage(
            model = if (isEmpty) EMPTY_COVER_ASSET_URI else (nowPlaying?.coverUrl ?: EMPTY_COVER_ASSET_URI),
            contentDescription = "Album art",
            modifier = Modifier
                .size(COVER_SIZE)
                .clip(RoundedCornerShape(6.dp))
                .testTag(MiniPlayerTestTags.COVER),
            contentScale = ContentScale.Crop,
            placeholder = emptyCoverPainter,
            error = emptyCoverPainter,
            fallback = emptyCoverPainter,
        )

        Spacer(modifier = Modifier.width(10.dp))

        MiniTrackInfo(
            title = if (isEmpty) "No Song" else nowPlaying?.title.orEmpty(),
            artist = if (isEmpty) "—" else nowPlaying?.artist.orEmpty(),
            modifier = Modifier.weight(1f),
        )

        MiniShuffleToggle(
            isChecked = state.shuffle,
            onToggle = contract::toggleShuffle,
            enabled = true,
        )

        MiniTransportControls(
            contract = contract,
            isPlaying = state.isPlaying,
            enabled = !isEmpty,
        )
    }
}

/**
 * Displays the now-playing track title and artist name.
 */
@Composable
private fun MiniTrackInfo(title: String, artist: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(MiniPlayerTestTags.TITLE),
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(MiniPlayerTestTags.ARTIST),
        )
    }
}

/**
 * Shuffle toggle button for the mini player. Always enabled — even in empty state it
 * persists its flag for the next queue build.
 */
@Composable
private fun MiniShuffleToggle(
    isChecked: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    IconToggleButton(
        checked = isChecked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        modifier = Modifier.testTag(MiniPlayerTestTags.SHUFFLE),
    ) {
        Icon(
            imageVector = Icons.Filled.Shuffle,
            contentDescription = "Shuffle",
            tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Transport controls (previous, play/pause, next) for the mini player.
 *
 * Disabled + dimmed in empty state ([enabled=false]).
 */
@Composable
private fun MiniTransportControls(
    contract: PlayerContract,
    isPlaying: Boolean,
    enabled: Boolean = true,
) {
    val disabledAlpha = 0.38f
    IconButton(
        onClick = contract::previous,
        enabled = enabled,
        modifier = Modifier
            .testTag(MiniPlayerTestTags.PREV)
            .alpha(if (enabled) 1f else disabledAlpha),
    ) {
        Icon(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "Previous",
            modifier = Modifier.size(28.dp),
        )
    }

    IconButton(
        onClick = contract::togglePlay,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .testTag(MiniPlayerTestTags.PLAY)
            .alpha(if (enabled) 1f else disabledAlpha),
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            modifier = Modifier.size(32.dp),
        )
    }

    IconButton(
        onClick = contract::next,
        enabled = enabled,
        modifier = Modifier
            .testTag(MiniPlayerTestTags.NEXT)
            .alpha(if (enabled) 1f else disabledAlpha),
    ) {
        Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "Next",
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * Thin seek strip across the top of the bar: a draggable slider when the duration is
 * known, an indeterminate progress line otherwise (duration not yet resolved).
 *
 * Disabled (non-functional, dimmed) in empty state — no song to seek.
 */
@Composable
private fun MiniSeekBar(state: PlayerUiState, contract: PlayerContract, isEmpty: Boolean = false) {
    if (isEmpty) {
        Slider(
            value = 0f,
            onValueChange = {},
            valueRange = 0f..1f,
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(SEEK_BAR_HEIGHT)
                .padding(horizontal = SEEK_BAR_HORIZONTAL_PADDING)
                .alpha(0.38f)
                .testTag(MiniPlayerTestTags.SEEK),
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(TRACK_HEIGHT),
                    thumbTrackGapSize = 0.dp,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    ),
                )
            },
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = remember { MutableInteractionSource() },
                    thumbSize = THUMB_SIZE,
                )
            },
        )
        return
    }
    if (state.durationMs > 0) {
        val maxMs = state.durationMs
        val interactionSource = remember { MutableInteractionSource() }

        var isDragging by remember { mutableStateOf(false) }
        var dragPosition by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        isDragging = true
                        dragPosition = state.positionMs.coerceIn(0L, maxMs).toFloat()
                    }
                    is PressInteraction.Release,
                    is PressInteraction.Cancel -> {
                        isDragging = false
                    }
                }
            }
        }

        val displayPosition = if (isDragging) {
            dragPosition
        } else {
            state.positionMs.coerceIn(0L, maxMs).toFloat()
        }

        Slider(
            value = displayPosition,
            onValueChange = { value ->
                isDragging = true
                dragPosition = value
            },
            onValueChangeFinished = {
                contract.seekTo(dragPosition.toLong())
                isDragging = false
            },
            valueRange = 0f..maxMs.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(SEEK_BAR_HEIGHT)
                .padding(horizontal = SEEK_BAR_HORIZONTAL_PADDING)
                .testTag(MiniPlayerTestTags.SEEK),
            interactionSource = interactionSource,
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(TRACK_HEIGHT),
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
                    thumbSize = THUMB_SIZE,
                )
            },
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
