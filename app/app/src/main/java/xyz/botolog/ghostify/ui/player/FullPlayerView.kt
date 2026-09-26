@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.botolog.ghostify.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.roundToInt
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.model.FullPlayerLayout
import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.util.DurationFormat

// ── Dimensions ────────────────────────────────────────────────────────
private val ARTWORK_HEIGHT = 280.dp
private val HORIZONTAL_PADDING = 24.dp
private val PLAY_BUTTON_SIZE = 64.dp
private val PLAY_ICON_SIZE = 40.dp
private val TRANSPORT_ICON_SIZE = 32.dp
private val SLIDER_MIN_TRACK_HEIGHT = 16.dp

// ── Landscape Dimensions ──────────────────────────────────────────────
private val LANDSCAPE_ARTWORK_MAX_WIDTH = 400.dp
private val LANDSCAPE_LEFT_PANEL_MAX_WIDTH = 480.dp

// ── Super Compact Overlay Dimensions ────────────────────────────────────
private const val SUPER_COMPACT_SCRIM_ALPHA = 0.6f
private const val SUPER_COMPACT_SEEK_INACTIVE_ALPHA = 0.4f
private val SUPER_COMPACT_CONTROL_SPACING = 16.dp
private val SUPER_COMPACT_SEEK_BOTTOM_SPACING = 24.dp
private val SUPER_COMPACT_SEEK_PADDING = 24.dp
private val SUPER_COMPACT_TRANSPORT_PADDING = 32.dp

// ── Compact (controls on cover art) Dimensions ────────────────────────
private const val COMPACT_PLAY_BUTTON_COVER_RATIO = 0.26f
private const val COMPACT_PLAY_ICON_COVER_RATIO = 0.625f
private const val COMPACT_TRANSPORT_ICON_COVER_RATIO = 0.13f
private const val COMPACT_EDGE_INSET_COVER_RATIO = 0.05f
private const val COMPACT_TRANSPORT_GAP_COVER_RATIO = 0.06f
private const val COMPACT_SCRIM_ALPHA = 0.5f
private val COMPACT_PLAY_BUTTON_MIN = 40.dp
private val COMPACT_PLAY_BUTTON_MAX = PLAY_BUTTON_SIZE
private val COMPACT_TRANSPORT_ICON_MIN = 24.dp
private val COMPACT_TRANSPORT_ICON_MAX = TRANSPORT_ICON_SIZE
private val COMPACT_TRANSPORT_GAP_MIN = 12.dp
private val COMPACT_TRANSPORT_GAP_MAX = 32.dp
private val COMPACT_SEEK_MAX_HORIZONTAL_PADDING = 20.dp
private val COMPACT_SEEK_MAX_BOTTOM_PADDING = 12.dp
private val COMPACT_LYRICS_TOP_SPACING = 12.dp

// ── Normal (portrait) Lyrics Spacing ─────────────────────────────────
private val NORMAL_TRANSPORT_UP_OFFSET = 12.dp
private val NORMAL_LYRICS_BOTTOM_SPACING = 12.dp

// ── Lyrics Autoscroll ────────────────────────────────────────────────
private const val FULL_LYRICS_ACTIVE_LINE_OFFSET_PX = -200
private const val MINI_LYRICS_ACTIVE_LINE_OFFSET_PX = -100

/** How many times one autoscroll may re-measure its destination before it settles for. */
internal const val LYRICS_SCROLL_MAX_AIMS = 5
private const val LYRICS_SCROLL_MS_PER_100PX = 90
private const val LYRICS_SCROLL_MIN_DURATION_MS = 90
private const val LYRICS_SCROLL_MAX_DURATION_MS = 420

// ── Full Lyrics Active Line Emphasis ─────────────────────────────────
private const val LYRICS_EMPHASIS_ANIMATION_MS = 220
private const val LYRICS_IDLE_FONT_SIZE_SP = 18f
private const val LYRICS_ACTIVE_FONT_SIZE_SP = 24f
private const val LYRICS_LINE_WRAP_HEADROOM = 0.95f
private const val LYRICS_LINE_HEIGHT_FACTOR = 1.2f
private const val LYRICS_BOLD_SPREAD_FACTOR = 0.02f

