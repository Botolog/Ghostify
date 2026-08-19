package xyz.botolog.ghostify.trackdownload

/**
 * Typed, user-facing outcomes and errors for [TrackDownloadBridge].
 *
 * The machine-readable [DownloadErrorKind] mirrors `ghostify_dl.ErrorKind` 1:1 so
 * the Kotlin layer never has to parse exception text — the Python module emits
 * the kind token in its `TrackDownloadError` string (``"<KIND>: <message>"``),
 * and the bridge classifies it via [DownloadErrorKind.fromMessage] (the same
 * regex scan used by the sibling `PlaylistMetadataBridge` for `GhostifyError`
 * codes).
 */

/** Stable failure category, mirroring `ghostify_dl.ErrorKind`. */
enum class DownloadErrorKind {
    NO_TRACK,
    METADATA_FAILED,
    SEARCH_FAILED,
    AUDIO_UNAVAILABLE,
    CONVERSION_FAILED,
    TAGGING_FAILED,
    VALIDATION_FAILED,
    IO,
    INTERRUPTED,
    DEPENDENCY,
    UNKNOWN;

    companion object {
        /**
         * Extracts the first recognised [ErrorKind] token from a Chaquopy
         * exception message. Chaquopy prefixes the Python exception type name, so
         * the token can appear anywhere in the string — scan for word boundaries.
         */
        private val WIRE_PATTERN = Regex(
            "\\b(NO_TRACK|METADATA_FAILED|SEARCH_FAILED|AUDIO_UNAVAILABLE|" +
                "CONVERSION_FAILED|TAGGING_FAILED|VALIDATION_FAILED|IO|" +
                "INTERRUPTED|DEPENDENCY|UNKNOWN)\\b"
        )

        fun fromWire(token: String?): DownloadErrorKind {
            if (token.isNullOrBlank()) return UNKNOWN
            for (value in values()) if (value.name == token) return value
            return UNKNOWN
        }

        /** Classifies a Chaquopy/PyException message into a [DownloadErrorKind]. */
        fun fromMessage(message: String?): DownloadErrorKind {
            if (message.isNullOrEmpty()) return UNKNOWN
            val match = WIRE_PATTERN.find(message)
            return if (match != null) fromWire(match.value) else UNKNOWN
        }

        /**
         * Returns the human-readable portion after `"<KIND>: "` when a token was
         * found, or the full message otherwise. Never returns blank.
         */
        fun humanMessage(message: String?): String {
            if (message.isNullOrEmpty()) return "Download failed."
            val match = WIRE_PATTERN.find(message)
            if (match != null) {
                val start = match.range.last + 2 // skip "KIND: "
                if (start <= message.length) {
                    val after = message.substring(start).trim()
                    if (after.isNotEmpty()) return after
                }
            }
            return message
        }
    }
}

/**
 * A typed, user-safe failure from the Python download layer.
 *
 * @param kind stable machine-readable category.
 * @param message human-readable explanation (safe to render in UI).
 * @param wireType the original Python `ErrorKind` token, kept for diagnostics.
 */
data class DownloadError(
    val kind: DownloadErrorKind,
    val message: String,
    val wireType: String? = null
) {
    companion object {
        /** Fallback for failures that could not be classified. (T-021 style.) */
        fun unknown(rawMessage: String?): DownloadError =
            DownloadError(
                kind = DownloadErrorKind.UNKNOWN,
                message = rawMessage?.takeIf { !it.isNullOrBlank() }
                    ?: "The track could not be downloaded."
            )
    }
}

/** Metadata describing a track, surfaced to the progress hooks as plain maps. */
data class TrackInfo(
    val url: String,
    val spotifyId: String?,
    val title: String?,
    val artists: String?,
    val album: String?,
    val durationMs: Long?,
    val coverUrl: String?
) {
    companion object {
        /**
         * Parses the map Python hands to `on_download_start`
         * (`ghostify_dl._track_dict`). Fully defensive: missing/wrong-typed
         * fields fall back to null rather than throwing.
         */
        fun fromMap(map: Map<*, *>): TrackInfo = TrackInfo(
            url = map["url"] as? String ?: "",
            spotifyId = map["spotify_id"] as? String,
            title = map["title"] as? String,
            artists = map["artists"] as? String,
            album = map["album"] as? String,
            durationMs = (map["duration_ms"] as? Number)?.toLong(),
            coverUrl = map["cover_url"] as? String
        )
    }
}

/**
 * Outcome of a single-track download. The bridge returns this for every path and
 * never throws for expected track failures — see [Failure].
 */
sealed class TrackDownloadResult {
    abstract val url: String

    /** The track was (freshly) downloaded to [outputPath]. */
    data class Downloaded(
        override val url: String,
        val outputPath: String,
        val fileSize: Long?,
        val title: String?,
        val artists: String?,
        val album: String?,
        val bitrate: String?,
        val durationMs: Long?
    ) : TrackDownloadResult()

    /** The track was already present (sidecar match) and skipped — no network. */
    data class Skipped(
        override val url: String,
        val outputPath: String,
        val title: String?,
        val artists: String?,
        val album: String?,
        val bitrate: String?
    ) : TrackDownloadResult()

    /** The download failed; see [error] for a typed, user-safe explanation. */
    data class Failure(
        override val url: String,
        val error: DownloadError
    ) : TrackDownloadResult()

    companion object {
        /**
         * Parses the result dict returned by `ghostify_dl.download` (and the
         * `on_download_complete` payload — they share this shape). Unknown
         * statuses degrade to [Failure]/UNKNOWN rather than throwing.
         */
        fun fromMap(map: Map<*, *>): TrackDownloadResult {
            val status = map["status"] as? String
            val url = map["url"] as? String ?: ""
            val title = map["title"] as? String
            val artists = map["artists"] as? String
            val album = map["album"] as? String
            val bitrate = map["bitrate"] as? String
            val durationMs = (map["duration_ms"] as? Number)?.toLong()
            val fileSize = (map["file_size"] as? Number)?.toLong()
            return when (status) {
                "DOWNLOADED" -> Downloaded(
                    url = url,
                    outputPath = (map["output_path"] as? String) ?: "",
                    fileSize = fileSize,
                    title = title,
                    artists = artists,
                    album = album,
                    bitrate = bitrate,
                    durationMs = durationMs
                )
                "SKIPPED" -> Skipped(
                    url = url,
                    outputPath = (map["output_path"] as? String) ?: "",
                    title = title,
                    artists = artists,
                    album = album,
                    bitrate = bitrate
                )
                "FAILED" -> Failure(
                    url = url,
                    error = DownloadError(
                        kind = DownloadErrorKind.fromWire(map["error_type"] as? String),
                        message = DownloadErrorKind.humanMessage(map["error"] as? String),
                        wireType = map["error_type"] as? String
                    )
                )
                else -> Failure(
                    url = url,
                    error = DownloadError.unknown(
                        "Unexpected download status: " + (status?.toString() ?: "null")
                    )
                )
            }
        }
    }
}
