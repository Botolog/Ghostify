package xyz.botolog.ghostify.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import xyz.botolog.ghostify.R
import kotlin.math.roundToInt

/**
 * The large "Lyrics" home-screen widget.
 *
 * Like [PlayerWidget] this is a pure *reader*: [provideGlance] asks
 * [WidgetStateProvider] for a [WidgetSnapshot] and paints it, without ever touching the
 * `PlayerController`, the `ExoPlayer` or the `PlaybackService`. A process launched only
 * to draw the widget reads persisted state + Room and nothing else; live data is pushed
 * in by [WidgetUpdateCoordinator].
 *
 * **Responsive + freely resizable vertically**: the widget declares [SizeMode.Exact], so
 * the composition re-runs with the host's real size (`androidx.glance.LocalSize`) on every
 * resize. The number of visible lyric rows is derived from the available height by the
 * pure `lyricLinesForHeight`, and the header/progress/transport blocks are dropped as the
 * cell gets short (`lyricsLayout`). At the reference 4x4 (250x250dp) the look is unchanged:
 * ```
 * ┌───────────────────────────────────────────┐
 * │ ┌────┐  Midnight Vessel                     │
 * │ └────┘  Neon Tides                          │
 * │                                             │
 * │      I feel the bassline in my chest        │  ← onSurface (faded)
 * │   Every heartbeat a waveform wake           │  ← onSurface
 * │   So play it louder, play it slow           │  ← primary + bold + 16sp (current)
 * │   Let the current pull me under             │  ← onSurface
 * │      There's a chorus I don't know          │  ← onSurfaceVariant (faded further)
 * │                                             │
 * │  ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁        │
 * │           ◀◀   (▶)   ▶▶                     │
 * └───────────────────────────────────────────┘
 * ```
 *
 * Lyrics behaviour:
 *  - **Synced**: the [lyricsWindow] of `layout.windowSize` lines is centred on the active
 *    line, which is drawn `primary` + bold + larger while neighbours fade
 *    (`onSurface` → `onSurfaceVariant`) with distance. **Every row is a single `Column`
 *    child** (padding, not a `Spacer`), so the window never trips the RemoteViews
 *    10-child cap that used to truncate the highlighted line.
 *  - **Plain fallback**: untimed lyrics render as a static block of the first
 *    `windowSize` lines, unhighlighted.
 *  - **No lyrics**: a centred "No lyrics" label; **nothing playing**: the centred shared
 *    empty state.
 */
class LyricsWidget : GlanceAppWidget() {

    /**
     * Compose at the exact host size and recompose on every resize, so the lyric window can
     * follow the height the user drags the widget to. See [PlayerWidget.sizeMode].
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Live snapshot when the UI process is up, otherwise persisted-state + Room.
        // Wrapped so a failed cold read degrades to the empty state instead of Glance's
        // error UI (`onCompositionError`); `latest` already guards internally.
        val initial = runCatching { WidgetStateProvider.latest(context) }
            .getOrElse { WidgetSnapshot.EMPTY }
        provideContent {
            // `update`/`updateAll` do NOT restart `provideGlance` while the Glance session
            // is alive — they only recompose. So the snapshot must be observed *inside*
            // the composition: capturing it above would freeze the widget on the first
            // frame. `WidgetUpdateCoordinator` publishes every player emission here.
            val live by WidgetStateProvider.snapshot.collectAsState()
            val snapshot = live ?: initial
            // The host cannot read our private files, so artwork travels as bytes; decode
            // only when the bytes change (the session runs off the main thread).
            val cover = remember(snapshot.coverBytes) { decodeCover(snapshot.coverBytes) }
            GlanceTheme(colors = WidgetTheme.colors) {
                LyricsWidgetContent(snapshot, cover)
            }
        }
    }
}

/** Registers [LyricsWidget] with the system; wired in the manifest. */
class LyricsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LyricsWidget()
}

// ── Composition ───────────────────────────────────────────────────────