/** Minimum horizontal drag distance (px) to count as a swipe. */
private const val SWIPE_THRESHOLD = 50f

// ── LRC helpers ───────────────────────────────────────────────────────

internal fun currentLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var idx = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) idx = i else break
    }
    return idx
}

internal data class LyricsScrollTarget(val index: Int, val offsetPx: Int)

/** A lyric line as the list has placed it: where its top sits and how tall it turned out. */
internal data class LyricsMeasuredLine(
    val index: Int,
    val offsetInViewportPx: Int,
    val heightPx: Int,
)

/**
 * Autoscroll destination for a lyrics list, or `null` when the list must stay put: an unmeasured
 * viewport has no item offsets to aim at, a negative [currentIndex] means playback is still before
 * the first timestamp, so the list is asked to rest at the very top, and an index outside the list
 * cannot be scrolled to. Otherwise the active line is kept near the top edge, its top edge landing
 * [activeLineOffsetPx] inside the viewport.
 */
internal fun lyricsAutoScrollTarget(
    currentIndex: Int,
    lineCount: Int,
    viewportMeasured: Boolean,
    activeLineOffsetPx: Int = FULL_LYRICS_ACTIVE_LINE_OFFSET_PX,
): LyricsScrollTarget? = when {
    !viewportMeasured -> null
    lineCount <= 0 -> null
    currentIndex < 0 -> LyricsScrollTarget(index = 0, offsetPx = 0)
    currentIndex < lineCount -> LyricsScrollTarget(
        index = currentIndex,
        offsetPx = activeLineOffsetPx,
    )
    else -> null
}

/** The mini box aims the same way as the full list, only closer to the top of its smaller box. */
internal fun miniLyricsAutoScrollTarget(
    currentIndex: Int,
    lineCount: Int,
    viewportMeasured: Boolean,
): LyricsScrollTarget? = lyricsAutoScrollTarget(
    currentIndex = currentIndex,
    lineCount = lineCount,
    viewportMeasured = viewportMeasured,
    activeLineOffsetPx = MINI_LYRICS_ACTIVE_LINE_OFFSET_PX,
)

/**
 * Where the top of [targetIndex] sits in the viewport: the offset the list measured for that line
 * when it has placed it, otherwise the offset carried on from the nearest placed line at the
 * average measured line height, and `null` while the list has placed nothing to measure.
 */
internal fun lyricsLineOffsetInViewportPx(
    targetIndex: Int,
    measuredLines: List<LyricsMeasuredLine>,
): Int? {
    if (measuredLines.isEmpty()) return null
    measuredLines.firstOrNull { line -> line.index == targetIndex }
        ?.let { line -> return line.offsetInViewportPx }
    val measuredHeights = measuredLines.map { line -> line.heightPx }.filter { heightPx -> heightPx > 0 }
    if (measuredHeights.isEmpty()) return null
    val averageHeightPx = measuredHeights.sum() / measuredHeights.size
    val anchor = measuredLines.minByOrNull { line -> abs(line.index - targetIndex) } ?: return null
    return anchor.offsetInViewportPx + (targetIndex - anchor.index) * averageHeightPx
}

/**
 * Pixels the list still has to move to put [target]'s line where its [LyricsScrollTarget.offsetPx]
 * asks for, and zero once the line is there — which is how an autoscroll knows it has landed and
 * needs no corrective scroll for the last pixels. `null` while the list has measured nothing.
 */
internal fun lyricsScrollDeltaPx(
    target: LyricsScrollTarget,
    measuredLines: List<LyricsMeasuredLine>,
): Int? {
    val offsetInViewportPx = lyricsLineOffsetInViewportPx(target.index, measuredLines) ?: return null
    return offsetInViewportPx + target.offsetPx
}

