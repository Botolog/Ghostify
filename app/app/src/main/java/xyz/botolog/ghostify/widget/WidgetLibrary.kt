package xyz.botolog.ghostify.widget

import java.text.NumberFormat
import java.util.Locale

/**
 * The "Library" widget's sync readout, as one of four states.
 *
 * Kept as an enum (rather than a pre-formatted string) so the composable owns the
 * resource lookup while the *decision* stays pure and JVM-tested.
 */
enum class SyncLabelKind {
    /** A sync is running right now. */
    SYNCING,

    /** The library has never been synced. */
    NEVER,

    /** The last sync finished within [JUST_NOW_WINDOW_MS]. */
    JUST_NOW,

    /** The last sync finished longer ago than [JUST_NOW_WINDOW_MS]. */
    AGO,
}

/** Window within which a finished sync reads as "just now". */
const val JUST_NOW_WINDOW_MS: Long = 60_000L

/**
 * Decides which sync readout to paint.
 *
 * Pure: [nowMs] is injected so the window boundary is testable rather than clock-dependent.
 */
fun syncLabelKind(isSyncing: Boolean, lastSyncedAtMs: Long?, nowMs: Long): SyncLabelKind = when {
    isSyncing -> SyncLabelKind.SYNCING
    lastSyncedAtMs == null -> SyncLabelKind.NEVER
    nowMs - lastSyncedAtMs < JUST_NOW_WINDOW_MS -> SyncLabelKind.JUST_NOW
    else -> SyncLabelKind.AGO
}

/** Unit of the coarse "synced N <unit> ago" readout. */
enum class SyncAgeUnit {
    MINUTES,
    HOURS,
    DAYS,
}

/**
 * A coarse age, split into a value + [SyncAgeUnit] so the composable owns the (localized)
 * unit string. Pure and JVM-tested.
 *
 * @property value count of the unit; always at least 1.
 * @property unit the largest unit that keeps [value] below the next unit's threshold.
 */
data class SyncAge(val value: Int, val unit: SyncAgeUnit)

/**
 * Buckets [ageMs] into minutes / hours / days for the sync line.
 *
 * Values are floored and clamped to at least 1 so an age just past the "just now" window
 * reads "1 min" rather than "0 min". Negative ages (a clock that moved backwards) are
 * treated as zero.
 */
fun syncAgeParts(ageMs: Long): SyncAge {
    val minutes = (ageMs.coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 60L -> SyncAge(minutes.toInt().coerceAtLeast(1), SyncAgeUnit.MINUTES)
        minutes < 60L * 24L -> SyncAge((minutes / 60L).toInt(), SyncAgeUnit.HOURS)
        else -> SyncAge((minutes / (60L * 24L)).toInt(), SyncAgeUnit.DAYS)
    }
}

/**
 * Formats a library count with locale-aware digit grouping (`1234` → `1,234`).
 *
 * @param count the raw count.
 * @param locale grouping locale; defaults to [Locale.US] so the formatting is deterministic
 *   in tests (the composable passes the host's locale).
 */
fun formatStatCount(count: Int, locale: Locale = Locale.US): String =
    NumberFormat.getIntegerInstance(locale).format(count.toLong())
