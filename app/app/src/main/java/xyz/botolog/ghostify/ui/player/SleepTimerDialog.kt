package xyz.botolog.ghostify.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val MILLIS_PER_SECOND = 1_000L
private val SLEEP_TIMER_UNIT_WIDTH = 64.dp
private val SLEEP_TIMER_DIALOG_GAP = 8.dp

/**
 * The duration the sleep-timer editor starts from: half an hour, the most common bedtime
 * request, and a value that is valid without any adjustment.
 */
internal val DEFAULT_SLEEP_TIMER_DRAFT = SleepTimerDraft(hours = 0, minutes = 30, seconds = 0)

/**
 * One editable field of the sleep-timer editor.
 *
 * @property label the field's name, shown under its digits.
 */
enum class SleepTimerUnit(val label: String) {
    HOURS("Hours"),
    MINUTES("Minutes"),
    SECONDS("Seconds"),
}

/**
 * The hours/minutes/seconds the user picked in the sleep-timer editor.
 *
 * Every field is clamped to its own bounds and moved in fixed steps, so a long press can
 * never run a value out of range or spin forever, and the digits on the clock stay a real
 * clock reading: hours run `00` to `12`, minutes and seconds run in five-unit steps.
 *
 * @property hours whole hours, `0..12`.
 * @property minutes whole minutes, `0..55`.
 * @property seconds whole seconds, `0..55`.
 */
data class SleepTimerDraft(
    val hours: Int = 0,
    val minutes: Int = 0,
    val seconds: Int = 0,
) {

    /** The picked duration in whole seconds. */
    val totalSeconds: Int get() = hours * 3_600 + minutes * 60 + seconds

    /** The picked duration in milliseconds, or `0` when nothing was picked. */
    val totalMs: Long get() = totalSeconds.toLong() * MILLIS_PER_SECOND

    /** `true` when the draft is a duration the player can be asked to count down. */
    val isValid: Boolean get() = totalSeconds >= 1

    /** The clock reading, e.g. `01:30:00`. */
    val display: String get() = "%02d:%02d:%02d".format(hours, minutes, seconds)

    /**
     * Moves one field by a number of steps, clamped to that field's bounds.
     *
     * @param unit the field to change.
     * @param steps how many steps to move it, negative to move it down.
     * @return the adjusted draft.
     */
    fun adjust(unit: SleepTimerUnit, steps: Int): SleepTimerDraft = when (unit) {
        SleepTimerUnit.HOURS -> copy(hours = clampField(hours, steps, HOUR_STEP, MAX_HOURS))
        SleepTimerUnit.MINUTES -> copy(minutes = clampField(minutes, steps, MINUTE_STEP, MAX_MINUTES))
        SleepTimerUnit.SECONDS -> copy(seconds = clampField(seconds, steps, SECOND_STEP, MAX_SECONDS))
    }

    /**
     * Clamps a field to its own bounds after moving it.
     *
     * @param value the field's current value.
     * @param steps how many steps to move it.
     * @param step the size of one step.
     * @param maximum the field's largest value.
     * @return the new value, never below `0` and never above the field's maximum.
     */
    private fun clampField(value: Int, steps: Int, step: Int, maximum: Int): Int =
        (value + steps * step).coerceIn(0, maximum)

    /**
     * The current value of one field.
     *
     * @param unit the field to read.
     */
    fun valueOf(unit: SleepTimerUnit): Int = when (unit) {
        SleepTimerUnit.HOURS -> hours
        SleepTimerUnit.MINUTES -> minutes
        SleepTimerUnit.SECONDS -> seconds
    }

    companion object {

        /** Largest whole number of hours the editor can count down. */
        const val MAX_HOURS: Int = 12

        /** Largest number of minutes the editor can count down. */
        const val MAX_MINUTES: Int = 55

        /** Largest number of seconds the editor can count down. */
        const val MAX_SECONDS: Int = 55

        /** How much one press of the hours controls moves the clock. */
        const val HOUR_STEP: Int = 1

        /** How much one press of the minutes controls moves the clock. */
        const val MINUTE_STEP: Int = 5

        /** How much one press of the seconds controls moves the clock. */
        const val SECOND_STEP: Int = 5
    }
}