/** A move of this many pixels is given this many milliseconds, so short corrections stay short. */
internal fun lyricsScrollDurationMs(distancePx: Int): Int =
    (LYRICS_SCROLL_MS_PER_100PX * abs(distancePx) / 100f)
        .roundToInt()
        .coerceIn(LYRICS_SCROLL_MIN_DURATION_MS, LYRICS_SCROLL_MAX_DURATION_MS)

private fun LazyListLayoutInfo.measuredLyricsLines(): List<LyricsMeasuredLine> =
    visibleItemsInfo.map { item -> LyricsMeasuredLine(item.index, item.offset, item.size) }

/**
 * Autoscrolls the list onto [target] and leaves it there.
 *
 * Each pass measures the line where the list currently has it and animates exactly the pixel
 * distance that measurement asks for, then waits a frame for the list to re-measure and aim
 * again: a destination the list has not placed yet moves as it places more of itself, and the next
 * pass follows it there. The animation therefore lands on the measured target by itself — measured
 * zero ends it — so no corrective scroll snaps the last pixels of the move, which is exactly what
 * the item-based animation used to do at the end of every scroll. A list that will not move any
 * further has run out of content and is left where it is, and a user drag takes the list over
 * through the scroll mutation, as with any autoscroll.
 */
private suspend fun LazyListState.animateToLyricsTarget(target: LyricsScrollTarget) {
    var measuredLines = layoutInfo.measuredLyricsLines()
    var aimsLeft = LYRICS_SCROLL_MAX_AIMS

    while (aimsLeft-- > 0) {
        val deltaPx = lyricsScrollDeltaPx(target, measuredLines) ?: return
        if (deltaPx == 0) return
        val movedPx = animateScrollBy(
            value = deltaPx.toFloat(),
            animationSpec = tween(
                durationMillis = lyricsScrollDurationMs(deltaPx),
                easing = FastOutSlowInEasing,
            ),
        )
        if (movedPx == 0f) return
        withFrameNanos { }
        if (isScrollInProgress) return
        measuredLines = layoutInfo.measuredLyricsLines()
    }
}

/** Emphasis a line is animated towards: fully emphasised while it is the active one. */
internal fun lyricsEmphasisTarget(isCurrent: Boolean): Float = if (isCurrent) 1f else 0f

internal fun lyricsLineFontSizeSp(emphasis: Float): TextUnit =
    lerp(LYRICS_IDLE_FONT_SIZE_SP.sp, LYRICS_ACTIVE_FONT_SIZE_SP.sp, emphasis.coerceIn(0f, 1f))

internal fun lyricsLineFontWeight(emphasis: Float): FontWeight =
    lerp(FontWeight.Normal, FontWeight.Bold, emphasis.coerceIn(0f, 1f))

internal fun lyricsLineColor(idle: Color, active: Color, emphasis: Float): Color =
    lerp(idle, active, emphasis.coerceIn(0f, 1f))

/**
 * How far a line's text is scaled inside the box the line is measured in: down to the idle size
 * while the line rests, up to the measured size while it is active. The scale is paint only, so the
 * box the line occupies in the list, its tap target and its baseline stay where the measurement
 * put them whatever the emphasis is doing.
 */
internal fun lyricsLineVisualScale(emphasis: Float): Float {
    val idleScale = LYRICS_IDLE_FONT_SIZE_SP / LYRICS_ACTIVE_FONT_SIZE_SP
    return idleScale + emphasis.coerceIn(0f, 1f) * (1f - idleScale)
}

/**
 * Ems that fit on one line of the full lyrics list, i.e. the [availableWidth] measured in the
 * largest font the list ever uses, with a little [LYRICS_LINE_WRAP_HEADROOM] left for the bolder
 * active weight. Font size and budget are both read in dp, so a larger system font shrinks the
 * number of ems per line instead of widening the line past the viewport.
 */
internal fun lyricsLineEmBudget(availableWidth: Dp, fontScale: Float): Float {
    if (!availableWidth.value.isFinite() || !fontScale.isFinite() || fontScale <= 0f) return 0f
    val activeFontSizeDp = LYRICS_ACTIVE_FONT_SIZE_SP * fontScale
    if (activeFontSizeDp <= 0f) return 0f
    return availableWidth.value * LYRICS_LINE_WRAP_HEADROOM / activeFontSizeDp
}

