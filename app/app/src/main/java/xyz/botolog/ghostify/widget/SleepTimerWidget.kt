package xyz.botolog.ghostify.widget

import android.content.Context
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
 * The "Sleep timer" home-screen widget.
 *
 * Like [PlayerWidget] this is a pure *reader* on the cold path: [provideGlance] asks
 * [WidgetStateProvider] for a [WidgetSnapshot] and paints it, never touching the
 * `PlayerController`, the `ExoPlayer` or the `PlaybackService`. Live data is pushed by
 * [WidgetUpdateCoordinator].
 *
 * **The countdown is app-process-only.** The sleep timer is intentionally never
 * persisted (`SleepTimerState`), so a cold widget process always renders the *idle*
 * state; the ring only visibly ticks while the app process that owns the timer is alive
 * ([WidgetStateProvider.readPersisted] documents this). Starting/cancelling from the
 * widget itself does reach the container — see [SleepTimerWidgetActionCallback].
 *
 * **Responsive + resizable**: [SizeMode.Exact] recomposes with the host's real size, which
 * [sleepLayout] turns into either a **true ring** (rasterised arc bitmap, see
 * [ringBitmap]) beside a caption + Cancel, or — on the smallest 2x2 square, where the
 * caption would crowd the ring out — the same ring with its caption dropped and a compact
 * Cancel beneath it. Only a cell below the 2x2 minimum falls back to a large `mm:ss` over a
 * thick rounded progress bar.
 */
class SleepTimerWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetStateProvider.latest(context)
        provideContent {
            val live by WidgetStateProvider.snapshot.collectAsState()
            val snapshot = live ?: initial
            GlanceTheme(colors = WidgetTheme.colors) {
                SleepTimerWidgetContent(snapshot)
            }
        }
    }
}

/** Registers [SleepTimerWidget] with the system; wired in the manifest. */
class SleepTimerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepTimerWidget()
}

@Composable
private fun SleepTimerWidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    val layout = sleepLayout(
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
        if (snapshot.sleepTimerActive) {
            SleepActive(snapshot, layout)
        } else {
            SleepIdle(layout)
        }
    }
}

/** Idle: a caption and either three presets, a stacked pill list, or a single "Start" pill. */
@Composable
private fun SleepIdle(layout: SleepLayout) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (layout.showLabel) {
            Caption(layout.labelSp, layout.showLabelText)
            Spacer(GlanceModifier.height(8.dp))
        }
        when {
            layout.stackPresets -> Column(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PresetPill(15, layout)
                Spacer(GlanceModifier.height(6.dp))
                PresetPill(30, layout)
                Spacer(GlanceModifier.height(6.dp))
                PresetPill(60, layout)
            }

            layout.showPresets -> Row(verticalAlignment = Alignment.CenterVertically) {
                PresetButton(15, layout)
                Spacer(GlanceModifier.width(SLEEP_GAP_PRESETS_DP.dp))
                PresetButton(30, layout)
                Spacer(GlanceModifier.width(SLEEP_GAP_PRESETS_DP.dp))
                PresetButton(60, layout)
            }

            else -> StartSingle(layout.singlePresetMinutes, layout)
        }
    }
}

/** Active: the countdown. A true ring on wide/tall cells, a number + bar on a small square. */
@Composable
private fun SleepActive(snapshot: WidgetSnapshot, layout: SleepLayout) {
    val remaining = formatTime(snapshot.sleepTimerRemainingMs)
    when {
        layout.wide -> Row(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SleepRing(snapshot, layout)
            Spacer(GlanceModifier.width(12.dp))
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.Start,
            ) {
                if (layout.activeCaption) {
                    Caption(layout.labelSp, layout.showLabelText)
                    Spacer(GlanceModifier.height(4.dp))
                }
                Text(
                    text = remaining,
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = layout.timeSp.sp,
                    ),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(8.dp))
                CancelButton(layout, fill = true)
            }
        }

        layout.activeRing -> Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (layout.activeCaption) {
                Caption(layout.labelSp, layout.showLabelText)
                Spacer(GlanceModifier.height(8.dp))
            }
            SleepRing(snapshot, layout)
            Spacer(GlanceModifier.height(8.dp))
            CancelButton(layout)
        }

        else -> Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = remaining,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.timeSp.sp,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = snapshot.sleepFraction(),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = GlanceTheme.colors.primary,
                backgroundColor = GlanceTheme.colors.surfaceVariant,
            )
            Spacer(GlanceModifier.height(6.dp))
            CancelButton(layout)
        }
    }
}

