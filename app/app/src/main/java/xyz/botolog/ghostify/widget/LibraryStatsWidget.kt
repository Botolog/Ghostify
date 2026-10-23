package xyz.botolog.ghostify.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.unit.ColorProvider
import xyz.botolog.ghostify.R
import xyz.botolog.ghostify.data.model.LibraryStats
import xyz.botolog.ghostify.sync.SyncStatus
import xyz.botolog.ghostify.sync.SyncStatusHolder
import kotlin.math.roundToInt

/**
 * The "Library" home-screen widget: global sync status + library stats.
 *
 * Two readers, both pure:
 *  - the library aggregates ([LibraryStats]) come from [WidgetStateProvider.stats] (live) or
 *    a cold read of Room's `COUNT(*)` queries ([WidgetStateProvider.latestStats]) — never the
 *    player;
 *  - the sync readout comes from the process-wide [SyncStatusHolder] (live) or the persisted
 *    `SettingsRepository` timestamp ([WidgetStateProvider.latestLastSyncedAt]) on a cold
 *    start, because a widget-only process never runs a sync and so has no in-memory history.
 *    See [SyncStatusHolder] for why the flag is live-only and the timestamp is persisted.
 *
 * Responsive: [SizeMode.Exact] recomposes with the host's real size; [statsLayout] drops the
 * header, the dedicated sync line and the progress block as the cell shrinks and trims the
 * figures to the space that is actually left. Every line is truncated to its own character
 * budget because Glance 1.1's `Text` has no overflow. Each stat row is one `Column` child, so
 * the composition stays under the RemoteViews 10-child cap.
 */
class LibraryStatsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialStats = WidgetStateProvider.latestStats(context)
        val initialSyncedAt = WidgetStateProvider.latestLastSyncedAt(context)
        provideContent {
            val liveStats by WidgetStateProvider.stats.collectAsState()
            val liveSync by SyncStatusHolder.state.collectAsState()
            val stats = liveStats ?: initialStats
            // Prefer the live process's timestamp; fall back to the cold Room read so a
            // widget-only process still shows "last synced" instead of "never synced".
            val sync = liveSync.copy(lastSyncedAtMs = liveSync.lastSyncedAtMs ?: initialSyncedAt)
            GlanceTheme(colors = WidgetTheme.colors) {
                LibraryStatsContent(stats, sync)
            }
        }
    }
}

/** Registers [LibraryStatsWidget] with the system; wired in the manifest. */
class LibraryStatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LibraryStatsWidget()
}

@Composable
private fun LibraryStatsContent(stats: LibraryStats, sync: SyncStatus) {
    val size = LocalSize.current
    val layout = statsLayout(
        widthDp = size.width.value.roundToInt(),
        heightDp = size.height.value.roundToInt(),
    )
    val now = System.currentTimeMillis()
    val kind = syncLabelKind(sync.isSyncing, sync.lastSyncedAtMs, now)
    val syncText = syncTextFor(LocalContext.current, kind, sync.lastSyncedAtMs, now, short = false)
    // On a narrow cell the readout has only ~74dp wherever it sits — the header badge on a
    // 3x3 or the dedicated line on a 2x4 — so it drops the leading "Synced" and shortens
    // "just now" rather than being cut mid-word ("Synced 3 min…"). A wider cell keeps the
    // full readout, whether it rides the badge or its own line.
    val syncTextShort =
        if (layout.shortSync) {
            syncTextFor(LocalContext.current, kind, sync.lastSyncedAtMs, now, short = true)
        } else {
            syncText
        }
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(WidgetDimens.outerPadding)
            .clickable(actionOpenApp()),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize(),
            // Centring the column turns whatever height the blocks do not use into an even
            // top/bottom margin, instead of the lopsided band a top-aligned tall cell left
            // under the figures (the old 4x4 pooled ~77dp below "980 of 1,284"). The
            // progress-only tall cells (4x3/6x3) pool a band above AND below when centred,
            // so they pin to the top instead (see [StatsLayout.topAlign]).
            verticalAlignment = if (layout.topAlign) Alignment.Top else Alignment.CenterVertically,
        ) {
            if (layout.showHeader) {
                HeaderRow(layout, kind, syncTextShort)
            }
            if (layout.singleLine) {
                // The shortest headerless cells: figures, the sync-state dot and (where it
                // fits) the short readout share ONE line, instead of a dedicated sync line
                // consuming the whole height and dropping every figure.
                SingleLineRow(stats, kind, syncTextShort, layout)
            } else {
                if (layout.showSyncLine) {
                    Text(
                        text = ellipsize(syncTextShort, layout.syncChars),
                        style = TextStyle(
                            color = syncColor(kind),
                            fontWeight = FontWeight.Medium,
                            fontSize = layout.syncSp.sp,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp),
                    )
                }
                layout.statRows.forEach { row -> StatRow(row, stats, layout) }
            }
            if (layout.showProgress) {
                ProgressBlock(stats, layout)
            }
        }
    }
}

