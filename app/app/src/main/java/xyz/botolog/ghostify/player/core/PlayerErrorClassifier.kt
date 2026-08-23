package xyz.botolog.ghostify.player.core

import timber.log.Timber

/** What the controller should do when a playable item fails. */
enum class ErrorAction {
    /** The current media item is unplayable — skip it and keep playing. */
    SKIP_CURRENT,

    /** Fatal/global error — stop playback and surface it. */
    STOP_PLAYBACK,
}

/**
 * Decides how to react to a `PlaybackException` from Media3, keyed on its `errorCode`.
 *
 * The codes below mirror `androidx.media3.common.PlaybackException.ERROR_CODE_*` (stable public
 * API). For an offline local-file player, load/parse/decode failures on a single item are almost
 * always "that file is corrupt" and should be skipped; DRM/remote/session-level errors are not.
 * The Android glue passes the real `errorCode` through; the numeric constants are duplicated here
 * purely so this classifier is unit-testable without the Media3 dependency.
 */
object PlayerErrorClassifier {

    // region General error codes (1000–1999)

    const val ERROR_CODE_UNSPECIFIED = 1000
    const val ERROR_CODE_REMOTE_ERROR = 1001
    const val ERROR_CODE_BEHIND_LIVE_WINDOW = 1002
    const val ERROR_CODE_TIMEOUT = 1003
    const val ERROR_CODE_FAILED_RUNTIME_CHECK = 1004

    // endregion

    // region I/O error codes (2000–2999)

    const val ERROR_CODE_IO_UNSPECIFIED = 2000
    const val ERROR_CODE_IO_NETWORK_CONNECTION_FAILED = 2001
    const val ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT = 2002
    const val ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE = 2003
    const val ERROR_CODE_IO_BAD_HTTP_STATUS = 2004
    const val ERROR_CODE_IO_FILE_NOT_FOUND = 2005
    const val ERROR_CODE_IO_NO_PERMISSION = 2006
    const val ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED = 2007
    const val ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE = 2008

    // endregion

    // region Parsing error codes (3000–3999)

    const val ERROR_CODE_PARSING_CONTAINER_MALFORMED = 3001
    const val ERROR_CODE_PARSING_MANIFEST_MALFORMED = 3002
    const val ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED = 3003
    const val ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED = 3004

    // endregion

    // region Decoder error codes (4000–4999)

    const val ERROR_CODE_DECODER_INIT_FAILED = 4001
    const val ERROR_CODE_DECODER_QUERY_FAILED = 4002
    const val ERROR_CODE_DECODING_FAILED = 4003
    const val ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
    const val ERROR_CODE_DECODING_FORMAT_UNSUPPORTED = 4005
    const val ERROR_CODE_DECODING_RESOURCES_RECLAIMED = 4006

    // endregion

    // region Audio track error codes (5000–5999)

    const val ERROR_CODE_AUDIO_TRACK_INIT_FAILED = 5001
    const val ERROR_CODE_AUDIO_TRACK_WRITE_FAILED = 5002

    // endregion

    /** First error code in the skippable range (inclusive). */
    private const val FIRST_SKIPPABLE = ERROR_CODE_UNSPECIFIED

    /** Last error code in the skippable range (inclusive). */
    private const val LAST_SKIPPABLE = 5999

    /** Error codes that should always stop playback regardless of range. */
    private val stopCodes = setOf(
        ERROR_CODE_REMOTE_ERROR,
        ERROR_CODE_BEHIND_LIVE_WINDOW,
        ERROR_CODE_TIMEOUT,
        ERROR_CODE_FAILED_RUNTIME_CHECK,
    )

    /**
     * Classifies an error code into an [ErrorAction] for the player controller.
     *
     * Errors in the skippable range (1000–5999) that are not in [stopCodes] are treated as
     * single-item failures that can be skipped. All other errors stop playback.
     *
     * @param errorCode The `PlaybackException.errorCode` from Media3.
     * @return The [ErrorAction] the controller should take.
     */
    fun classify(errorCode: Int): ErrorAction {
        Timber.i("PlayerErrorClassifier.classify: START errorCode=$errorCode")
        val result = when {
            errorCode in stopCodes -> ErrorAction.STOP_PLAYBACK
            errorCode in FIRST_SKIPPABLE..LAST_SKIPPABLE -> ErrorAction.SKIP_CURRENT
            else -> ErrorAction.STOP_PLAYBACK
        }
        Timber.i("PlayerErrorClassifier.classify: returning $result")
        return result
    }
}
