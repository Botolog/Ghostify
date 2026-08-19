package xyz.botolog.ghostify.ui.util

/**
 * Formats a track duration in milliseconds as `m:ss` or `h:mm:ss`.
 * Pure Kotlin (JVM testable).
 */
object DurationFormat {

    fun format(durationMs: Long): String {
        if (durationMs <= 0) return "0:00"
        val totalSeconds = durationMs / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
