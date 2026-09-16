@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.botolog.ghostify.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.util.DurationFormat
import kotlin.math.abs

// ── Dimensions ────────────────────────────────────────────────────────
private val ARTWORK_HEIGHT = 280.dp
private val HORIZONTAL_PADDING = 24.dp
private val PLAY_BUTTON_SIZE = 64.dp
private val PLAY_ICON_SIZE = 40.dp
private val TRANSPORT_ICON_SIZE = 32.dp
private val LYRICS_MINI_HEIGHT = 100.dp
private val LYRICS_LINE_HEIGHT = 22.sp

// ── LRC timestamp parsing ─────────────────────────────────────────────

/**
 * A single parsed LRC line with its timestamp in milliseconds.
 */
private data class LrcLine(val timeMs: Long, val text: String)

/**
 * Parses LRC-format lyrics into timestamped lines.
 */
private fun parseLrc(lrc: String): List<LrcLine> {
    val regex = Regex("""^\[(\d{2}):(\d{2})\.(\d{2})\](.*)""")
    return lrc.lines().mapNotNull { line ->
        val m = regex.matchEntire(line.trim()) ?: return@mapNotNull null
        val min = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val sec = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        val cs = m.groupValues[3].toLongOrNull() ?: return@mapNotNull null
        val text = m.groupValues[4].trim()
        if (text.isEmpty()) return@mapNotNull null
        LrcLine(timeMs = min * 60_000 + sec * 1_000 + cs * 10, text = text)
    }
}

/**
 * Returns the index of the line currently playing at [positionMs].
 * -1 if no lyrics or before the first line.
 */
private fun currentLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) idx = i else break
    }
    return idx
}

// ── Full Player View ──────────────────────────────────────────────────

/**
 * Full-screen player overlay that slides up from the bottom.
 *
 * @param contract player contract for state and actions.
 * @param visible whether the overlay is shown.
 * @param onBack callback when the user dismisses (back or swipe down).
 * @param modifier optional modifier.
 */
@Composable
fun FullPlayerOverlay(
    contract: PlayerContract,
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by contract.state.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        // Intercept system back press to close the overlay.
        BackHandler { onBack() }

        if (state.empty || state.nowPlaying == null) return@AnimatedVisibility

        var lyricsExpanded by remember { mutableStateOf(false) }

        // If lyrics are expanded, back closes lyrics; otherwise closes the overlay.
        BackHandler(enabled = lyricsExpanded) { lyricsExpanded = false }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (lyricsExpanded) {
                LyricsFullScreen(
                    lyrics = state.lyrics,
                    positionMs = state.positionMs,
                    onBack = { lyricsExpanded = false },
                    onRetryLyrics = contract::retryLyrics,
                )
            } else {
                FullPlayerContent(
                    state = state,
                    contract = contract,
                    onBack = onBack,
                    onLyricsTap = { lyricsExpanded = true },
                )
            }
        }
    }
}

// ── Full Player Content ───────────────────────────────────────────────

@Composable
private fun FullPlayerContent(
    state: PlayerUiState,
    contract: PlayerContract,
    onBack: () -> Unit,
    onLyricsTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Collapse button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Collapse player",
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Cover art
        AsyncImage(
            model = state.nowPlaying?.coverUrl,
            contentDescription = "Album art",
            modifier = Modifier
                .fillMaxWidth()
                .height(ARTWORK_HEIGHT)
                .padding(horizontal = HORIZONTAL_PADDING)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = state.nowPlaying?.title.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING),
        )

        // Artist
        Text(
            text = state.nowPlaying?.artist.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Seek bar
        FullPlayerSeekBar(state = state, contract = contract)

        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = DurationFormat.format(state.positionMs),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = DurationFormat.format(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Transport controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = contract::previous) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                )
            }

            IconButton(
                onClick = contract::togglePlay,
                modifier = Modifier.size(PLAY_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(PLAY_ICON_SIZE),
                )
            }

            IconButton(onClick = contract::next) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Lyrics mini-view (fills remaining space)
        LyricsMiniView(
            lyrics = state.lyrics,
            positionMs = state.positionMs,
            onTap = onLyricsTap,
            onRetryLyrics = contract::retryLyrics,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = HORIZONTAL_PADDING),
        )
    }
}

// ── Seek Bar ──────────────────────────────────────────────────────────

@Composable
private fun FullPlayerSeekBar(state: PlayerUiState, contract: PlayerContract) {
    val maxMs = if (state.durationMs > 0) state.durationMs else 1L
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = state.positionMs.coerceIn(0L, maxMs).toFloat(),
        onValueChange = { contract.seekTo(it.toLong()) },
        valueRange = 0f..maxMs.toFloat(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HORIZONTAL_PADDING),
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
                thumbSize = androidx.compose.ui.unit.DpSize(12.dp, 12.dp),
            )
        },
    )
}

// ── Lyrics Mini View ──────────────────────────────────────────────────

/**
 * Compact lyrics view showing 3 lines centered on the current playback position.
 * Tapping expands to full-screen lyrics.
 * If no lyrics are available, shows a "No lyrics" message with tap-to-retry.
 */
@Composable
private fun LyricsMiniView(
    lyrics: String?,
    positionMs: Long,
    onTap: () -> Unit,
    onRetryLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(lyrics) { lyrics?.let { parseLrc(it) } ?: emptyList() }
    val currentIndex = remember(parsed, positionMs) { currentLineIndex(parsed, positionMs) }

    Column(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) {
                if (lyrics != null) onTap() else onRetryLyrics()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (lyrics == null || parsed.isEmpty()) {
            // No lyrics available
            Text(
                text = if (lyrics == null) "No lyrics available" else "No lyrics",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        } else {
            // Show 3 lines: previous, current, next
            val prevIdx = (currentIndex - 1).coerceAtLeast(0)
            val nextIdx = (currentIndex + 1).coerceAtMost(parsed.lastIndex)
            val visibleIndices = when {
                currentIndex < 0 -> listOf(0, 1, 2).filter { it <= parsed.lastIndex }
                currentIndex == 0 -> listOf(0, 1).filter { it <= parsed.lastIndex }
                currentIndex >= parsed.lastIndex -> listOf(parsed.lastIndex - 1, parsed.lastIndex).filter { it >= 0 }
                else -> listOf(prevIdx, currentIndex, nextIdx)
            }

            for (idx in visibleIndices) {
                val isCurrent = idx == currentIndex
                Text(
                    text = parsed[idx].text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (isCurrent) 16.sp else 14.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Lyrics Full Screen ────────────────────────────────────────────────

/**
 * Full-screen lyrics view with auto-scrolling to the current line.
 * Current line is displayed in bold.
 */
@Composable
private fun LyricsFullScreen(
    lyrics: String?,
    positionMs: Long,
    onBack: () -> Unit,
    onRetryLyrics: () -> Unit,
) {
    val parsed = remember(lyrics) { lyrics?.let { parseLrc(it) } ?: emptyList() }
    val currentIndex = remember(parsed, positionMs) { currentLineIndex(parsed, positionMs) }
    val listState = rememberLazyListState()

    // Auto-scroll to the current line
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < parsed.size) {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -200,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { /* consume taps */ },
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "\u25BC",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        if (lyrics == null || parsed.isEmpty()) {
            // No lyrics
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onRetryLyrics() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No lyrics available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to retry",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(
                    items = parsed,
                    key = { index, line -> "${line.timeMs}_$index" },
                    contentType = { _, _ -> "lyrics_line" },
                ) { index, line ->
                    val isCurrent = index == currentIndex
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (isCurrent) 20.sp else 16.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}
