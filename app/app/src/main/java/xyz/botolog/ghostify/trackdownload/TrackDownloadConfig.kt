package xyz.botolog.ghostify.trackdownload

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
    val ffmpeg: String = "ffmpeg",
    /** Lyrics providers to search for song lyrics. Empty list = no lyrics. */
    val lyricsProviders: List<String> = emptyList(),
) {
    companion object {
        /** Default bitrate in kbps. */
        const val DEFAULT_BITRATE = 192

        /** Default spotdl output template. */
        const val DEFAULT_TEMPLATE = "{artists} - {title}"

        /** Allowed bitrate values in kbps. */
        val ALLOWED_BITRATES = intArrayOf(128, 192, 320)

        /** Returns true when [b] is a valid bitrate. */
        fun isValidBitrate(b: Int): Boolean = b in ALLOWED_BITRATES

        /** Default configuration with an empty output directory. */
        val DEFAULT: TrackDownloadConfig
            get() = TrackDownloadConfig(outputDir = "", bitrate = DEFAULT_BITRATE)
    }
}