/**
 * Width one line of the full lyrics list may use at [emphasis], or `null` when [availableWidth]
 * is not a real width yet and the caller should fill the available space instead.
 *
 * The width grows with the line's font scale, so a line keeps the same [lyricsLineEmBudget] —
 * and therefore the same line breaks — at every emphasis: emphasising it re-uses the wrapping it
 * already had instead of pushing a word onto a new line, which would change the item's height
 * and shift everything below it.
 */
internal fun lyricsLineWidthDp(availableWidth: Dp, fontScale: Float, emphasis: Float): Dp? {
    if (!availableWidth.value.isFinite() || !fontScale.isFinite() || fontScale <= 0f) return null
    if (availableWidth <= 0.dp) return null
    val fontSizeDp = lyricsLineFontSizeSp(emphasis).value * fontScale
    if (fontSizeDp <= 0f) return null
    val width = (lyricsLineEmBudget(availableWidth, fontScale) * fontSizeDp).dp
    return width.coerceIn(0.dp, availableWidth)
}

// ── Full Player Overlay ───────────────────────────────────────────────

@Composable
fun FullPlayerOverlay(
    contract: PlayerContract,
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    landscapeControlsSide: String = "left",
    layout: FullPlayerLayout = FullPlayerLayout.NORMAL,
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
        var queueEditorOpen by remember { mutableStateOf(false) }
        BackHandler(enabled = queueEditorOpen) { queueEditorOpen = false }
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
                    onQueueClick = { queueEditorOpen = true },
                    landscapeControlsSide = landscapeControlsSide,
                    layout = layout,
                )
            }
        }

        if (queueEditorOpen) {
            QueueEditorBottomSheet(
                contract = contract,
                onDismiss = { queueEditorOpen = false },
            )
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
    onQueueClick: () -> Unit,
    landscapeControlsSide: String = "left",
    layout: FullPlayerLayout = FullPlayerLayout.NORMAL,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controlsOnCover = layout == FullPlayerLayout.COMPACT

    when (layout) {
        FullPlayerLayout.SUPER_COMPACT -> SuperCompactPlayerContent(
            state = state,
            contract = contract,
            onBack = onBack,
            onLyricsTap = onLyricsTap,
            onInfoTap = onInfoTap,
            onQueueClick = onQueueClick,
        )

        FullPlayerLayout.NORMAL, FullPlayerLayout.COMPACT -> if (isLandscape) {
            LandscapePlayerContent(
                state = state,
                contract = contract,
                onBack = onBack,
                onLyricsTap = onLyricsTap,
                onInfoTap = onInfoTap,
                onQueueClick = onQueueClick,
                controlsSide = landscapeControlsSide,
                controlsOnCover = controlsOnCover,
            )
        } else {
            PortraitPlayerContent(
                state = state,
                contract = contract,
                onBack = onBack,
                onLyricsTap = onLyricsTap,
                onInfoTap = onInfoTap,
                onQueueClick = onQueueClick,
                controlsOnCover = controlsOnCover,
            )
        }
    }
}

// ── Super Compact Player Content (full-bleed cover art overlay) ───────

