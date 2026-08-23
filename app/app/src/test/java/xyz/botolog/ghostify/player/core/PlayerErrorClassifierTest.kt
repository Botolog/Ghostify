package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorClassifierTest {

    // ── Skippable errors (1000–5999, excluding stopCodes) ────────────────

    @Test
    fun unspecifiedErrorIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(1000))
    }

    @Test
    fun ioUnspecifiedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(2000))
    }

    @Test
    fun ioFileNotFoundIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(2005))
    }

    @Test
    fun ioNoPermissionIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(2006))
    }

    @Test
    fun parsingContainerMalformedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(3001))
    }

    @Test
    fun parsingContainerUnsupportedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(3003))
    }

    @Test
    fun decodingFailedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(4003))
    }

    @Test
    fun decodingFormatUnsupportedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(4005))
    }

    @Test
    fun audioTrackInitFailedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(5001))
    }

    @Test
    fun audioTrackWriteFailedIsSkippable() {
        assertEquals(ErrorAction.SKIP_CURRENT, PlayerErrorClassifier.classify(5002))
    }

    @Test
    fun allSkippableCodesReturnSkipCurrent() {
        val skippableCodes = listOf(
            1000,
            2000, 2001, 2002, 2003, 2004, 2005, 2006, 2007, 2008,
            3001, 3002, 3003, 3004,
            4001, 4002, 4003, 4004, 4005, 4006,
            5001, 5002,
        )
        skippableCodes.forEach { code ->
            assertEquals(
                "code=$code should be SKIP_CURRENT",
                ErrorAction.SKIP_CURRENT,
                PlayerErrorClassifier.classify(code),
            )
        }
    }

    // ── Stop-playback errors ─────────────────────────────────────────────

    @Test
    fun remoteErrorStopsPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(1001))
    }

    @Test
    fun behindLiveWindowStopsPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(1002))
    }

    @Test
    fun timeoutStopsPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(1003))
    }

    @Test
    fun failedRuntimeCheckStopsPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(1004))
    }

    @Test
    fun stopCodesAreIdentifiedCorrectly() {
        val stopCodes = listOf(1001, 1002, 1003, 1004)
        stopCodes.forEach { code ->
            assertEquals(
                "code=$code should be STOP_PLAYBACK",
                ErrorAction.STOP_PLAYBACK,
                PlayerErrorClassifier.classify(code),
            )
        }
    }

    // ── Out-of-range errors ──────────────────────────────────────────────

    @Test
    fun errorAboveSkippableRangeStopsPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(6000))
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(6001))
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(Int.MAX_VALUE))
    }

    @Test
    fun errorBelowSkippableRangeStopsPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(-1))
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(0))
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(999))
    }

    @Test
    fun negativeErrorCodesStopPlayback() {
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(-109))
        assertEquals(ErrorAction.STOP_PLAYBACK, PlayerErrorClassifier.classify(Int.MIN_VALUE))
    }
}