/** "Library" (left) with the sync badge (right, carrying its words when the width allows). */
@Composable
private fun HeaderRow(layout: StatsLayout, kind: SyncLabelKind, syncText: String) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = LocalContext.current.getString(R.string.widget_library_header),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = layout.headerSp.sp,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())
        SyncBadge(layout, kind, syncText)
    }
}

/**
 * A coloured state dot plus, on a wide enough cell ([StatsLayout.showSyncText]), the readout
 * — already in its short form when the cell is narrow ([StatsLayout.shortSync]).
 */
@Composable
private fun SyncBadge(layout: StatsLayout, kind: SyncLabelKind, syncText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(syncColor(kind))
                .cornerRadius(4.dp),
        ) {}
        if (layout.showSyncText) {
            Spacer(GlanceModifier.width(5.dp))
            Text(
                text = ellipsize(syncText, layout.badgeChars),
                style = TextStyle(
                    color = syncColor(kind),
                    fontWeight = FontWeight.Medium,
                    fontSize = layout.syncSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** One line of figures, e.g. `1,234 songs · 980 downloaded`. Always one `Column` child. */
@Composable
private fun StatRow(kinds: List<LibraryStatKind>, stats: LibraryStats, layout: StatsLayout) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        kinds.forEachIndexed { index, kind ->
            if (index > 0) {
                Text(
                    text = LocalContext.current.getString(R.string.widget_library_stat_separator),
                    style = TextStyle(
                        color = GlanceTheme.colors.outline,
                        fontSize = layout.statSp.sp,
                    ),
                    maxLines = 1,
                )
            }
            StatFigure(kind, stats, layout, GlanceModifier.defaultWeight())
        }
    }
}

/**
 * The 1-row cell's combined readout: the sync-state dot, the figures in priority order and
 * (when the short readout fits after them) the short sync words. Parts are dropped from the
 * **end** until the line fits its budget, so the figures are never replaced by a fragmentary
 * readout — this is only why the 3x1 lost every figure before.
 */
@Composable
private fun SingleLineRow(
    stats: LibraryStats,
    kind: SyncLabelKind,
    syncTextShort: String,
    layout: StatsLayout,
) {
    val context = LocalContext.current
    val separator = context.getString(R.string.widget_library_stat_separator)
    val parts = buildList {
        layout.statItems.forEach { add(statText(it, stats, context, short = true)) }
        if (layout.syncOnLine) add(syncTextShort)
    }
    var shown = parts
    while (shown.size > 1 &&
        shown.joinToString(separator).length > layout.singleLineChars
    ) {
        shown = shown.dropLast(1)
    }
    val text = if (shown.isEmpty()) "" else ellipsize(shown.joinToString(separator), layout.singleLineChars)
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .background(syncColor(kind))
                .cornerRadius(4.dp),
        ) {}
        Spacer(GlanceModifier.width(4.dp))
        if (text.isNotBlank()) {
            Text(
                text = text,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.statSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** One figure; the headline song count is emphasised. Takes an equal share of its row. */
@Composable
private fun StatFigure(
    kind: LibraryStatKind,
    stats: LibraryStats,
    layout: StatsLayout,
    modifier: GlanceModifier,
) {
    val primary = kind == LibraryStatKind.SONGS
    // Prefer the full label; fall back to the short one when it (not just the number) would
    // be cut. This keeps "980 downloaded" whole on a 4x2 but paints "9,876 dl" beside the
    // separator on the same cell, where the full label is a character too wide.
    val context = LocalContext.current
    val full = statText(kind, stats, context, short = false)
    val text = if (full.length <= layout.statItemChars) {
        full
    } else {
        statText(kind, stats, context, short = true)
    }
    Text(
        text = ellipsize(text, layout.statItemChars),
        style = TextStyle(
            color = if (primary) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            fontSize = layout.statSp.sp,
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

/** The downloaded-progress block: the tall-cell filler (hero percentage, bar, counts). */
@Composable
private fun ProgressBlock(stats: LibraryStats, layout: StatsLayout) {
    val context = LocalContext.current
    val percent = context.getString(R.string.widget_library_percent, stats.downloadedPercent)
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp)) {
        if (layout.showHeroPercent) {
            Text(
                text = percent,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.percentSp.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
        LinearProgressIndicator(
            progress = stats.downloadedFraction,
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(WidgetDimens.progressHeight)
                .cornerRadius(WidgetDimens.progressCorner),
            color = GlanceTheme.colors.primary,
            backgroundColor = GlanceTheme.colors.surfaceVariant,
        )
        Spacer(GlanceModifier.height(3.dp))
        if (layout.showHeroPercent) {
            Text(
                text = context.getString(
                    R.string.widget_library_downloaded_of,
                    formatCount(stats.downloadedSongs, context),
                    formatCount(stats.totalSongs, context),
                ),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = layout.syncSp.sp,
                ),
                maxLines = 1,
                modifier = GlanceModifier.fillMaxWidth(),
            )
        } else {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (layout.progressLabel) {
                    Text(
                        text = context.getString(R.string.widget_library_downloaded_label),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = layout.syncSp.sp,
                        ),
                        maxLines = 1,
                    )
                    // A fixed 6dp gutter *plus* the weighted one, so the label and the figure
                    // never touch: the 2x3 (93dp) collapsed the weight to nothing and read
                    // "Downloaded76%". On a narrower cell the label is dropped entirely
                    // (see [StatsLayout.progressLabel]) and only the weighted spacer remains.
                    Spacer(GlanceModifier.width(6.dp))
                }
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = percent,
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = layout.syncSp.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Locale-aware grouped count, using the host's first locale. */
private fun formatCount(count: Int, context: Context): String =
    formatStatCount(count, context.resources.configuration.locales[0])

/**
 * The sync readout for [kind]; for [SyncLabelKind.AGO] the age is prefixed in.
 *
 * [short] drops the leading "Synced" (and shortens "just now") for the narrow dedicated line,
 * whose ~14-character budget cannot hold "Synced 3 min ago".
 */
private fun syncTextFor(
    context: Context,
    kind: SyncLabelKind,
    lastSyncedAtMs: Long?,
    nowMs: Long,
    short: Boolean,
): String = when (kind) {
    SyncLabelKind.SYNCING -> context.getString(R.string.widget_library_syncing)
    SyncLabelKind.NEVER -> context.getString(R.string.widget_library_never_synced)
    SyncLabelKind.JUST_NOW -> context.getString(
        if (short) R.string.widget_library_synced_just_now_short else R.string.widget_library_synced_just_now,
    )
    SyncLabelKind.AGO -> {
        val age = syncAgeParts(nowMs - (lastSyncedAtMs ?: nowMs))
        val unit = when (age.unit) {
            SyncAgeUnit.MINUTES -> R.string.widget_library_ago_minutes
            SyncAgeUnit.HOURS -> R.string.widget_library_ago_hours
            SyncAgeUnit.DAYS -> R.string.widget_library_ago_days
        }
        context.getString(
            if (short) R.string.widget_library_synced_ago_short else R.string.widget_library_synced_ago,
            context.getString(unit, age.value),
        )
    }
}

/** Badge / dot colour for the sync state. */
@Composable
private fun syncColor(kind: SyncLabelKind): ColorProvider = when (kind) {
    SyncLabelKind.SYNCING, SyncLabelKind.JUST_NOW -> GlanceTheme.colors.primary
    SyncLabelKind.AGO -> GlanceTheme.colors.onSurfaceVariant
    SyncLabelKind.NEVER -> GlanceTheme.colors.outline
}

/** Figure label for [kind] with the count formatted for the host's locale. */
private fun statText(kind: LibraryStatKind, stats: LibraryStats, context: Context, short: Boolean): String {
    val full = !short
    return when (kind) {
        LibraryStatKind.SONGS ->
            context.getString(R.string.widget_library_stat_songs, formatCount(stats.totalSongs, context))
        LibraryStatKind.DOWNLOADED -> context.getString(
            if (full) R.string.widget_library_stat_downloaded else R.string.widget_library_stat_downloaded_short,
            formatCount(stats.downloadedSongs, context),
        )
        LibraryStatKind.LYRICS -> context.getString(
            if (full) R.string.widget_library_stat_lyrics else R.string.widget_library_stat_lyrics_short,
            formatCount(stats.songsWithLyrics, context),
        )
        LibraryStatKind.PLAYLISTS -> context.getString(
            if (full) R.string.widget_library_stat_playlists else R.string.widget_library_stat_playlists_short,
            formatCount(stats.playlists, context),
        )
    }
}