@Composable
private fun SuperCompactPlayerContent(
    state: PlayerUiState,
    contract: PlayerContract,
    onBack: () -> Unit,
    onLyricsTap: () -> Unit,
    onInfoTap: () -> Unit,
    onQueueClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        SwipeableCoverArt(
            coverUrl = state.nowPlaying?.coverUrl,
            onSwipeLeft = contract::next,
            onSwipeRight = contract::previousTrack,
            onSwipeDown = onBack,
            modifier = Modifier.fillMaxSize(),
            square = false,
            shape = RectangleShape,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.scrim.copy(alpha = SUPER_COMPACT_SCRIM_ALPHA),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            SuperCompactTopBar(
                onBack = onBack,
                onLyricsTap = onLyricsTap,
                onQueueClick = onQueueClick,
                onInfoTap = onInfoTap,
            )

            Spacer(modifier = Modifier.weight(1f))

            SuperCompactTransportRow(state = state, contract = contract)

            Spacer(modifier = Modifier.height(SUPER_COMPACT_CONTROL_SPACING))

            FullPlayerSeekBar(
                state = state,
                contract = contract,
                horizontalPadding = 0.dp,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = SUPER_COMPACT_SEEK_INACTIVE_ALPHA),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SUPER_COMPACT_SEEK_PADDING),
            )

            Spacer(modifier = Modifier.height(SUPER_COMPACT_SEEK_BOTTOM_SPACING))
        }
    }
}

/**
 * Top overlay bar of the super-compact player: collapse on the left, lyrics, queue and
 * info on the right. All icons stay white so they read against the cover art.
 */
@Composable
private fun SuperCompactTopBar(
    onBack: () -> Unit,
    onLyricsTap: () -> Unit,
    onQueueClick: () -> Unit,
    onInfoTap: () -> Unit,
) {
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
                tint = Color.White,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onLyricsTap) {
            Icon(
                imageVector = Icons.Filled.Lyrics,
                contentDescription = "Lyrics",
                modifier = Modifier.size(24.dp),
                tint = Color.White,
            )
        }
        IconButton(onClick = onQueueClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Queue",
                modifier = Modifier.size(24.dp),
                tint = Color.White,
            )
        }
        IconButton(onClick = onInfoTap) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = "Song info",
                modifier = Modifier.size(24.dp),
                tint = Color.White,
            )
        }
    }
}

/**
 * Transport controls laid over the cover art: previous on the left, play/pause dead
 * center, next on the right. Buttons keep their default (transparent) container.
 */
@Composable
private fun SuperCompactTransportRow(
    state: PlayerUiState,
    contract: PlayerContract,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SUPER_COMPACT_TRANSPORT_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = contract::previous) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.size(PLAY_BUTTON_SIZE))
            IconButton(onClick = contract::next) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(TRANSPORT_ICON_SIZE),
                    tint = Color.White,
                )
            }
        }

        IconButton(
            onClick = contract::togglePlay,
            modifier = Modifier.size(PLAY_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(PLAY_ICON_SIZE),
                tint = Color.White,
            )
        }
    }
}

// ── Compact Player Content (controls on the cover art) ─────────────────

/**
 * Playback controls drawn on top of the cover art: play/pause exactly centered,
 * previous and next tucked close to it, and the seek bar hugging the bottom edge
 * of the cover. A theme-derived [MaterialTheme.colorScheme.scrim] at
 * [COMPACT_SCRIM_ALPHA] is laid over the cover bounds only, to keep the overlaid
 * controls legible. Sizes scale with the cover so the controls stay centered and
 * tappable in both orientations. The scrim does not consume pointer events, so
 * swipe gestures on the cover art underneath still work, while the buttons and
 * the seek bar consume their own touches.
 */
