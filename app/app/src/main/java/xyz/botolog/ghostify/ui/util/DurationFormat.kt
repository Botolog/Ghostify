package xyz.botolog.ghostify.ui.util

/**
 * Utility object that formats track durations from millisecond values into human-readable
 * strings using either `m:ss` or `h:mm:ss` notation.
 *
 * This is a pure Kotlin utility (JVM testable) with no Android or Compose dependencies.
 */
object DurationFormat {

    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_HOUR = 3_600
    private const val SECONDS_PER_MINUTE = 60

    /**
     * Formats a track duration in milliseconds as `m:ss` or `h:mm:ss`.
     *
     * @param durationMs duration in milliseconds; values <= 0 are treated as zero.
     * @return formatted duration string (e.g. "3:42" or "1:02:15").
     */
    fun format(durationMs: Long): String {
        if (durationMs <= 0) return "0:00"
        val totalSeconds = durationMs / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
