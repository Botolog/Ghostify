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
        private const val KIND_SEPARATOR = ": "
        private const val KIND_SEPARATOR_LENGTH = 2
        private const val FALLBACK_MESSAGE = "Download failed."

        /**
         * Extracts the first recognised [ErrorKind] token from a Chaquopy
         * exception message. Chaquopy prefixes the Python exception type name, so
         * the token can appear anywhere in the string — scan for word boundaries.
         */
        private val WIRE_PATTERN = Regex(
            "\\b(NO_TRACK|METADATA_FAILED|SEARCH_FAILED|AUDIO_UNAVAILABLE|" +
                "CONVERSION_FAILED|TAGGING_FAILED|VALIDATION_FAILED|IO|" +
                "INTERRUPTED|DEPENDENCY|UNKNOWN)\\b",
        )

        fun fromWire(token: String?): DownloadErrorKind {
            if (token.isNullOrBlank()) return UNKNOWN
            for (value in entries) if (value.name == token) return value
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
            if (message.isNullOrEmpty()) return FALLBACK_MESSAGE
            val match = WIRE_PATTERN.find(message)
            if (match != null) {
                val start = match.range.last + KIND_SEPARATOR_LENGTH
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
        private const val FALLBACK_MESSAGE = "The track could not be downloaded."

        /** Fallback for failures that could not be classified. (T-021 style.) */
        fun unknown(rawMessage: String?): DownloadError = DownloadError(
            kind = DownloadErrorKind.UNKNOWN,
            message = rawMessage?.takeIf { !it.isNullOrBlank() } ?: FALLBACK_MESSAGE,
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
    val coverUrl: String?,
) {
    companion object {
        private const val KEY_URL = "url"
        private const val KEY_SPOTIFY_ID = "spotify_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTISTS = "artists"
        private const val KEY_ALBUM = "album"
        private const val KEY_DURATION_MS = "duration_ms"
        private const val KEY_COVER_URL = "cover_url"

        /**
         * Parses the map Python hands to `on_download_start`
         * (`ghostify_dl._track_dict`). Fully defensive: missing/wrong-typed
         * fields fall back to null rather than throwing.
         */
        fun fromMap(map: Map<*, *>): TrackInfo = TrackInfo(
            url = map[KEY_URL] as? String ?: "",
            spotifyId = map[KEY_SPOTIFY_ID] as? String,
            title = map[KEY_TITLE] as? String,
            artists = map[KEY_ARTISTS] as? String,
            album = map[KEY_ALBUM] as? String,
            durationMs = (map[KEY_DURATION_MS] as? Number)?.toLong(),
            coverUrl = map[KEY_COVER_URL] as? String,
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
        val durationMs: Long?,
        val lyrics: String? = null,
    ) : TrackDownloadResult()

    /** The track was already present (sidecar match) and skipped — no network. */
    data class Skipped(
        override val url: String,
        val outputPath: String,
        val title: String?,
        val artists: String?,
        val album: String?,
        val bitrate: String?,
        val lyrics: String? = null,
    ) : TrackDownloadResult()

    /** The download failed; see [error] for a typed, user-safe explanation. */
    data class Failure(
        override val url: String,
        val error: DownloadError
    ) : TrackDownloadResult()

    companion object {
        private const val STATUS_DOWNLOADED = "DOWNLOADED"
        private const val STATUS_SKIPPED = "SKIPPED"
        private const val STATUS_FAILED = "FAILED"
        private const val UNEXPECTED_STATUS_PREFIX = "Unexpected download status: "

        /**
         * Parses the result dict returned by `ghostify_dl.download` (and the
         * `on_download_complete` payload — they share this shape). Unknown
         * statuses degrade to [Failure]/UNKNOWN rather than throwing.
         */
        fun fromMap(map: Map<*, *>): TrackDownloadResult {
            val status = map["status"] as? String
            val url = map["url"] as? String ?: ""
            val metadata = parseMetadata(map)
            return when (status) {
                STATUS_DOWNLOADED -> parseDownloaded(url, map, metadata)
                STATUS_SKIPPED -> parseSkipped(url, map, metadata)
                STATUS_FAILED -> parseFailure(url, map)
                else -> unexpectedStatus(url, status)
            }
        }

        private data class TrackMetadata(
            val title: String?,
            val artists: String?,
            val album: String?,
            val bitrate: String?,
            val durationMs: Long?,
            val fileSize: Long?,
        )

        private fun parseMetadata(map: Map<*, *>): TrackMetadata = TrackMetadata(
            title = map["title"] as? String,
            artists = map["artists"] as? String,
            album = map["album"] as? String,
            bitrate = map["bitrate"] as? String,
            durationMs = (map["duration_ms"] as? Number)?.toLong(),
            fileSize = (map["file_size"] as? Number)?.toLong(),
        )

        private fun parseDownloaded(
            url: String,
            map: Map<*, *>,
            metadata: TrackMetadata,
        ): Downloaded = Downloaded(
            url = url,
            outputPath = (map["output_path"] as? String) ?: "",
            fileSize = metadata.fileSize,
            title = metadata.title,
            artists = metadata.artists,
            album = metadata.album,
            bitrate = metadata.bitrate,
            durationMs = metadata.durationMs,
            lyrics = map["lyrics"] as? String,
        )

        private fun parseSkipped(
            url: String,
            map: Map<*, *>,
            metadata: TrackMetadata,
        ): Skipped = Skipped(
            url = url,
            outputPath = (map["output_path"] as? String) ?: "",
            title = metadata.title,
            artists = metadata.artists,
            album = metadata.album,
            bitrate = metadata.bitrate,
            lyrics = map["lyrics"] as? String,
        )

        private fun parseFailure(url: String, map: Map<*, *>): Failure = Failure(
            url = url,
            error = DownloadError(
                kind = DownloadErrorKind.fromWire(map["error_type"] as? String),
                message = DownloadErrorKind.humanMessage(map["error"] as? String),
                wireType = map["error_type"] as? String,
            ),
        )

        private fun unexpectedStatus(url: String, status: String?): Failure = Failure(
            url = url,
            error = DownloadError.unknown(
                UNEXPECTED_STATUS_PREFIX + (status?.toString() ?: "null"),
            ),
        )
    }
}
