package xyz.botolog.ghostify.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 * The "Shuffle Roulette" home-screen widget.
 *
 * A slot-machine-style card: the current track, a shuffle-state readout and a prominent
 * SPIN button (with a small shuffle toggle beside it when the cell is wide enough).
 *
 * Pure reader on the cold path, like every widget here — it paints a [WidgetSnapshot] and
 * never touches the player. The spin itself runs only on tap, via
 * [ShuffleRouletteActionCallback], which reaches the container to shuffle + jump (or, on
 * an empty queue, start one random downloaded track). See that callback for the exact
 * behaviour and its documented limits.
 *
 * Responsive: [SizeMode.Exact] recomposes with the host's real size; [rouletteLayout]
 * shrinks the SPIN button from the height that is actually left and drops the header /
 * artist / toggle as the cell gets smaller.
 */
class ShuffleRouletteWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetStateProvider.latest(context)
        provideContent {
            val live by WidgetStateProvider.snapshot.collectAsState()
            val snapshot = live ?: initial
            GlanceTheme(colors = WidgetTheme.colors) {
                ShuffleRouletteWidgetContent(snapshot)
            }
        }
    }
}

/** Registers [ShuffleRouletteWidget] with the system; wired in the manifest. */
class ShuffleRouletteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShuffleRouletteWidget()
}

@Composable
private fun ShuffleRouletteWidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    val layout = rouletteLayout(
        widthDp = size.width.value.roundToInt(),
        heightDp = size.height.value.roundToInt(),
    )
    // Without the header the whole card is a centred stack (status readout, title, SPIN);
    // with it, the text block stays left-aligned under the header like the other widgets.
    val centered = !layout.showHeader
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(WidgetDimens.outerPadding)
            .clickable(actionOpenApp()),
    ) {
        if (layout.spread) {
            // Wide, short cell (6x2): the text block and SPIN sit side by side so the extra
            // width is filled instead of leaving a centred stack marooned between two gutters.
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = GlanceModifier.defaultWeight(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    ShuffleStateText(snapshot, layout)
                    TrackText(snapshot, layout, fill = true)
                }
                Spacer(GlanceModifier.width(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpinButton(layout, grow = false)
                    if (layout.showShuffleToggle) {
                        Spacer(GlanceModifier.width(12.dp))
                        ShuffleToggle(layout, on = snapshot.shuffleEnabled)
                    }
                }
            }
        } else {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                StatusRow(snapshot, layout)
                Spacer(GlanceModifier.defaultWeight())
                TrackText(snapshot, layout, fill = !centered)
                Spacer(GlanceModifier.defaultWeight())
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpinButton(layout, grow = !layout.showShuffleToggle)
                    if (layout.showShuffleToggle) {
                        Spacer(GlanceModifier.width(12.dp))
                        ShuffleToggle(layout, on = snapshot.shuffleEnabled)
                    }
                }
                if (layout.showHint) {
                    // A tall cell cannot spend all its spare height on SPIN alone, so a quiet
                    // hint sits under the button instead of leaving a lopsided band above the
                    // title.
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = LocalContext.current.getString(R.string.widget_roulette_idle),
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurfaceVariant,
                                fontSize = 11.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The current track (title + artist), the block that the centred / spread shapes place. */
@Composable
private fun TrackText(snapshot: WidgetSnapshot, layout: RouletteLayout, fill: Boolean) {
    if (layout.showTitle) {
        Text(
            text = snapshot.title.ifBlank {
                LocalContext.current.getString(R.string.widget_roulette_idle)
            },
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = layout.titleSp.sp,
            ),
            maxLines = 1,
            modifier = if (fill) GlanceModifier.fillMaxWidth() else GlanceModifier,
        )
    }
    if (layout.showArtist && snapshot.artist.isNotBlank()) {
        Text(
            text = snapshot.artist,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = layout.artistSp.sp,
            ),
            maxLines = 1,
            modifier = if (fill) GlanceModifier.fillMaxWidth() else GlanceModifier,
        )
    }
}

/** "SHUFFLE ON" / "SHUFFLE OFF", tinted `primary` when the queue is already shuffled. */
@Composable
private fun ShuffleStateText(snapshot: WidgetSnapshot, layout: RouletteLayout) {
    Text(
        text = LocalContext.current.getString(
            if (snapshot.shuffleEnabled) R.string.widget_roulette_on else R.string.widget_roulette_off,
        ),
        style = TextStyle(
            color = if (snapshot.shuffleEnabled) {
                GlanceTheme.colors.primary
            } else {
                GlanceTheme.colors.onSurfaceVariant
            },
            fontWeight = FontWeight.Bold,
            fontSize = layout.headerSp.sp,
        ),
        maxLines = 1,
    )
}

/** "Shuffle Roulette" + the ON/OFF readout (header), or just the readout on a short cell. */
@Composable
private fun StatusRow(snapshot: WidgetSnapshot, layout: RouletteLayout) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (layout.showHeader) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        if (layout.showHeader) {
            Text(
                text = LocalContext.current.getString(R.string.widget_roulette_header),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.headerSp.sp,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.defaultWeight())
        }
        ShuffleStateText(snapshot, layout)
    }
}

/** The prominent SPIN pill: a filled primary button with a dice glyph and label. */
@Composable
private fun SpinButton(layout: RouletteLayout, grow: Boolean) {
    val label = LocalContext.current.getString(R.string.widget_roulette_spin)
    val base = GlanceModifier.height(layout.spinDp.dp)
    val sized = if (grow) base.fillMaxWidth() else base
    // A pill at small sizes, but a softened rounded rectangle once SPIN is tall, so a 4x4
    // does not turn it into an oval blob.
    val radius = minOf(layout.spinDp / 2, 28)
    Box(
        modifier = sized
            .background(GlanceTheme.colors.primary)
            .cornerRadius(radius.dp)
            .clickable(rouletteSpinAction())
            // A small SPIN pill cannot afford the roomy padding, or the label ellipsises.
            .padding(horizontal = (if (layout.spinDp < 48) 10 else 18).dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_dice),
                contentDescription = null,
                modifier = GlanceModifier.size((layout.spinDp * 42 / 100).dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = (layout.spinDp * 34 / 100).coerceIn(12, 18).sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** The small shuffle chip beside SPIN: tinted `primary` when shuffle is on. */
@Composable
private fun ShuffleToggle(layout: RouletteLayout, on: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(layout.shuffleDp.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius((layout.shuffleDp / 2).dp)
            .clickable(rouletteToggleAction()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_shuffle),
            contentDescription = LocalContext.current.getString(R.string.widget_roulette_toggle),
            modifier = GlanceModifier.size((layout.shuffleDp * 55 / 100).dp),
            colorFilter = ColorFilter.tint(
                if (on) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
            ),
        )
    }
}