/**
 * The true determinate ring: a rasterised arc bitmap (see [ringBitmap]) with the moon and
 * the remaining time centred over it. The bitmap is regenerated only when the visible
 * second, size or palette changes, and is built off the composition thread by Glance.
 */
@Composable
private fun SleepRing(snapshot: WidgetSnapshot, layout: SleepLayout) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val sizePx = (layout.ringDp * density).roundToInt().coerceAtLeast(24)
    val strokePx = (layout.ringStrokeDp * density).roundToInt().coerceAtLeast(2)
    val colors = ringColors(context)
    val fraction = snapshot.sleepFraction()
    // Key on the visible whole second so we rebuild at most once a second, not per tick.
    val secondsLeft = snapshot.sleepTimerRemainingMs / 1000L
    val ring = remember(secondsLeft, layout.ringDp, colors) {
        ringBitmap(fraction, sizePx, strokePx, colors.track, colors.progress)
    }
    Box(
        modifier = GlanceModifier.size(layout.ringDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(ring),
            contentDescription = null,
            modifier = GlanceModifier.size(layout.ringDp.dp),
            contentScale = ContentScale.Fit,
        )
        Column(
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (layout.ringGlyphDp > 0) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_sleep),
                    contentDescription = null,
                    modifier = GlanceModifier.size(layout.ringGlyphDp.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
                Spacer(GlanceModifier.height(2.dp))
            }
            Text(
                text = formatTime(snapshot.sleepTimerRemainingMs),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = layout.ringTimeSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** The "Sleep timer" caption with its moon glyph; the words drop on a very narrow cell. */
@Composable
private fun Caption(sp: Int, showText: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_sleep),
            contentDescription = null,
            modifier = GlanceModifier.size((if (showText) sp + 3 else 20).dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
        if (showText) {
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = LocalContext.current.getString(R.string.widget_sleep_title),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = sp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

/** One circular "N" minutes preset button. */
@Composable
private fun PresetButton(minutes: Int, layout: SleepLayout) {
    val label = LocalContext.current.getString(R.string.widget_sleep_preset, minutes)
    Box(
        modifier = GlanceModifier
            .size(layout.buttonDp.dp)
            .background(GlanceTheme.colors.primary)
            .cornerRadius((layout.buttonDp / 2).dp)
            .clickable(sleepStartAction(minutes * 60_000L))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = minutes.toString(),
            style = TextStyle(
                color = GlanceTheme.colors.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = layout.buttonSp.sp,
            ),
            maxLines = 1,
        )
    }
}

/**
 * One full-width "N min" preset pill: the tall-cell alternative to the circle row, so a
 * 4x4 cell is not a circle row floating in empty space.
 */
@Composable
private fun PresetPill(minutes: Int, layout: SleepLayout) {
    val label = LocalContext.current.getString(R.string.widget_sleep_preset_label, minutes)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(layout.buttonDp.dp)
            .background(GlanceTheme.colors.primary)
            .cornerRadius((layout.buttonDp / 2).dp)
            .clickable(sleepStartAction(minutes * 60_000L))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = layout.buttonSp.sp,
            ),
            maxLines = 1,
        )
    }
}

/** The narrow-cell alternative to three presets: one full-width "Start N min" pill. */
@Composable
private fun StartSingle(minutes: Int, layout: SleepLayout) {
    val label = LocalContext.current.getString(R.string.widget_sleep_start_single, minutes)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(layout.buttonDp.dp)
            .background(GlanceTheme.colors.primary)
            .cornerRadius((layout.buttonDp / 2).dp)
            .clickable(sleepStartAction(minutes * 60_000L))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = layout.buttonSp.sp,
            ),
            maxLines = 1,
        )
    }
}

/** Cancel pill: a tonal button that stops the countdown without touching playback. */
@Composable
private fun CancelButton(layout: SleepLayout, fill: Boolean = false) {
    val label = LocalContext.current.getString(R.string.widget_sleep_cancel)
    var modifier = GlanceModifier.height(layout.cancelDp.dp)
    if (fill) modifier = modifier.fillMaxWidth()
    modifier = modifier
        .background(GlanceTheme.colors.surfaceVariant)
        .cornerRadius((layout.cancelDp / 2).dp)
        .clickable(sleepCancelAction())
        .padding(horizontal = layout.cancelPadDp.dp)
        .semantics { contentDescription = label }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_close),
                contentDescription = null,
                modifier = GlanceModifier.size(layout.iconDp.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    fontSize = layout.cancelSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}
