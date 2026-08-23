package xyz.botolog.ghostify.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Timestamp handling.
 *
 * The app stores and sorts by absolute epoch milliseconds (UTC) — never by local
 * wall-clock strings — so DST changes and cross-timezone travel (T-162) can neither shift
 * ordering nor break display: an epoch instant is the same at any moment, in any zone.
 * `java.time` is available on minSdk 26+, so no desugaring is required.
 */
object Timestamps {

    /** Sentinel value for an invalid or missing timestamp. */
    const val INVALID: Long = -1L

    /**
     * Converts epoch milliseconds to an [Instant].
     *
     * @param epochMs the epoch milliseconds to convert.
     * @return the corresponding [Instant].
     */
    fun fromEpochMs(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)

    /**
     * Returns true when [epochMs] represents a valid (positive) timestamp.
     *
     * @param epochMs the epoch milliseconds to validate.
     */
    fun isValid(epochMs: Long): Boolean = epochMs > 0L

    /**
     * Human display for a given zone. The instant itself never changes with the zone;
     * only the rendering does. Returns "" for invalid/missing timestamps.
     *
     * @param epochMs the epoch milliseconds to format.
     * @param zone the timezone to format in (defaults to system default).
     * @param locale the locale for formatting (defaults to system default).
     * @return the formatted datetime string, or "" for invalid timestamps.
     */
    fun format(
        epochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (!isValid(epochMs)) return ""
        return DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(epochMs))
    }

    /** Sort ascending by absolute time — stable regardless of DST or travel. */
    fun sortAscending(a: Long, b: Long): Int = a.compareTo(b)
}
