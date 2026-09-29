package xyz.botolog.ghostify.ui.player

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.util.DurationFormat

/** Stable test tags for the sleep timer control and its editor. */
object SleepTimerTestTags {
    const val BUTTON = "sleep_timer_button"
    const val DIALOG = "sleep_timer_dialog"
    const val CLOCK = "sleep_timer_clock"
    const val HOURS_INCREMENT = "sleep_timer_hours_increment"
    const val HOURS_DECREMENT = "sleep_timer_hours_decrement"
    const val MINUTES_INCREMENT = "sleep_timer_minutes_increment"
    const val MINUTES_DECREMENT = "sleep_timer_minutes_decrement"
    const val SECONDS_INCREMENT = "sleep_timer_seconds_increment"
    const val SECONDS_DECREMENT = "sleep_timer_seconds_decrement"
    const val START = "sleep_timer_start"
    const val CANCEL = "sleep_timer_cancel"
}

internal const val SLEEP_TIMER_IDLE_DESCRIPTION = "Sleep timer"

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private val SLEEP_TIMER_RING_STROKE = 1.5.dp
private val SLEEP_TIMER_RING_INSET = 10.dp
private val SLEEP_TIMER_RING_VERTICAL_INSET = 3.dp
private const val SLEEP_TIMER_RESIZE_ANIMATION_MILLIS = 200

/**
 * The size of the button while it is idle: the 1:1 square that holds the hourglass.
 *
 * The running button grows out of this square to fit its countdown and animates back into it,
 * so both dimensions have to start out equal.
 */
internal val SLEEP_TIMER_IDLE_SIZE = DpSize(48.dp, 48.dp)

/**
 * The height of the button while a timer is running: shorter than the idle square, so the
 * countdown sits in a flat pill that does not loom over the transport controls next to it.
 * Its width still grows with the countdown, so this only flattens the button, never squeezes it.
 */
internal val SLEEP_TIMER_ACTIVE_HEIGHT = 36.dp

/**
 * The height the button asks for in a given state.
 *
 * @param active whether a timer is currently running.
 * @return the shorter countdown height while running, the square's height while idle.
 */
internal fun sleepTimerButtonMinHeight(active: Boolean): Dp =
    if (active) SLEEP_TIMER_ACTIVE_HEIGHT else SLEEP_TIMER_IDLE_SIZE.height

/** The hourglass drawn inside the idle square. */
internal val SLEEP_TIMER_IDLE_ICON_SIZE = 24.dp

/**
 * Rounds a remaining time up to the next whole second.
 *
 * Rounded up, never down: a timer with half a second left still shows a second, so the
 * countdown never reads `0:00` while it is still running.
 *
 * @param remainingMs remaining time in milliseconds.
 * @return the remaining time in whole seconds, never negative.
 */
