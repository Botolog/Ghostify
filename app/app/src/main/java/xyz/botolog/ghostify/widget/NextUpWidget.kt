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
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import xyz.botolog.ghostify.R
import kotlin.math.roundToInt

/**
 * The text-only "Next up" home-screen widget: a peek at the upcoming queue.
 *
 * Pure reader, like every widget here: the cold path renders persisted state + Room
 * (reconstructing a few upcoming rows from the saved queue ids) and never touches the
 * player. Live data is pushed by [WidgetUpdateCoordinator].
 *
 * **No artwork** is ever carried for upcoming items (only text), which is the OOM lesson
 * the snapshot encodes: `NextUpItem` has no bytes or paths at all.
 *
 * Responsive: [SizeMode.Exact] recomposes with the host's real size; [nextUpLayout]
 * derives how many two-line rows fit and drops the header / "Now playing" line as the cell
 * gets shorter. Every row is a single `Column` child and the total stays below the
 * RemoteViews 10-child cap.
 */
class NextUpWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetStateProvider.latest(context)
        provideContent {
            val live by WidgetStateProvider.snapshot.collectAsState()
            val snapshot = live ?: initial
            GlanceTheme(colors = WidgetTheme.colors) {
                NextUpWidgetContent(snapshot)
            }
        }
    }
}

/** Registers [NextUpWidget] with the system; wired in the manifest. */
class NextUpWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextUpWidget()
}

@Composable
private fun NextUpWidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    val hidden = snapshot.hiddenNextUpCount()
    val layout = nextUpLayout(
        widthDp = size.width.value.roundToInt(),
        heightDp = size.height.value.roundToInt(),
        hasMore = hidden > 0 && snapshot.nextUp.isNotEmpty(),
    )
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(WidgetDimens.outerPadding)
            .clickable(actionOpenApp()),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            if (layout.showHeader) {
                Header(snapshot, layout)
            }
            if (layout.showNowPlaying) {
                Text(
                    text = LocalContext.current.getString(R.string.widget_nextup_now_playing, snapshot.title),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = layout.artistSp.sp,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            if (snapshot.nextUp.isEmpty()) {
                EmptyQueue(layout)
            } else {
                snapshot.nextUp.take(layout.rows).forEachIndexed { index, item ->
                    NextUpRow(item, first = index == 0, layout = layout)
                }
                if (layout.showMore) {
                    Text(
                        text = LocalContext.current.getString(R.string.widget_nextup_more, hidden),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurfaceVariant,
                            fontSize = layout.artistSp.sp,
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** "Next up" (left) with the "n of m" track position (right), over a hairline divider. */
@Composable
private fun Header(snapshot: WidgetSnapshot, layout: NextUpLayout) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = LocalContext.current.getString(R.string.widget_nextup_header),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.headerSp.sp,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
            if (snapshot.currentQueueIndex >= 0 && snapshot.queueCount > 0) {
                Text(
                    text = LocalContext.current.getString(
                        R.string.widget_nextup_position,
                        snapshot.currentQueueIndex + 1,
                        snapshot.queueCount,
                    ),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = layout.headerSp.sp,
                    ),
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GlanceTheme.colors.outline),
        ) {}
        Spacer(GlanceModifier.height(6.dp))
    }
}

/** One upcoming track: title over `artist · duration`; the next one is slightly bolder. */
@Composable
private fun NextUpRow(item: NextUpItem, first: Boolean, layout: NextUpLayout) {
    if (layout.dense) {
        NextUpDenseRow(item, first, layout)
        return
    }
    val secondary = buildString {
        append(item.artist)
        if (item.durationMs > 0L) {
            if (isNotEmpty()) append(" · ")
            append(formatTime(item.durationMs))
        }
    }
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Text(
            text = item.title.ifBlank { LocalContext.current.getString(R.string.widget_nextup_untitled) },
            style = TextStyle(
                color = if (first) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                fontWeight = if (first) FontWeight.Bold else FontWeight.Normal,
                fontSize = layout.titleSp.sp,
            ),
            maxLines = 1,
        )
        if (secondary.isNotEmpty()) {
            Text(
                text = secondary,
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
 * The dense, single-line row used on a short cell (see [NextUpLayout.dense]): the title on
 * the left, `artist · duration` right-aligned. Its height is pinned to
 * [NEXT_UP_DENSE_ROW_HEIGHT_DP] so the layout's row budget matches what is drawn exactly —
 * a two-line row here would either overflow the 6x2 or clip its descenders.
 */
@Composable
private fun NextUpDenseRow(item: NextUpItem, first: Boolean, layout: NextUpLayout) {
    val secondary = buildString {
        append(item.artist)
        if (item.durationMs > 0L) {
            if (isNotEmpty()) append(" · ")
            append(formatTime(item.durationMs))
        }
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(NEXT_UP_DENSE_ROW_HEIGHT_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.title.ifBlank { LocalContext.current.getString(R.string.widget_nextup_untitled) },
            style = TextStyle(
                color = if (first) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                fontWeight = if (first) FontWeight.Bold else FontWeight.Normal,
                fontSize = layout.titleSp.sp,
            ),
            maxLines = 1,
        )
        if (secondary.isNotEmpty()) {
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = secondary,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = layout.artistSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Cold/empty queue: a centred label under the header. */
@Composable
private fun EmptyQueue(layout: NextUpLayout) {
    Text(
        text = LocalContext.current.getString(R.string.widget_nextup_empty),
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
            fontSize = layout.titleSp.sp,
        ),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
    )
}
