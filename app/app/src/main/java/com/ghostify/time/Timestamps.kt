package com.ghostify.time

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

    const val INVALID: Long = -1L

    fun fromEpochMs(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)

    fun isValid(epochMs: Long): Boolean = epochMs > 0L

    /**
     * Human display for a given zone. The instant itself never changes with the zone;
     * only the rendering does. Returns "" for invalid/missing timestamps.
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
