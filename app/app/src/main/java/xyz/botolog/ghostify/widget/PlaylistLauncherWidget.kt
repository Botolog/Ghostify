package xyz.botolog.ghostify.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import xyz.botolog.ghostify.R
import kotlin.math.roundToInt

/**
 * The "Playlists" launcher-tiles home-screen widget.
 *
 * A small grid of the user's playlists; tapping a tile starts that playlist's playback.
 *
 * Pure reader on the cold path, like every widget here: it paints the tile list
 * ([PlaylistTile]s) and never touches the player. A tile tap runs
 * [PlaylistTileActionCallback], which reaches the container to build the queue exactly like
 * `PlaylistDetailViewModel.playAll()` does (same `getDownloadedSongsBatch` + `toPlayerSong`
 * + `playPlaylistLazy` path). See that callback for the behaviour and its limits.
 *
 * **Bounded artwork.** This is the one widget that carries more than one image, so the cap
 * is deliberate and enforced twice: [PLAYLIST_TILE_CAP] tiles at most (see [playlistLayout]),
 * and each cover is decoded down to a tiny JPEG by `WidgetCovers` before it reaches the
 * snapshot. The "no artwork for queue items" rule still holds — there is no artwork for the
 * *queue*, only ≤4 small per-playlist covers.
 *
 * Responsive: [SizeMode.Exact] recomposes with the host's real size; [playlistLayout] derives
 * the tile grid (columns from width, rows from height, product capped at [PLAYLIST_TILE_CAP])
 * and drops the header / covers as the cell shrinks. Every row is one `Column` child, so the
 * composition stays under the RemoteViews 10-child cap.
 */
class PlaylistLauncherWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetStateProvider.latestPlaylists(context)
        provideContent {
            val live by WidgetStateProvider.playlists.collectAsState()
            val tiles = live ?: initial
            GlanceTheme(colors = WidgetTheme.colors) {
                PlaylistLauncherContent(tiles)
            }
        }
    }
}

/** Registers [PlaylistLauncherWidget] with the system; wired in the manifest. */
class PlaylistLauncherWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistLauncherWidget()
}