/**
 * Sleep-timer editor: a digital clock whose three fields are stepped up and down, and which
 * can only be started once it names a real duration.
 *
 * Purely an editor — the countdown itself is owned by the player, so this only ever hands
 * over a duration.
 *
 * @param onDismiss invoked by Cancel, by a tap outside and by system back; starts nothing.
 * @param onStart invoked with the picked duration in milliseconds; only called when the
 *   draft names at least one second.
 */
@Composable
fun SleepTimerDialog(
    onDismiss: () -> Unit,
    onStart: (Long) -> Unit,
) {
    var draft by remember { mutableStateOf(DEFAULT_SLEEP_TIMER_DRAFT) }

    fun step(unit: SleepTimerUnit, steps: Int) {
        draft = draft.adjust(unit, steps)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SleepTimerTestTags.DIALOG),
        title = {
            Text(
                text = "Sleep timer",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SleepTimerTestTags.CLOCK),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Top,
                ) {
                    SleepTimerUnit.entries.forEach { unit ->
                        SleepTimerUnitControl(
                            unit = unit,
                            value = draft.valueOf(unit),
                            onIncrement = { step(unit, 1) },
                            onDecrement = { step(unit, -1) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(SLEEP_TIMER_DIALOG_GAP))
                Text(
                    text = if (draft.isValid) {
                        "Playback pauses when the timer runs out."
                    } else {
                        "Pick at least one second."
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (draft.isValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(draft.totalMs) },
                enabled = draft.isValid,
                modifier = Modifier.testTag(SleepTimerTestTags.START),
            ) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(SleepTimerTestTags.CANCEL),
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * One field of the digital clock: the value between an increment and a decrement control.
 *
 * @param unit which field this is.
 * @param value the field's current value, already clamped by the draft.
 * @param onIncrement invoked by the upper control.
 * @param onDecrement invoked by the lower control.
 */
@Composable
private fun SleepTimerUnitControl(
    unit: SleepTimerUnit,
    value: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onIncrement, modifier = Modifier.testTag(incrementTag(unit))) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add ${unit.label.lowercase()}",
                tint = stepTint(unit, value, increment = true),
            )
        }
        Text(
            text = "%02d".format(value),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(SLEEP_TIMER_UNIT_WIDTH),
        )
        IconButton(onClick = onDecrement, modifier = Modifier.testTag(decrementTag(unit))) {
            Icon(
                imageVector = Icons.Filled.Remove,
                contentDescription = "Remove ${unit.label.lowercase()}",
                tint = stepTint(unit, value, increment = false),
            )
        }
        Text(
            text = unit.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Test tag of a unit's increment control.
 *
 * @param unit which field the control belongs to.
 */
internal fun incrementTag(unit: SleepTimerUnit): String = when (unit) {
    SleepTimerUnit.HOURS -> SleepTimerTestTags.HOURS_INCREMENT
    SleepTimerUnit.MINUTES -> SleepTimerTestTags.MINUTES_INCREMENT
    SleepTimerUnit.SECONDS -> SleepTimerTestTags.SECONDS_INCREMENT
}

/**
 * Test tag of a unit's decrement control.
 *
 * @param unit which field the control belongs to.
 */
internal fun decrementTag(unit: SleepTimerUnit): String = when (unit) {
    SleepTimerUnit.HOURS -> SleepTimerTestTags.HOURS_DECREMENT
    SleepTimerUnit.MINUTES -> SleepTimerTestTags.MINUTES_DECREMENT
    SleepTimerUnit.SECONDS -> SleepTimerTestTags.SECONDS_DECREMENT
}

/**
 * Colour of a step control: dimmed once its field has nowhere left to go, so a control that
 * cannot change anything does not look as available as one that can.
 *
 * @param unit which field the control belongs to.
 * @param value the field's current value.
 * @param increment whether this is the increment control.
 */
@Composable
private fun stepTint(unit: SleepTimerUnit, value: Int, increment: Boolean): Color {
    val canStep = if (increment) value < unit.maximum else value > 0
    return if (canStep) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    }
}

/**
 * The largest value a unit can be moved to.
 */
private val SleepTimerUnit.maximum: Int
    get() = when (this) {
        SleepTimerUnit.HOURS -> SleepTimerDraft.MAX_HOURS
        SleepTimerUnit.MINUTES -> SleepTimerDraft.MAX_MINUTES
        SleepTimerUnit.SECONDS -> SleepTimerDraft.MAX_SECONDS
    }
