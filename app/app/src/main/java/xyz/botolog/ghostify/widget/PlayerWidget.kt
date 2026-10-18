package xyz.botolog.ghostify.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import xyz.botolog.ghostify.MainActivity
import xyz.botolog.ghostify.R
import kotlin.math.roundToInt

/**
 * The single "Now Playing" home-screen widget.
 *
 * Rendering is deliberately read-only: [provideGlance] asks [WidgetStateProvider]
 * for a [WidgetSnapshot] and paints it. It never touches `PlayerController`, the
 * `ExoPlayer` or `PlaybackService` — a process started only to draw the widget must
 * not spin up audio. Fresh data is *pushed* in by [WidgetUpdateCoordinator] from the
 * running UI process.
 *
 * **Responsive**: the widget declares [SizeMode.Exact], so Glance re-runs the
 * composition with the host's real size on every resize and exposes it via
 * `androidx.glance.LocalSize`. [PlayerWidgetContent] turns that size (dp) into a
 * [PlayerLayout] via the pure [playerLayout] and paints one of five deliberately
 * distinct compositions — see [PlayerLayoutKind]. The 4x2 cell is the reference:
 * ```
 * ┌───────────────────────────────────────────────┐
 * │ ┌──────┐  Midnight Vessel                      │
 * │ │cover │  Neon Tides                            │
 * │ │ 52dp │                                        │
 * │ └──────┘  ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁ 0:40/1:25 │
 * │           ◀◀   (▶)   ▶▶                         │
 * └───────────────────────────────────────────────┘
 * ```
 * A weighted spacer between the text block and the progress/transport block absorbs any
 * spare height, so the widget reads as balanced top-to-bottom instead of top-aligned.
 *
 * Transport buttons are Glance [androidx.glance.appwidget.action.ActionCallback]s
 * (see [toggleAction]/[nextAction]/[previousAction]); tapping the body opens
 * [MainActivity].
 */
class PlayerWidget : GlanceAppWidget() {

    /**
     * Compose at the exact host size and recompose on every resize.
     *
     * `SizeMode.Exact` asks Glance to build the RemoteViews for the current AppWidget
     * options and to re-run `provideGlance`'s composition when those options change
     * (`GlanceAppWidgetReceiver.onAppWidgetOptionsChanged` → `resize`). That is what makes
     * `LocalSize` inside [PlayerWidgetContent] always equal the real cell, instead of the
     * single size the widget was first pinned at.
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Live snapshot when the UI process is up, otherwise persisted-state + Room.
        val initial = WidgetStateProvider.latest(context)
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
                PlayerWidgetContent(snapshot, cover)
            }
        }
    }
}

/**
 * Decodes snapshot cover bytes into a [Bitmap] for Glance, or `null` when there is none.
 *
 * Shared by every widget (the host process cannot read our private cover files, so
 * artwork travels as bytes in the snapshot and is decoded here, off the composition
 * thread).
 */
internal fun decodeCover(bytes: ByteArray?): Bitmap? =
    bytes?.takeIf { it.isNotEmpty() }?.let {
        runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
    }

/** Registers [PlayerWidget] with the system; wired in the manifest. */
class PlayerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlayerWidget()
}

// ── Composition ───────────────────────────────────────────────────────

@Composable
private fun PlayerWidgetContent(snapshot: WidgetSnapshot, cover: Bitmap?) {
    val size = LocalSize.current
    val layout = playerLayout(
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
            EmptyState(layout)
        } else {
            NowPlaying(snapshot, cover, layout)
        }
    }
}

