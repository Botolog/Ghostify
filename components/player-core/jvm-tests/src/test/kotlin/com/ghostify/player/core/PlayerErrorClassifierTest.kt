package com.ghostify.player.core

import kotlin.test.*

/**
 * JVM mirror of the corrupt-file handling contract behind T-088: load/parse/decode failures
 * must be classified as skippable, while session/remote/DRM-level errors must stop playback.
 * The numbers are Media3's stable public `PlaybackException.ERROR_CODE_*` values.
 */
class PlayerErrorClassifierTest {

    @Test
    fun `corrupt or unreadable local files are skipped`() {
        val skippable = listOf(
            PlayerErrorClassifier.ERROR_CODE_UNSPECIFIED,        // 1000
            PlayerErrorClassifier.ERROR_CODE_IO_UNSPECIFIED,     // 2000
            PlayerErrorClassifier.ERROR_CODE_IO_FILE_NOT_FOUND,  // 2005
            PlayerErrorClassifier.ERROR_CODE_IO_NO_PERMISSION,   // 2006
            PlayerErrorClassifier.ERROR_CODE_PARSING_CONTAINER_MALFORMED,   // 3001
            PlayerErrorClassifier.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, // 3003
            PlayerErrorClassifier.ERROR_CODE_DECODING_FAILED,               // 4003
            PlayerErrorClassifier.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,   // 4005
            PlayerErrorClassifier.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,       // 5001
        )
        skippable.forEach { code ->
            assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(code), "code=$code")
        }
    }

    @Test
    fun `global and non-file errors stop playback`() {
        val stopping = listOf(
            PlayerErrorClassifier.ERROR_CODE_REMOTE_ERROR,            // 1001
            PlayerErrorClassifier.ERROR_CODE_TIMEOUT,                 // 1003
            PlayerErrorClassifier.ERROR_CODE_FAILED_RUNTIME_CHECK,    // 1004
            6001, // DRM unsupported
            -109, // end of playlist
            -1,
        )
        stopping.forEach { code ->
            assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(code), "code=$code")
        }
    }
}
