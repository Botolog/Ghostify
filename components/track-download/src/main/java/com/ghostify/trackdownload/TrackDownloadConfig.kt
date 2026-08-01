package com.ghostify.trackdownload

/**
 * Configuration for a single-track download through [TrackDownloadBridge].
 *
 * A pure, immutable description mirroring `ghostify_dl.TrackDownloader`'s
 * constructor. All values are validated on the Python side; this type only
 * bundles them for the bridge. Immutable so a single instance can be safely
 * shared across coroutines.
 */
data class TrackDownloadConfig(
    /** Directory MP3s (and their `.spotdl` sidecars) are written into. */
    val outputDir: String,
    /** Target bitrate in kbps. One of [ALLOWED_BITRATES]; else the Python layer
     *  raises `TrackDownloadError(DEPENDENCY)` on download. */
    val bitrate: Int = DEFAULT_BITRATE,
    /** spotdl output template, e.g. `"{artists} - {title}"`. */
    val outputTemplate: String = DEFAULT_TEMPLATE,
    /** ffmpeg binary name on PATH or an absolute path (see PROJECT.md §7). */
    val ffmpeg: String = "ffmpeg"
) {
    companion object {
        const val DEFAULT_BITRATE = 192
        const val DEFAULT_TEMPLATE = "{artists} - {title}"
        val ALLOWED_BITRATES = intArrayOf(128, 192, 320)
        fun isValidBitrate(b: Int): Boolean = b in ALLOWED_BITRATES

        val DEFAULT: TrackDownloadConfig
            get() = TrackDownloadConfig(outputDir = "", bitrate = DEFAULT_BITRATE)
    }
}