@Composable
private fun CompactCoverControls(
    state: PlayerUiState,
    contract: PlayerContract,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val coverExtent = minOf(maxWidth, maxHeight)
        val playButtonSize = (coverExtent * COMPACT_PLAY_BUTTON_COVER_RATIO)
            .coerceIn(COMPACT_PLAY_BUTTON_MIN, COMPACT_PLAY_BUTTON_MAX)
        val playIconSize = (coverExtent * COMPACT_PLAY_ICON_COVER_RATIO)
            .coerceIn(COMPACT_TRANSPORT_ICON_MIN, playButtonSize)
        val transportIconSize = (coverExtent * COMPACT_TRANSPORT_ICON_COVER_RATIO)
            .coerceIn(COMPACT_TRANSPORT_ICON_MIN, COMPACT_TRANSPORT_ICON_MAX)
        val transportGap = (coverExtent * COMPACT_TRANSPORT_GAP_COVER_RATIO)
            .coerceIn(COMPACT_TRANSPORT_GAP_MIN, COMPACT_TRANSPORT_GAP_MAX)
        val sidePadding = (coverExtent * COMPACT_EDGE_INSET_COVER_RATIO)
            .coerceAtMost(COMPACT_SEEK_MAX_HORIZONTAL_PADDING)
        val bottomPadding = (coverExtent * COMPACT_EDGE_INSET_COVER_RATIO)
            .coerceAtMost(COMPACT_SEEK_MAX_BOTTOM_PADDING)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.scrim.copy(alpha = COMPACT_SCRIM_ALPHA),
                ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(transportGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = contract::previous) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(transportIconSize),
                )
            }
            IconButton(
                onClick = contract::togglePlay,
                modifier = Modifier.size(playButtonSize),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(playIconSize),
                )
            }
            IconButton(onClick = contract::next) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(transportIconSize),
                )
            }
        }

        FullPlayerSeekBar(
            state = state,
            contract = contract,
            horizontalPadding = sidePadding,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
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
    onQueueClick: () -> Unit,
    controlsOnCover: Boolean = false,
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
            IconButton(onClick = onQueueClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
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

            if (controlsOnCover) {
                CompactCoverControls(
                    state = state,
                    contract = contract,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
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

        // Seek bar, time labels and transport controls — omitted in compact mode,
        // where they are drawn on top of the cover art instead.
        if (!controlsOnCover) {
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
                    .offset(y = -NORMAL_TRANSPORT_UP_OFFSET),
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
        }

        if (controlsOnCover) {
            Spacer(modifier = Modifier.height(COMPACT_LYRICS_TOP_SPACING))
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

        if (!controlsOnCover) {
            Spacer(modifier = Modifier.height(NORMAL_LYRICS_BOTTOM_SPACING))
        }
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
    onQueueClick: () -> Unit,
    controlsSide: String = "left",
    controlsOnCover: Boolean = false,
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
                controlsOnCover = controlsOnCover,
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
                controlsOnCover = controlsOnCover,
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
    controlsOnCover: Boolean = false,
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

            if (controlsOnCover) {
                CompactCoverControls(
                    state = state,
                    contract = contract,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
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

        // Seek bar, time labels and transport controls — omitted in compact mode,
        // where they are drawn on top of the cover art instead.
        if (!controlsOnCover) {
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
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    square: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val gestureModifier = if (gesturesEnabled) {
        Modifier.pointerInput(Unit) {
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
        }
    } else {
        Modifier
    }

    val sizeModifier = if (square) {
        modifier.fillMaxWidth().aspectRatio(1f)
    } else {
        modifier
    }

    AsyncImage(
        model = coverUrl,
        contentDescription = "Album art",
        modifier = sizeModifier
            .clip(shape)
            .then(gestureModifier),
        contentScale = ContentScale.Crop,
    )
}

// ── Seek Bar (seeks on release only) ──────────────────────────────────

@Composable
private fun FullPlayerSeekBar(
    state: PlayerUiState,
    contract: PlayerContract,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = HORIZONTAL_PADDING,
    trackHeight: Dp = 4.dp,
    thumbSize: Dp = 12.dp,
    activeTrackColor: Color = MaterialTheme.colorScheme.primary,
    inactiveTrackColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
) {
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        interactionSource = interactionSource,
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(trackHeight),
                thumbTrackGapSize = 0.dp,
                colors = SliderDefaults.colors(
                    activeTrackColor = activeTrackColor,
                    inactiveTrackColor = inactiveTrackColor,
                ),
            )
        },
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                modifier = Modifier.offset(y = (SLIDER_MIN_TRACK_HEIGHT - thumbSize) / 2),
                thumbSize = DpSize(thumbSize, thumbSize),
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
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }

            // Keyed on the parsed lyrics, the active line and the viewport, so the scroll is
            // issued once per track or line change instead of on every position update, and is
            // re-aimed when the box has been measured again after a resize. Playback before the
            // first timestamp asks for the same top every time, so it settles there once instead of
            // resetting the box on every position update.
            LaunchedEffect(parsed, currentIndex, viewportSize) {
                val target = miniLyricsAutoScrollTarget(
                    currentIndex = currentIndex,
                    lineCount = parsed.size,
                    viewportMeasured = viewportSize.width > 0 && viewportSize.height > 0,
                ) ?: return@LaunchedEffect
                listState.animateToLyricsTarget(target)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { viewportSize = it },
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

    // Scoping on the parsed lyrics gives every track its own list, scroll position and
    // autoscroll, so a new song starts at its own top instead of inheriting the old one.
    key(parsed) {
        val listState = rememberLazyListState()
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }

        // Keyed on the active line and on the viewport, so the scroll is issued once per
        // line change instead of on every position update, and is re-aimed when the list is
        // measured again after a resize. Nothing is scrolled before the list has been measured,
        // because the item offsets that the autoscroll aims at are not known until then. Playback
        // before the first timestamp rests at the top, later lines keep the active line near the
        // top edge.
        LaunchedEffect(currentIndex, viewportSize) {
            val target = lyricsAutoScrollTarget(
                currentIndex = currentIndex,
                lineCount = parsed.size,
                viewportMeasured = viewportSize.width > 0 && viewportSize.height > 0,
            ) ?: return@LaunchedEffect
            listState.animateToLyricsTarget(target)
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
                        .padding(horizontal = 24.dp)
                        .onSizeChanged { viewportSize = it },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    itemsIndexed(
                        items = parsed,
                        key = { index, line -> "${line.timeMs}_$index" },
                        contentType = { _, _ -> "lyrics_line" },
                    ) { index, line ->
                        LyricsFullLine(
                            line = line,
                            isCurrent = index == currentIndex,
                            onSeek = { onSeekTo(line.timeMs.coerceAtLeast(0L)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single line of the full lyrics list.
 *
 * The emphasis of the active line is animated instead of snapped, driven by one value that only
 * changes when this very line gains or loses focus, so an idle line never animates. Every line is
 * measured and laid out once, in the largest typography the list ever uses, and keeps that box for
 * good: the wrapping, the line height, the tap target and the space the line takes in the list all
 * come from that one measurement, so emphasising a line can never add or drop a line of text or
 * resize the item, and nothing below it moves.
 *
 * The emphasis is therefore paint only. The text is scaled inside its fixed box between the idle
 * and the active size, a second copy of the same text is smeared sideways by a fraction of the
 * font size to read as bold, and both are tinted from the idle to the active colour. The smear
 * shares the text's style, width and wrap points exactly — it is the same layout painted twice —
 * and it is drawn without semantics so the line is still announced once.
 */
@Composable
private fun LyricsFullLine(
    line: LrcLine,
    isCurrent: Boolean,
    onSeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val activeColor = MaterialTheme.colorScheme.onSurface
    val emphasis by animateFloatAsState(
        targetValue = lyricsEmphasisTarget(isCurrent),
        animationSpec = tween(
            durationMillis = LYRICS_EMPHASIS_ANIMATION_MS,
            easing = FastOutSlowInEasing,
        ),
        label = "lyricsLineEmphasis",
    )
    val color = lyricsLineColor(idle = idleColor, active = activeColor, emphasis = emphasis)
    val visualScale = lyricsLineVisualScale(emphasis)
    val boldSpreadPx = with(LocalDensity.current) {
        (lyricsLineFontSizeSp(1f).toDp() * LYRICS_BOLD_SPREAD_FACTOR).toPx()
    } * emphasis
    val measuredStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = lyricsLineFontSizeSp(1f),
        lineHeight = (LYRICS_ACTIVE_FONT_SIZE_SP * LYRICS_LINE_HEIGHT_FACTOR).sp,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSeek() }
            .padding(vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = visualScale
                    scaleY = visualScale
                },
        ) {
            Text(
                text = line.text,
                style = measuredStyle,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = line.text,
                style = measuredStyle,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = emphasis
                        translationX = boldSpreadPx
                    }
                    .clearAndSetSemantics { },
            )
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