@Composable
private fun LyricsWidgetContent(snapshot: WidgetSnapshot, cover: Bitmap?) {
    val size = LocalSize.current
    val layout = lyricsLayout(
        widthDp = size.width.value.roundToInt(),
        heightDp = size.height.value.roundToInt(),
    )
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(WidgetDimens.outerPadding)
            .clickable(actionOpenApp()),
        contentAlignment = Alignment.Center,
    ) {
        if (snapshot.isEmpty) {
            LyricsEmptyState(layout)
        } else {
            LyricsBody(snapshot, cover, layout)
        }
    }
}

/**
 * Cold-start / "nothing playing" state: the app's no-cover glyph above a label, centred
 * as a single unit. The glyph shrinks with the cell so it never crowds a small widget.
 */
@Composable
private fun LyricsEmptyState(layout: LyricsLayout) {
    val glyph = when {
        layout.showTransport -> 64
        layout.showHeader -> 44
        else -> 34
    }
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_no_song_cover),
            contentDescription = null,
            modifier = GlanceModifier.size(glyph.dp).cornerRadius(16.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(GlanceModifier.height(10.dp))
        Text(
            text = LocalContext.current.getString(R.string.player_nothing_playing),
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun LyricsBody(snapshot: WidgetSnapshot, cover: Bitmap?, layout: LyricsLayout) {
    if (layout.spreadHeader) {
        // Wide, short cell: header on the left, lyrics filling the rest of the width.
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Header(snapshot, cover, layout, GlanceModifier.width(layout.headerWidthDp.dp))
            Spacer(GlanceModifier.width(12.dp))
            // Wrap-height block: the Row's `CenterVertically` centres it, exactly like the
            // header beside it (the stacked case centres with the weighted spacers above).
            LyricsBlock(snapshot, layout, GlanceModifier.defaultWeight())
        }
        return
    }
    Column(modifier = GlanceModifier.fillMaxSize()) {
        if (layout.showHeader) {
            Header(snapshot, cover, layout)
            Spacer(GlanceModifier.height(6.dp))
        }
        // Balanced band: the weighted spacers above and below centre the fitted lyric
        // block in whatever vertical space is left between the header and the
        // progress/transport. Any rounding slack the row-fit leaves is split evenly above
        // and below the block instead of pooling as a dead band under the last line.
        Spacer(GlanceModifier.defaultWeight())
        LyricsBlock(snapshot, layout, GlanceModifier.fillMaxWidth())
        Spacer(GlanceModifier.defaultWeight())
        if (layout.showProgress) {
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = snapshot.progressFraction(),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(WidgetDimens.progressHeight)
                    .cornerRadius(WidgetDimens.progressCorner),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
        }
        if (layout.showTransport) {
            Spacer(GlanceModifier.height(6.dp))
            TransportRow(snapshot.isPlaying, layout)
        }
    }
}

/** Small cover thumb + song title / artist, at the top of the widget (or beside the lyrics). */
@Composable
private fun Header(
    snapshot: WidgetSnapshot,
    cover: Bitmap?,
    layout: LyricsLayout,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    Row(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val provider = if (cover != null) {
            ImageProvider(cover)
        } else {
            ImageProvider(R.drawable.ic_no_song_cover)
        }
        Image(
            provider = provider,
            contentDescription = snapshot.album,
            modifier = GlanceModifier.size(layout.headerCoverDp.dp).cornerRadius(WidgetDimens.coverCorner),
            contentScale = ContentScale.Crop,
        )
        Spacer(GlanceModifier.width(10.dp))
        Column(
            modifier = GlanceModifier.defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = snapshot.title.ifBlank {
                    LocalContext.current.getString(R.string.player_nothing_playing)
                },
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.titleSp.sp,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = snapshot.artist,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = layout.artistSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * The lyric text block: a [lyricsWindow] of `layout.windowSize` rows with the active line
 * highlighted, the static plain-text fallback, or a centred "No lyrics" label.
 *
 * Each row is a **single** child of the inner `Column` so the window can never be
 * truncated by the RemoteViews 10-child cap (see [WIDGET_COLUMN_MAX_CHILDREN]).
 * `windowSize` is `<=` [WIDGET_COLUMN_MAX_CHILDREN] by construction, and
 * `lyricLinesForHeight` counts exactly what the row pitch really needs, so the block
 * nearly fills the slot it is given and the caller's weighted spacers centre it.
 */
@Composable
private fun LyricsBlock(snapshot: WidgetSnapshot, layout: LyricsLayout, modifier: GlanceModifier) {
    val window = lyricsWindow(snapshot.lyricsLines, snapshot.currentLineIndex, layout.windowSize)
    val a11y = LocalContext.current.getString(R.string.widget_lyrics_content_description)
    if (window.isEmpty()) {
        Box(
            modifier = modifier.semantics { contentDescription = a11y },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = LocalContext.current.getString(R.string.widget_lyrics_no_lyrics),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
                maxLines = 1,
            )
        }
    } else {
        Column(
            modifier = modifier.semantics { contentDescription = a11y },
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            window.forEach { line ->
                LyricRow(line, snapshot.currentLineIndex, layout)
            }
        }
    }
}

/**
 * One lyric row: highlighted when current, fading with distance. The two faded tiers are
 * `onSurface` (nearest neighbour) and `onSurfaceVariant` (further away) — both readable in
 * light **and** dark. The old furthest tier used `outline`, which all but vanished on the
 * dark surface.
 */
@Composable
private fun LyricRow(line: LyricsWindowLine, currentLineIndex: Int, layout: LyricsLayout) {
    val distance = lyricRowDistance(line.index, currentLineIndex)
    val style = when {
        line.isCurrent -> TextStyle(
            color = GlanceTheme.colors.primary,
            fontWeight = FontWeight.Bold,
            fontSize = layout.currentSp.sp,
        )
        distance >= 2 -> TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontWeight = FontWeight.Normal,
            fontSize = layout.neighborSp.sp,
        )
        else -> TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontWeight = FontWeight.Normal,
            fontSize = layout.neighborSp.sp,
        )
    }
    val vertical = if (line.isCurrent) 3.dp else 2.dp
    Text(
        text = clampLyricLine(line.text),
        style = style,
        maxLines = 1,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = vertical, bottom = vertical),
    )
}

