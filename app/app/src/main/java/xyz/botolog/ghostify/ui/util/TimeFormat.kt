package xyz.botolog.ghostify.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Formats an epoch-millisecond timestamp as a short, human readable "time ago" string
 * used for the playlist "last synced" label.
 *
 * Pure Kotlin (JVM testable): [nowMs] is injected so callers (and tests) stay deterministic.
 */
object TimeFormat {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS
    private const val WEEK_MS = 7 * DAY_MS

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())

    /**
     * @param epochMs timestamp to format; `null` means the playlist was never synced.
     * @param nowMs current wall-clock time, injected for determinism.
     */
    fun formatRelative(epochMs: Long?, nowMs: Long): String {
        if (epochMs == null) return "Never"
        val diff = nowMs - epochMs
        if (diff <= 0 || diff < MINUTE_MS) return "Just now"
        if (diff < HOUR_MS) return "${diff / MINUTE_MS}m ago"
        if (diff < DAY_MS) return "${diff / HOUR_MS}h ago"
        if (diff < WEEK_MS) return "${diff / DAY_MS}d ago"
        return dateFormatter.format(Instant.ofEpochMilli(epochMs))
    }
}
