package com.ghostify.player.core

import kotlin.test.*

/**
 * JVM mirror of the state-mapping half of T-080/T-090/T-092 and the shuffle/repeat exposure
 * (T-083/T-084): raw player values must be turned into the UI state correctly, including the
 * subtle cases (buffering derivation, unknown durations, shuffled order).
 */
class PlayerStateMapperTest {

    private val queue = listOf(
        QueueItem("s1", "One", "A", "Al", 1000L, "/x/1.mp3", 0),
        QueueItem("s2", "Two", "B", "Al", 2000L, "/x/2.mp3", 1),
        QueueItem("s3", "Three", "C", "Al", 3000L, "/x/3.mp3", 2),
    )

    private fun snapshot(block: PlayerSnapshot.() -> PlayerSnapshot = { this }) = PlayerSnapshot()

    @Test
    fun `ready and playing maps through`() {
        val ui = PlayerStateMapper.toUiState(
            snapshot = PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                isPlaying = true,
                itemCount = 3,
                currentItemIndex = 1,
                currentMediaId = "s2",
                currentTitle = "Two",
                currentArtist = "B",
                currentAlbum = "Al",
            ),
            queue = queue,
            nothingToPlay = false,
            lastError = null,
        )

        assertEquals(PlaybackStatus.READY, ui.playbackStatus)
        assertTrue(ui.isPlaying)
        assertFalse(ui.isBuffering)
        assertEquals(1, ui.currentQueueIndex)
        assertEquals("s2", ui.currentItem?.mediaId)
        assertEquals("Two", ui.currentItem?.title)
        assertEquals("B", ui.currentItem?.artist)
        assertTrue(ui.hasQueue)
        assertFalse(ui.nothingToPlay)
    }

    @Test
    fun `current queue index is resolved by mediaId so shuffle cannot desync it`() {
        // The player reports a shuffled timeline index; the queue highlight must follow the
        // actual mediaId, not the raw index.
        val ui = PlayerStateMapper.toUiState(
            snapshot = PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                isPlaying = true,
                currentItemIndex = 0,
                itemCount = 3,
                currentMediaId = "s3",
            ),
            queue = queue,
            nothingToPlay = false,
            lastError = null,
        )
        assertEquals(2, ui.currentQueueIndex)
    }

    @Test
    fun `shuffle and repeat are surfaced and mapped`() {
        val ui = PlayerStateMapper.toUiState(
            snapshot = PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                itemCount = 3,
                shuffleEnabled = true,
                repeatMode = 2,
            ),
            queue = queue,
            nothingToPlay = false,
            lastError = null,
        )
        assertTrue(ui.shuffleEnabled)
        assertEquals(RepeatMode.ALL, ui.repeatMode)
    }

    @Test
    fun `buffering is derived from loading while playing`() {
        val buffering = PlayerStateMapper.toUiState(
            PlayerSnapshot(playbackState = PlaybackStatus.BUFFERING.playerValue, itemCount = 3),
            queue, false, null,
        )
        assertTrue(buffering.isBuffering)

        val loadingWhilePlaying = PlayerStateMapper.toUiState(
            PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                isPlaying = true,
                isLoading = true,
                itemCount = 3,
            ),
            queue, false, null,
        )
        assertTrue(loadingWhilePlaying.isBuffering)
    }

    @Test
    fun `duration prefers the player value and falls back to metadata`() {
        val known = PlayerStateMapper.toUiState(
            PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                currentDurationMs = 42_000L,
                itemCount = 3,
            ),
            queue, false, null,
        )
        assertEquals(42_000L, known.durationMs)

        val unknownButMetadata = PlayerStateMapper.toUiState(
            PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                currentDurationMs = PlaybackConstants.TIME_UNSET,
                itemMetadataDurationMs = 7_000L,
                itemCount = 3,
            ),
            queue, false, null,
        )
        assertEquals(7_000L, unknownButMetadata.durationMs)

        val unknown = PlayerStateMapper.toUiState(
            PlayerSnapshot(playbackState = PlaybackStatus.READY.playerValue, itemCount = 3),
            queue, false, null,
        )
        assertEquals(0L, unknown.durationMs)
    }

    @Test
    fun `position is never negative`() {
        val ui = PlayerStateMapper.toUiState(
            PlayerSnapshot(
                playbackState = PlaybackStatus.BUFFERING.playerValue,
                currentPositionMs = -5L,
                itemCount = 3,
            ),
            queue, false, null,
        )
        assertEquals(0L, ui.positionMs)
    }

    @Test
    fun `empty queue yields no current item and no crash`() {
        val ui = PlayerStateMapper.toUiState(
            PlayerSnapshot(playbackState = PlaybackStatus.IDLE.playerValue, itemCount = 0),
            queue = emptyList(),
            nothingToPlay = true,
            lastError = null,
        )
        assertNull(ui.currentItem)
        assertEquals(-1, ui.currentQueueIndex)
        assertFalse(ui.hasQueue)
        assertTrue(ui.nothingToPlay)
    }

    @Test
    fun `metadata falls back to the queue item at the reported index`() {
        val ui = PlayerStateMapper.toUiState(
            PlayerSnapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                isPlaying = true,
                currentItemIndex = 2,
                itemCount = 3,
            ),
            queue, false, null,
        )
        assertEquals("s3", ui.currentItem?.mediaId)
        assertEquals("Three", ui.currentItem?.title)
    }

    @Test
    fun `last error is carried through`() {
        val ui = PlayerStateMapper.toUiState(
            PlayerSnapshot(playbackState = PlaybackStatus.IDLE.playerValue, itemCount = 0),
            queue, true, PlayerError(2005, "file not found"),
        )
        assertEquals(2005, ui.lastError?.errorCode)
    }
}