@Composable
private fun PlaylistLauncherContent(tiles: List<PlaylistTile>) {
    val size = LocalSize.current
    val layout = playlistLayout(
        widthDp = size.width.value.roundToInt(),
        heightDp = size.height.value.roundToInt(),
        tileCount = tiles.size,
    )
    val shown = tiles.take(layout.tiles)
    // Decode only when the tile set changes (the host cannot read our private cover files,
    // so the art travels as small bytes; see PlaylistTile).
    val covers = remember(shown) { shown.map { decodeCover(it.coverBytes) } }
    val indexed = shown.withIndex().toList()
    val rows = if (layout.columns > 0) indexed.chunked(layout.columns) else emptyList()

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(WidgetDimens.outerPadding)
            .clickable(actionOpenApp()),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            if (layout.showHeader) {
                // The header carries the overflow count ("+2") on the trailing edge, so
                // playlists that do not fit are marked instead of silently hidden — at no
                // extra height, wherever the header is on screen.
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = LocalContext.current.getString(R.string.widget_playlist_header),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = layout.headerSp.sp,
                        ),
                        maxLines = 1,
                    )
                    if (layout.overflowCount > 0) {
                        Spacer(GlanceModifier.defaultWeight())
                        Text(
                            text = LocalContext.current.getString(
                                R.string.widget_playlist_more,
                                layout.overflowCount,
                            ),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                fontSize = layout.headerSp.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (rows.isEmpty()) {
                // Centre the empty message in the space the grid would have filled, so a tall
                // empty cell (4x4) does not pool ~180dp of dead card under a top-pinned line.
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = LocalContext.current.getString(R.string.widget_playlist_empty),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        fontSize = layout.nameSp.sp,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
                Spacer(GlanceModifier.defaultWeight())
            } else {
                rows.forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) Spacer(GlanceModifier.height(layout.tileGapDp.dp))
                    PlaylistRow(
                        row = row,
                        covers = covers,
                        layout = layout,
                    )
                }
                // Headerless cells have no header line to carry the count, so the overflow
                // got its own reserved line under the grid (see [playlistLayout]).
                if (!layout.showHeader && layout.overflowCount > 0) {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = LocalContext.current.getString(
                            R.string.widget_playlist_more,
                            layout.overflowCount,
                        ),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                            fontSize = layout.nameSp.sp,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * One tile row. The row is pinned to [PlaylistLayout.rowHeightDp] and centres its tiles, so
 * the rows plus their gaps fill the grid's free height instead of pooling a band at the
 * bottom. Tiles keep an equal share of the width; a short final row is padded with weighted
 * spacers so its tiles stay the same size instead of stretching.
 */
@Composable
private fun PlaylistRow(
    row: List<IndexedValue<PlaylistTile>>,
    covers: List<Bitmap?>,
    layout: PlaylistLayout,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(layout.rowHeightDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.forEachIndexed { i, entry ->
            if (i > 0) Spacer(GlanceModifier.width(layout.tileGapDp.dp))
            PlaylistTileView(entry.value, covers[entry.index], layout, GlanceModifier.defaultWeight())
        }
        repeat(layout.columns - row.size) {
            Spacer(GlanceModifier.width(layout.tileGapDp.dp))
            Spacer(GlanceModifier.defaultWeight())
        }
    }
}

/**
 * One playlist. On a short covered row the rounded cover sits beside the name; on a tall one
 * ([PlaylistLayout.stacked]) it sits above a full-width, up-to-two-line name so the whole
 * label reads and the tile fills its row exactly. Text-only tiles are just the name + count.
 */
@Composable
private fun PlaylistTileView(
    tile: PlaylistTile,
    cover: Bitmap?,
    layout: PlaylistLayout,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val name = tile.name.ifBlank { context.getString(R.string.widget_playlist_untitled) }
    val action = modifier
        .clickable(playlistTileAction(tile.id))
        .semantics { contentDescription = context.getString(R.string.widget_playlist_play, name) }
    val nameText = ellipsize(name, layout.nameChars * layout.nameMaxLines)
    val countText = ellipsize(
        context.getString(
            if (tile.trackCount <= 0) R.string.library_track_count_zero else R.string.library_track_count,
            tile.trackCount,
        ),
        layout.countChars,
    )
    if (layout.stacked) {
        Column(
            modifier = action.height(layout.rowHeightDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TileCover(cover, layout.coverDp)
            Spacer(GlanceModifier.height(4.dp))
            Text(
                // Glance 1.1's Text has no overflow, so truncate explicitly to the width.
                text = nameText,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.nameSp.sp,
                    textAlign = TextAlign.Center,
                ),
                maxLines = layout.nameMaxLines,
                modifier = GlanceModifier.fillMaxWidth(),
            )
            if (layout.showTileCount) {
                Text(
                    text = countText,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = layout.countSp.sp,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
        return
    }
    Row(
        modifier = action,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layout.showCover) {
            TileCover(cover, layout.coverDp)
            Spacer(GlanceModifier.width(layout.tileGapDp.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = nameText,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.nameSp.sp,
                ),
                maxLines = layout.nameMaxLines,
                modifier = GlanceModifier.fillMaxWidth(),
            )
            if (layout.showTileCount) {
                // Same explicit truncation as the name: the count shares the text column
                // with it, and on a covered tile that column can be narrow.
                Text(
                    text = countText,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = layout.countSp.sp,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A tile's rounded cover thumb, or the playlist glyph on a muted square when art is absent. */
@Composable
private fun TileCover(cover: Bitmap?, sizeDp: Int) {
    val radius = (sizeDp / 5).dp
    if (cover != null) {
        Image(
            provider = ImageProvider(cover),
            contentDescription = null,
            modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(radius),
            contentScale = ContentScale.Crop,
        )
        return
    }
    Box(
        modifier = GlanceModifier
            .size(sizeDp.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(radius),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_playlist),
            contentDescription = null,
            modifier = GlanceModifier.size((sizeDp * 56 / 100).dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
    }
}