/** Centred prev / play-pause / next row, reusing the shared transport button. */
@Composable
private fun TransportRow(isPlaying: Boolean, layout: LyricsLayout) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layout.showSkip) {
            WidgetIconButton(
                iconRes = R.drawable.ic_widget_previous,
                descriptionRes = R.string.player_previous,
                action = previousAction(),
                tint = GlanceTheme.colors.onSurfaceVariant,
                filled = false,
                buttonSize = layout.skipDp.dp,
                iconSize = (layout.skipDp * 0.65f).dp,
            )
            Spacer(GlanceModifier.width(LYRICS_TRANSPORT_GAP_DP.dp))
        }
        WidgetIconButton(
            iconRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            descriptionRes = if (isPlaying) R.string.player_pause else R.string.player_play,
            action = toggleAction(),
            tint = GlanceTheme.colors.onPrimary,
            filled = true,
            buttonSize = layout.playDp.dp,
            iconSize = (layout.playDp * 0.53f).dp,
        )
        if (layout.showSkip) {
            Spacer(GlanceModifier.width(LYRICS_TRANSPORT_GAP_DP.dp))
            WidgetIconButton(
                iconRes = R.drawable.ic_widget_next,
                descriptionRes = R.string.player_next,
                action = nextAction(),
                tint = GlanceTheme.colors.onSurfaceVariant,
                filled = false,
                buttonSize = layout.skipDp.dp,
                iconSize = (layout.skipDp * 0.65f).dp,
            )
        }
    }
}