internal fun sleepTimerSecondsRemaining(remainingMs: Long): Long =
    if (remainingMs <= 0L) 0L else (remainingMs + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND

/**
 * Formats the time left on a running sleep timer for the top bar.
 *
 * @param remainingMs remaining time in milliseconds.
 * @return `m:ss`, or `h:mm:ss` once the timer runs past an hour.
 */
internal fun sleepTimerRemainingText(remainingMs: Long): String =
    DurationFormat.format(sleepTimerSecondsRemaining(remainingMs) * MILLIS_PER_SECOND)

/**
 * Spells the time left on a running sleep timer out for a screen reader.
 *
 * @param remainingMs remaining time in milliseconds.
 * @return the remaining time in words, e.g. "1 hour 5 minutes 30 seconds".
 */
internal fun sleepTimerSpokenRemaining(remainingMs: Long): String {
    val total = sleepTimerSecondsRemaining(remainingMs)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    val spoken = listOf(
        hours to "hour",
        minutes to "minute",
        seconds to "second",
    ).filter { (value, _) -> value > 0L }
        .joinToString(" ") { (value, unit) -> "$value $unit${if (value == 1L) "" else "s"}" }
    return if (spoken.isEmpty()) "0 seconds" else spoken
}

/**
 * Accessible name of the sleep-timer button, in both of its states.
 *
 * The idle button only says what it opens; the running one also says how long is left and
 * that activating it cancels, so the countdown is never a purely visual announcement.
 *
 * @param active whether a timer is currently running.
 * @param remainingMs remaining time in milliseconds.
 */
internal fun sleepTimerButtonDescription(active: Boolean, remainingMs: Long): String =
    if (!active) SLEEP_TIMER_IDLE_DESCRIPTION
    else "$SLEEP_TIMER_IDLE_DESCRIPTION, ${sleepTimerSpokenRemaining(remainingMs)} remaining, " +
        "activate to cancel"

/**
 * How much of the countdown ring is still drawn.
 *
 * @param active whether a timer is currently running.
 * @param remainingMs remaining time in milliseconds.
 * @param totalMs duration the timer was started with.
 * @return `1f` when the timer has just started, `0f` once it is over.
 */
internal fun sleepTimerRingFraction(active: Boolean, remainingMs: Long, totalMs: Long): Float =
    if (!active || totalMs <= 0L) 0f
    else (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)

/**
 * Sleep timer for the full player: the hourglass opens the editor, the running countdown
 * cancels the timer.
 *
 * The button is a square icon button while idle and grows — animated — into a hollow, flat
 * pill around the remaining `HH:MM:SS` while the timer runs, then animates back to the square
 * when it is cancelled or expires. The countdown is drawn rather than animated, and every value
 * it shows comes from the player contract, so the button is a view of state the player already
 * owns.
 *
 * @param state player state carrying the countdown.
 * @param contract player commands, used to start and cancel the timer.
 * @param modifier modifier applied to the button.
 * @param idleTint colour of the idle hourglass icon.
 */
@Composable
fun SleepTimerControl(
    state: PlayerUiState,
    contract: PlayerContract,
    modifier: Modifier = Modifier,
    idleTint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
) {
    var editorOpen by remember { mutableStateOf(false) }
    val remainingMs = state.sleepTimerRemainingMs
    val active = state.sleepTimerActive
    val interactionSource = remember { MutableInteractionSource() }
    val ringColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .drawBehind {
                drawSleepTimerRing(
                    fraction = sleepTimerRingFraction(active, remainingMs, state.sleepTimerTotalMs),
                    color = ringColor,
                    strokeWidth = SLEEP_TIMER_RING_STROKE.toPx(),
                )
            }
            .clip(RoundedCornerShape(percent = 50))
            .testTag(SleepTimerTestTags.BUTTON)
            .semantics {
                contentDescription = sleepTimerButtonDescription(active, remainingMs)
            }
            .animateContentSize(
                animationSpec = tween(durationMillis = SLEEP_TIMER_RESIZE_ANIMATION_MILLIS),
            )
            .defaultMinSize(
                minWidth = SLEEP_TIMER_IDLE_SIZE.width,
                minHeight = sleepTimerButtonMinHeight(active),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
            ) {
                if (active) contract.cancelSleepTimer() else editorOpen = true
            },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Text(
                text = sleepTimerRemainingText(remainingMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = idleTint,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    horizontal = SLEEP_TIMER_RING_INSET,
                    vertical = SLEEP_TIMER_RING_VERTICAL_INSET,
                ),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.HourglassEmpty,
                contentDescription = null,
                modifier = Modifier.size(SLEEP_TIMER_IDLE_ICON_SIZE),
                tint = idleTint,
            )
        }
    }

    if (editorOpen) {
        SleepTimerDialog(
            onDismiss = { editorOpen = false },
            onStart = { durationMs ->
                editorOpen = false
                contract.startSleepTimer(durationMs)
            },
        )
    }
}

/**
 * Draws the countdown ring.
 *
 * The outline is a full rounded rectangle whose visible length is the fraction of the timer
 * that is left. It starts at top centre and is drawn clockwise, and the hidden part grows
 * from the same point counter-clockwise: a full ring at the start of the timer, only the
 * right-hand side at the halfway point, and nothing at all once it has expired.
 *
 * @param fraction share of the ring to draw, in the range `0f..1f`.
 * @param color colour of the outline.
 * @param strokeWidth thickness of the outline in pixels.
 */
private fun DrawScope.drawSleepTimerRing(fraction: Float, color: Color, strokeWidth: Float) {
    if (fraction <= 0f || size.minDimension <= 0f) return

    val inset = strokeWidth / 2f
    val ring = sleepTimerRingPath(
        left = inset,
        top = inset,
        right = size.width - inset,
        bottom = size.height - inset,
    )
    val measure = PathMeasure().apply { setPath(ring, false) }
    val length = measure.length
    if (length <= 0f) return

    val visible = Path()
    measure.getSegment(0f, length * fraction.coerceIn(0f, 1f), visible, true)
    drawPath(
        path = visible,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/**
 * The countdown ring's outline, starting at top centre and running clockwise, so distance
 * `0` on the path is the point the empty edge retreats from.
 *
 * @param left left edge of the ring in pixels.
 * @param top top edge of the ring in pixels.
 * @param right right edge of the ring in pixels.
 * @param bottom bottom edge of the ring in pixels.
 */
private fun sleepTimerRingPath(left: Float, top: Float, right: Float, bottom: Float): Path {
    val width = right - left
    val height = bottom - top
    val radius = minOf(width, height) / 2f
    val centreX = left + width / 2f

    return Path().apply {
        moveTo(centreX, top)
        lineTo(right - radius, top)
        arcTo(Rect(right - radius, top, right, top + radius), -90f, 90f, false)
        lineTo(right, bottom - radius)
        arcTo(Rect(right - radius, bottom - radius, right, bottom), 0f, 90f, false)
        lineTo(left + radius, bottom)
        arcTo(Rect(left, bottom - radius, left + radius, bottom), 90f, 90f, false)
        lineTo(left, top + radius)
        arcTo(Rect(left, top, left + radius, top + radius), 180f, 90f, false)
        lineTo(centreX, top)
    }
}