/** Cold-start / "nothing playing" state, laid out to suit the current breakpoint. */
@Composable
private fun EmptyState(layout: PlayerLayout) {
    val label = LocalContext.current.getString(R.string.player_nothing_playing)
    val glyph = layout.coverDp.coerceIn(32, 56)
    if (layout.kind == PlayerLayoutKind.COMPACT || layout.kind == PlayerLayoutKind.SHORT) {
        // Narrow/short cells cannot hold a glyph beside the label — stack them.
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_no_song_cover),
                contentDescription = null,
                modifier = GlanceModifier.size(glyph.dp).cornerRadius(WidgetDimens.coverCorner),
                contentScale = ContentScale.Fit,
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }
    } else {
        Row(
            modifier = GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_no_song_cover),
                contentDescription = null,
                modifier = GlanceModifier.size(glyph.dp).cornerRadius(WidgetDimens.coverCorner),
                contentScale = ContentScale.Fit,
            )
            Spacer(GlanceModifier.width(14.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * Dispatches to the composition [playerLayout] selected for the current size. Every branch
 * is a *fixed* tree (no unbounded children) and every `Column`/`Row` stays well under the
 * RemoteViews 10-direct-children cap.
 */
@Composable
private fun NowPlaying(snapshot: WidgetSnapshot, cover: Bitmap?, layout: PlayerLayout) {
    when (layout.kind) {
        PlayerLayoutKind.SHORT -> ShortPlayer(snapshot, cover, layout)
        PlayerLayoutKind.COMPACT -> CompactPlayer(snapshot, cover, layout)
        PlayerLayoutKind.WIDE -> WidePlayer(snapshot, cover, layout)
        PlayerLayoutKind.TALL -> TallPlayer(snapshot, cover, layout)
        PlayerLayoutKind.STANDARD -> StandardPlayer(snapshot, cover, layout)
    }
}

/** 1-row: cover, title + artist, play/pause (+ prev/next when there is room). No progress. */
@Composable
private fun ShortPlayer(snapshot: WidgetSnapshot, cover: Bitmap?, layout: PlayerLayout) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(snapshot, cover, layout.coverDp)
        Spacer(GlanceModifier.width(8.dp))
        if (layout.showText) {
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start,
            ) {
                TitleText(snapshot, layout.titleSp)
                if (layout.showArtist) {
                    ArtistText(snapshot.artist, layout.artistSp)
                }
            }
        } else {
            // No room for text at all (e.g. 2x1): push the transport to the trailing edge
            // so the row fills the cell instead of clinging to the cover.
            Spacer(GlanceModifier.defaultWeight())
        }
        if (layout.showSkip) {
            Spacer(GlanceModifier.width(4.dp))
            SkipButton(R.drawable.ic_widget_previous, R.string.player_previous, previousAction(), layout.skipDp)
        }
        Spacer(GlanceModifier.width(6.dp))
        PlayButton(snapshot.isPlaying, layout.playDp)
        if (layout.showSkip) {
            Spacer(GlanceModifier.width(4.dp))
            SkipButton(R.drawable.ic_widget_next, R.string.player_next, nextAction(), layout.skipDp)
        }
    }
}

/** Small square (2x2): cover, one title line, a single play/pause button. */
@Composable
private fun CompactPlayer(snapshot: WidgetSnapshot, cover: Bitmap?, layout: PlayerLayout) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(snapshot, cover, layout.coverDp)
        Spacer(GlanceModifier.height(3.dp))
        TitleText(snapshot, layout.titleSp)
        Spacer(GlanceModifier.height(5.dp))
        PlayButton(snapshot.isPlaying, layout.playDp)
    }
}

/** The tuned 4x2 reference: cover beside a text column over progress + transport. */
@Composable
private fun StandardPlayer(snapshot: WidgetSnapshot, cover: Bitmap?, layout: PlayerLayout) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(snapshot, cover, layout.coverDp)
        Spacer(GlanceModifier.width(10.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            TitleText(snapshot, layout.titleSp, GlanceModifier.fillMaxWidth())
            ArtistText(snapshot.artist, layout.artistSp, GlanceModifier.fillMaxWidth())
            // Absorbs any spare height so the text sits at the top and the
            // progress + transport sit at the bottom.
            Spacer(GlanceModifier.defaultWeight())
            ProgressBar(snapshot)
            Spacer(GlanceModifier.height(2.dp))
            TimesTransportRow(snapshot, layout)
        }
    }
}

/** Wide cells (6x2): bigger cover, `artist · album` on one line, elapsed/duration spread. */
@Composable
private fun WidePlayer(snapshot: WidgetSnapshot, cover: Bitmap?, layout: PlayerLayout) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(snapshot, cover, layout.coverDp)
        Spacer(GlanceModifier.width(14.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.Start,
        ) {
            TitleText(snapshot, layout.titleSp, GlanceModifier.fillMaxWidth())
            // The album rides on the artist line: a third stacked row would not fit the
            // 77dp content box and clipped the transport (see [PlayerLayoutKind.WIDE]).
            ArtistText(
                text = playerSecondaryLine(snapshot.artist, snapshot.album),
                sp = layout.artistSp,
                modifier = GlanceModifier.fillMaxWidth(),
            )
            Spacer(GlanceModifier.defaultWeight())
            ProgressBar(snapshot)
            Spacer(GlanceModifier.height(4.dp))
            TimesTransportRow(snapshot, layout)
        }
    }
}

/**
 * Tall cells (4x3, 4x4): scaled-up, stacked, and spread top-to-bottom so the extra height
 * is used by rhythm rather than left as dead space.
 */
@Composable
private fun TallPlayer(snapshot: WidgetSnapshot, cover: Bitmap?, layout: PlayerLayout) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverArt(snapshot, cover, layout.coverDp)
        Spacer(GlanceModifier.height(10.dp))
        // Title / artist / album stack with no Spacer children between them so the
        // Column stays comfortably under the RemoteViews 10-child cap.
        TitleText(snapshot, layout.titleSp)
        ArtistText(snapshot.artist, layout.artistSp)
        if (layout.showAlbum) {
            AlbumText(snapshot, layout.albumSp)
        }
        // Pushes the progress + transport block to the bottom, filling the height.
        Spacer(GlanceModifier.defaultWeight())
        ProgressBar(snapshot)
        Spacer(GlanceModifier.height(6.dp))
        TimesTransportRow(snapshot, layout)
    }
}

