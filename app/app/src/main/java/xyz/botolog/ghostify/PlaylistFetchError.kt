package xyz.botolog.ghostify.python

/**
 * Typed failure codes for the Python bridge.
 *
 * These are the ONLY failure codes the app's UI layer may depend on. They
 * mirror the codes produced by `ghostify_dl.GhostifyError` in Python; the
 * bridge maps any unexpected Python exception to [UNKNOWN] so a raw crash can
 * never escape the bridge.
 */
enum class PlaylistFetchErrorCode {
    NO_NETWORK,
    RATE_LIMITED,
    PRIVATE,
    TIMEOUT,
    NOT_FOUND,
    UNKNOWN;

    companion object {
        /**
         * Maps a wire token (the `CODE` segment of a `GhostifyError` message)
         * to an enum value, defaulting to [UNKNOWN] for anything unrecognised.
         */
        fun fromWire(value: String?): PlaylistFetchErrorCode {
            for (code in values()) {
                if (code.name == value) return code
            }
            return UNKNOWN
        }
    }
}

/**
 * A typed, user-safe failure produced by the Python bridge.
 *
 * @param code the machine-readable failure category.
 * @param message a human-readable explanation (already safe to show in UI).
 * @param retryHint optional short guidance shown alongside the message.
 */
data class PlaylistFetchError(
    val code: PlaylistFetchErrorCode,
    val message: String,
    val retryHint: String? = null
) {
    companion object {
        /** Fallback for failures that could not be classified. */
        fun unknown(rawMessage: String?): PlaylistFetchError =
            PlaylistFetchError(
                code = PlaylistFetchErrorCode.UNKNOWN,
                message = rawMessage?.takeIf { it.isNotBlank() }
                    ?: "The playlist could not be fetched."
            )
    }
}

/**
 * Sealed outcome of a bridge call. Callers pattern-match on [Success] vs
 * [Failure]; the bridge never throws to callers.
 */
sealed class PlaylistFetchResult {
    data class Success(val metadata: PlaylistMetadata) : PlaylistFetchResult()
    data class Failure(val error: PlaylistFetchError) : PlaylistFetchResult()
}
