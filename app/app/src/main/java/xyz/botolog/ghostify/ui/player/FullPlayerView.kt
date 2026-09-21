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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import coil.compose.AsyncImage
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.util.DurationFormat
import kotlin.math.abs

// ── Dimensions ────────────────────────────────────────────────────────
private val ARTWORK_HEIGHT = 280.dp
private val HORIZONTAL_PADDING = 24.dp
private val PLAY_BUTTON_SIZE = 64.dp
private val PLAY_ICON_SIZE = 40.dp
private val TRANSPORT_ICON_SIZE = 32.dp

// ── Landscape Dimensions ──────────────────────────────────────────────
private val LANDSCAPE_ARTWORK_MAX_WIDTH = 400.dp
private val LANDSCAPE_LEFT_PANEL_MAX_WIDTH = 480.dp

/** Minimum horizontal drag distance (px) to count as a swipe. */
private const val SWIPE_THRESHOLD = 50f

// ── LRC helpers ───────────────────────────────────────────────────────

private fun currentLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) idx = i else break
    }
    return idx
}

// ── Full Player Overlay ───────────────────────────────────────────────

@Composable
fun FullPlayerOverlay(
    contract: PlayerContract,
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    landscapeControlsSide: String = "left",
) {
    val state by contract.state.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        BackHandler { onBack() }

        if (state.empty || state.nowPlaying == null) return@AnimatedVisibility

        var lyricsExpanded by remember { mutableStateOf(false) }
        var lyricsEditing by remember { mutableStateOf(false) }
        var showSongInfo by remember { mutableStateOf(false) }
        BackHandler(enabled = lyricsEditing) { lyricsEditing = false }
        BackHandler(enabled = lyricsExpanded && !lyricsEditing) { lyricsExpanded = false }

        if (showSongInfo) {
            SongInfoDialog(
                nowPlaying = state.nowPlaying,
                onDismiss = { showSongInfo = false },
            )
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (lyricsEditing) {
                LyricsEditView(
                    lyrics = state.lyrics,
                    onBack = { lyricsEditing = false },
                    onSave = { lyrics, edited -> contract.saveLyrics(lyrics, edited) },
                    onRefetch = { provider, onResult -> contract.refetchLyrics(provider, onResult) },
                )
            } else if (lyricsExpanded) {
                LyricsFullScreen(
                    lyrics = state.lyrics,
                    positionMs = state.positionMs,
                    onBack = { lyricsExpanded = false },
                    onRetryLyrics = contract::retryLyrics,
                    onEdit = { lyricsEditing = true },
                    onSeekTo = contract::seekTo,
                )
            } else {
                FullPlayerContent(
                    state = state,
                    contract = contract,
                    onBack = onBack,
                    onLyricsTap = { lyricsExpanded = true },
                    onInfoTap = { showSongInfo = true },
                    landscapeControlsSide = landscapeControlsSide,
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
    onInfoTap: () -> Unit,
    landscapeControlsSide: String = "left",
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        LandscapePlayerContent(
            state = state,
            contract = contract,
            onBack = onBack,
            onLyricsTap = onLyricsTap,
            onInfoTap = onInfoTap,
            controlsSide = landscapeControlsSide,
        )
    } else {
        PortraitPlayerContent(
            state = state,
            contract = contract,
            onBack = onBack,
            onLyricsTap = onLyricsTap,
            onInfoTap = onInfoTap,
        )
    }
}

// ── Portrait Player Content (original) ────────────────────────────────

@Composable
private fun PortraitPlayerContent(
    state: PlayerUiState,
    contract: PlayerContract,
    onBack: () -> Unit,
    onLyricsTap: () -> Unit,
    onInfoTap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Collapse button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Collapse player",
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onInfoTap) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Song info",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        // Cover art with swipe gestures
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING),
        ) {
            SwipeableCoverArt(
                coverUrl = state.nowPlaying?.coverUrl,
                onSwipeLeft = contract::next,
                onSwipeRight = contract::previousTrack,
                onSwipeDown = onBack,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

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

        // Seek bar (only seeks on release)
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

        // Transport controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = (-24).dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
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

// ── Landscape Player Content ──────────────────────────────────────────

@Composable
private fun LandscapePlayerContent(
    state: PlayerUiState,
    contract: PlayerContract,
    onBack: () -> Unit,
    onLyricsTap: () -> Unit,
    onInfoTap: () -> Unit,
    controlsSide: String = "left",
) {
    val controlsOnLeft = controlsSide == "left"

    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (controlsOnLeft) {
            // Left panel: Controls — Right panel: Lyrics
            ControlsPanel(
                state = state,
                contract = contract,
                modifier = Modifier
                    .widthIn(max = LANDSCAPE_LEFT_PANEL_MAX_WIDTH)
                    .weight(0.45f),
            )
            VerticalDivider()
            LyricsPanel(
                state = state,
                onBack = onBack,
                onInfoTap = onInfoTap,
                onLyricsTap = onLyricsTap,
                contract = contract,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        } else {
            // Left panel: Lyrics — Right panel: Controls
            LyricsPanel(
                state = state,
                onBack = onBack,
                onInfoTap = onInfoTap,
                onLyricsTap = onLyricsTap,
                contract = contract,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            VerticalDivider()
            ControlsPanel(
                state = state,
                contract = contract,
                modifier = Modifier
                    .widthIn(max = LANDSCAPE_LEFT_PANEL_MAX_WIDTH)
                    .weight(0.45f),
            )
        }
    }
}

// ── Swipeable Cover Art ───────────────────────────────────────────────

@Composable
private fun ControlsPanel(
    state: PlayerUiState,
    contract: PlayerContract,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = LANDSCAPE_ARTWORK_MAX_WIDTH)
                .aspectRatio(1f)
                .padding(horizontal = 8.dp),
        ) {
            SwipeableCoverArt(
                coverUrl = state.nowPlaying?.coverUrl,
                onSwipeLeft = contract::next,
                onSwipeRight = contract::previousTrack,
                onSwipeDown = { },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = state.nowPlaying?.title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )

        Text(
            text = state.nowPlaying?.artist.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        FullPlayerSeekBar(state = state, contract = contract)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = DurationFormat.format(state.positionMs),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = DurationFormat.format(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
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
    }
}

@Composable
private fun LyricsPanel(
    state: PlayerUiState,
    onBack: () -> Unit,
    onInfoTap: () -> Unit,
    onLyricsTap: () -> Unit,
    contract: PlayerContract,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(start = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Collapse player",
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onInfoTap) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "Song info",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        LyricsMiniView(
            lyrics = state.lyrics,
            positionMs = state.positionMs,
            onTap = onLyricsTap,
            onRetryLyrics = contract::retryLyrics,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun VerticalDivider() {
    Spacer(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    )
}

// ── Swipeable Cover Art ───────────────────────────────────────────────

@Composable
private fun SwipeableCoverArt(
    coverUrl: Any?,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    AsyncImage(
        model = coverUrl,
        contentDescription = "Album art",
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val absX = kotlin.math.abs(dragOffsetX)
                        val absY = kotlin.math.abs(dragOffsetY)
                        if (absX > SWIPE_THRESHOLD || absY > SWIPE_THRESHOLD) {
                            if (absX > absY) {
                                // Horizontal swipe dominates
                                if (dragOffsetX > 0) onSwipeRight() else onSwipeLeft()
                            } else {
                                // Vertical swipe dominates
                                onSwipeDown()
                            }
                        }
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffsetX += amount.x
                        dragOffsetY += amount.y
                    },
                )
            },
        contentScale = ContentScale.Crop,
    )
}

// ── Seek Bar (seeks on release only) ──────────────────────────────────

@Composable
private fun FullPlayerSeekBar(state: PlayerUiState, contract: PlayerContract) {
    val maxMs = if (state.durationMs > 0) state.durationMs else 1L
    val interactionSource = remember { MutableInteractionSource() }

    // Track whether the user is dragging.
    var isDragging by remember { mutableStateOf(false) }
    // The position to display while dragging (overrides state.positionMs).
    var dragPosition by remember { mutableFloatStateOf(0f) }

    // Observe interaction source to detect drag start/end.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isDragging = true
                    dragPosition = state.positionMs.coerceIn(0L, maxMs).toFloat()
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
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
            .padding(horizontal = HORIZONTAL_PADDING),
        interactionSource = interactionSource,
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
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable {
                if (lyrics != null && parsed.isNotEmpty()) onTap() else onRetryLyrics()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (lyrics == null || parsed.isEmpty()) {
            Text(
                text = if (lyrics == null) "No lyrics available" else "No lyrics",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tap to retry",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        } else {
            val listState = rememberLazyListState()

            LaunchedEffect(currentIndex) {
                if (currentIndex >= 0 && currentIndex < parsed.size) {
                    listState.animateScrollToItem(
                        index = currentIndex,
                        scrollOffset = -100,
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                userScrollEnabled = false,
            ) {
                itemsIndexed(
                    items = parsed,
                    key = { index, line -> "${line.timeMs}_$index" },
                    contentType = { _, _ -> "mini_lyrics_line" },
                ) { index, line ->
                    val isCurrent = index == currentIndex
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = if (isCurrent) 20.sp else 15.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                        textAlign = TextAlign.Center,
                        maxLines = if (isCurrent) Int.MAX_VALUE else 1,
                        overflow = if (isCurrent) TextOverflow.Clip else TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// ── Lyrics Full Screen ────────────────────────────────────────────────

@Composable
private fun LyricsFullScreen(
    lyrics: String?,
    positionMs: Long,
    onBack: () -> Unit,
    onRetryLyrics: () -> Unit,
    onEdit: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val parsed = remember(lyrics) { lyrics?.let { parseLrc(it) } ?: emptyList() }
    val currentIndex = remember(parsed, positionMs) { currentLineIndex(parsed, positionMs) }
    val listState = rememberLazyListState()

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
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Close lyrics",
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = "Lyrics",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit, modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Edit lyrics",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }

        if (lyrics == null || parsed.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onRetryLyrics() },
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
                            fontSize = if (isCurrent) 24.sp else 18.sp,
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
                            .padding(vertical = 6.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onSeekTo((line.timeMs).coerceAtLeast(0L)) },
                    )
                }
            }
        }
    }
}

// ── Song Info Formatting Helpers ─────────────────────────────────────

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

private fun formatTimestamp2(epochMs: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMs))
}

// ── Song Info Dialog ────────────────────────────────────────────────

@Composable
private fun SongInfoDialog(
    nowPlaying: NowPlaying?,
    onDismiss: () -> Unit,
) {
    if (nowPlaying == null) return

    val fields = remember(nowPlaying) {
        listOf(
            "Title" to nowPlaying.title.ifEmpty { "N/A" },
            "Artists" to nowPlaying.artist.ifEmpty { "N/A" },
            "Album" to nowPlaying.album.ifEmpty { "N/A" },
            "Spotify ID" to nowPlaying.spotifyId.ifEmpty { "N/A" },
            "YouTube ID" to (nowPlaying.ytId ?: "N/A"),
            "YouTube URL" to (nowPlaying.ytUrl ?: "N/A"),
            "YouTube Name" to (nowPlaying.ytName ?: "N/A"),
            "YouTube Channel" to (nowPlaying.ytChannel ?: "N/A"),
            "File Path" to (nowPlaying.filePath ?: "N/A"),
            "Status" to nowPlaying.status.ifEmpty { "N/A" },
            "Error" to (nowPlaying.error ?: "N/A"),
            "Duration (ms)" to nowPlaying.durationMs.toString(),
            "Position" to nowPlaying.position.toString(),
            "Bitrate" to (nowPlaying.bitrate?.let { "${it} kbps" } ?: "N/A"),
            "File Size" to (nowPlaying.fileSize?.let { formatFileSize(it) } ?: "N/A"),
            "Downloaded At" to (nowPlaying.downloadedAt?.let { formatTimestamp2(it) } ?: "N/A"),
            "Lyrics Source" to (nowPlaying.lyricsSource ?: "N/A"),
            "Lyrics Edited" to if (nowPlaying.lyricsEdited) "Yes" else "No",
            "Lyrics" to (nowPlaying.lyrics?.let {
                if (it.length > 200) it.take(200) + "..." else it
            } ?: "N/A"),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Song Info",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp),
            ) {
                items(fields) { (label, value) ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
