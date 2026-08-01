package com.ghostify.ui.util

import kotlin.math.roundToInt

/**
 * Maps a playlist's download state to the small "progress badge" shown on Library rows.
 * Pure Kotlin (JVM testable) so the mapping rules are covered by unit tests.
 *
 * @param inFlight       true when a download-all job is running for this playlist.
 * @param progressPercent explicit overall progress emitted by the download flow (0..100), if known.
 * @param downloaded     number of tracks already downloaded.
 * @param total          number of tracks in the playlist.
 */
data class DownloadBadge(
    /** 0..100 — drives a determinate progress indicator and is rendered as text. */
    val percent: Int,
    /** Compact label, e.g. "42%", "4/10 downloaded", "Downloaded". */
    val label: String,
)

object ProgressBadge {

    fun progressToBadge(
        inFlight: Boolean,
        progressPercent: Int?,
        downloaded: Int,
        total: Int,
    ): DownloadBadge? {
        if (total <= 0) return null
        val safeDownloaded = downloaded.coerceIn(0, total)

        if (inFlight) {
            val percent = (progressPercent
                ?: (safeDownloaded * 100f / total).roundToInt())
                .coerceIn(0, 100)
            return DownloadBadge(percent = percent, label = "$percent%")
        }
        if (safeDownloaded >= total) return DownloadBadge(percent = 100, label = "Downloaded")
        if (safeDownloaded > 0) {
            val percent = (safeDownloaded * 100f / total).roundToInt()
            return DownloadBadge(percent = percent, label = "$safeDownloaded/$total downloaded")
        }
        return null
    }
}