// ── Shared building blocks ────────────────────────────────────────────

@Composable
private fun CoverArt(snapshot: WidgetSnapshot, cover: Bitmap?, sizeDp: Int) {
    val provider = when {
        cover != null -> ImageProvider(cover)
        else -> ImageProvider(R.drawable.ic_no_song_cover)
    }
    Image(
        provider = provider,
        contentDescription = snapshot.album,
        modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(WidgetDimens.coverCorner),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun TitleText(snapshot: WidgetSnapshot, sp: Int, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = snapshot.title.ifBlank { LocalContext.current.getString(R.string.player_nothing_playing) },
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = sp.sp,
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun ArtistText(
    text: String,
    sp: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = sp.sp,
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun AlbumText(snapshot: WidgetSnapshot, sp: Int, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = snapshot.album.orEmpty(),
        style = TextStyle(
            color = GlanceTheme.colors.outline,
            fontSize = sp.sp,
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun ProgressBar(snapshot: WidgetSnapshot) {
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

/**
 * Elapsed/duration + transport. In "spread" mode (wide/tall) the elapsed time is pushed to
 * the left edge and the duration to the right, with the transport centred between them;
 * otherwise it is the compact `elapsed / duration` cluster beside a right-aligned transport.
 */
@Composable
private fun TimesTransportRow(snapshot: WidgetSnapshot, layout: PlayerLayout) {
    if (!layout.showTimes) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportRow(snapshot.isPlaying, layout)
        }
        return
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layout.spreadTimes) {
            TimeText(formatTime(snapshot.positionMs), layout.timeSp)
            Spacer(GlanceModifier.defaultWeight())
            TransportRow(snapshot.isPlaying, layout)
            Spacer(GlanceModifier.defaultWeight())
            TimeText(formatTime(snapshot.durationMs), layout.timeSp)
        } else {
            TimeText(
                "${formatTime(snapshot.positionMs)} / ${formatTime(snapshot.durationMs)}",
                layout.timeSp,
            )
            Spacer(GlanceModifier.defaultWeight())
            TransportRow(snapshot.isPlaying, layout)
        }
    }
}

@Composable
private fun TimeText(text: String, sp: Int) {
    Text(
        text = text,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = sp.sp,
        ),
        maxLines = 1,
    )
}

/** Prev / play-pause / next at the sizes the breakpoint asked for. */
@Composable
private fun TransportRow(isPlaying: Boolean, layout: PlayerLayout) {
    Row(
        horizontalAlignment = Alignment.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layout.showSkip) {
            SkipButton(R.drawable.ic_widget_previous, R.string.player_previous, previousAction(), layout.skipDp)
            Spacer(GlanceModifier.width(2.dp))
        }
        PlayButton(isPlaying, layout.playDp)
        if (layout.showSkip) {
            Spacer(GlanceModifier.width(2.dp))
            SkipButton(R.drawable.ic_widget_next, R.string.player_next, nextAction(), layout.skipDp)
        }
    }
}

@Composable
private fun PlayButton(isPlaying: Boolean, sizeDp: Int) {
    WidgetIconButton(
        iconRes = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        descriptionRes = if (isPlaying) R.string.player_pause else R.string.player_play,
        action = toggleAction(),
        tint = GlanceTheme.colors.onPrimary,
        filled = true,
        buttonSize = sizeDp.dp,
        iconSize = (sizeDp * 0.56f).dp,
    )
}

@Composable
private fun SkipButton(iconRes: Int, descriptionRes: Int, action: Action, sizeDp: Int) {
    WidgetIconButton(
        iconRes = iconRes,
        descriptionRes = descriptionRes,
        action = action,
        tint = GlanceTheme.colors.onSurfaceVariant,
        filled = false,
        buttonSize = sizeDp.dp,
        iconSize = (sizeDp * 0.74f).dp,
    )
}

/** Shared circular transport button; used by both the player and lyrics widgets. */
@Composable
internal fun WidgetIconButton(
    iconRes: Int,
    descriptionRes: Int,
    action: Action,
    tint: androidx.glance.unit.ColorProvider,
    filled: Boolean,
    buttonSize: Dp,
    iconSize: Dp = if (filled) 20.dp else 22.dp,
) {
    val base = GlanceModifier.size(buttonSize).clickable(action)
    val styled = if (filled) {
        base.background(GlanceTheme.colors.primary).cornerRadius(buttonSize / 2)
    } else {
        base
    }
    Box(modifier = styled, contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = LocalContext.current.getString(descriptionRes),
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** Opens the app (and its full player) when a widget body is tapped. */
internal fun actionOpenApp(): Action = actionStartActivity<MainActivity>()
