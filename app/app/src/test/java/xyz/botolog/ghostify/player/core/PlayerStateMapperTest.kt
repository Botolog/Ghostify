package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateMapperTest {

    private fun snapshot(
        playbackState: Int = PlaybackStatus.IDLE.playerValue,
        isPlaying: Boolean = false,
        isLoading: Boolean = false,
        currentItemIndex: Int = 0,
        itemCount: Int = 0,
        currentPositionMs: Long = 0L,
        currentDurationMs: Long = PlaybackConstants.TIME_UNSET,
        itemMetadataDurationMs: Long? = null,
        repeatMode: Int = RepeatMode.OFF.media3Value,
        shuffleEnabled: Boolean = false,
        volume: Float = 1f,
        currentMediaId: String? = null,
        currentTitle: String? = null,
        currentArtist: String? = null,
        currentAlbum: String? = null,
        artworkBytes: ByteArray? = null,
    ) = PlayerSnapshot(
        playbackState = playbackState,
        isPlaying = isPlaying,
        isLoading = isLoading,
        currentItemIndex = currentItemIndex,
        itemCount = itemCount,
        currentPositionMs = currentPositionMs,
        currentDurationMs = currentDurationMs,
        itemMetadataDurationMs = itemMetadataDurationMs,
        repeatMode = repeatMode,
        shuffleEnabled = shuffleEnabled,
        volume = volume,
        currentMediaId = currentMediaId,
        currentTitle = currentTitle,
        currentArtist = currentArtist,
        currentAlbum = currentAlbum,
        artworkBytes = artworkBytes,
    )

    private fun queueItem(songId: String, title: String = "T") = QueueItem(
        songId = songId, title = title, artist = "A", album = "B",
        durationMs = 100L, filePath = "/f.mp3", indexInQueue = 0,
    )

    // ── toUiState mapping ────────────────────────────────────────────────

    @Test
    fun mapsPlaybackStatusFromSnapshot() {
        val snap = snapshot(playbackState = PlaybackStatus.READY.playerValue)
        val state = PlayerStateMapper.toUiState(snap, emptyList(), false, null)
        assertEquals(PlaybackStatus.READY, state.playbackStatus)
    }

    @Test
    fun mapsIsPlayingFromSnapshot() {
        val state = PlayerStateMapper.toUiState(snapshot(isPlaying = true), emptyList(), false, null)
        assertTrue(state.isPlaying)

        val state2 = PlayerStateMapper.toUiState(snapshot(isPlaying = false), emptyList(), false, null)
        assertFalse(state2.isPlaying)
    }

    @Test
    fun mapsShuffleEnabledFromSnapshot() {
        val state = PlayerStateMapper.toUiState(snapshot(shuffleEnabled = true), emptyList(), false, null)
        assertTrue(state.shuffleEnabled)
    }

    @Test
    fun mapsRepeatModeFromSnapshot() {
        val state = PlayerStateMapper.toUiState(
            snapshot(repeatMode = RepeatMode.ONE.media3Value), emptyList(), false, null,
        )
        assertEquals(RepeatMode.ONE, state.repeatMode)
    }

    @Test
    fun mapsVolumeFromSnapshot() {
        val state = PlayerStateMapper.toUiState(snapshot(volume = 0.3f), emptyList(), false, null)
        assertEquals(0.3f, state.volume)
    }

    @Test
    fun mapsNothingToPlayFlag() {
        val state = PlayerStateMapper.toUiState(snapshot(), emptyList(), true, null)
        assertTrue(state.nothingToPlay)

        val state2 = PlayerStateMapper.toUiState(snapshot(), emptyList(), false, null)
        assertFalse(state2.nothingToPlay)
    }

    @Test
    fun mapsLastError() {
        val error = PlayerError(42, "boom")
        val state = PlayerStateMapper.toUiState(snapshot(), emptyList(), false, error)
        assertEquals(error, state.lastError)
    }

    @Test
    fun mapsQueueAndHasQueue() {
        val items = listOf(queueItem("a"), queueItem("b"))
        val state = PlayerStateMapper.toUiState(snapshot(itemCount = 2), items, false, null)
        assertEquals(items, state.queue)
        assertTrue(state.hasQueue)
    }

    @Test
    fun hasQueueIsFalseWhenItemCountZero() {
        val state = PlayerStateMapper.toUiState(snapshot(itemCount = 0), emptyList(), false, null)
        assertFalse(state.hasQueue)
    }

    @Test
    fun positionIsCoercedToAtLeastZero() {
        val state = PlayerStateMapper.toUiState(snapshot(currentPositionMs = -100L), emptyList(), false, null)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun positionPreservesPositiveValue() {
        val state = PlayerStateMapper.toUiState(snapshot(currentPositionMs = 5000L), emptyList(), false, null)
        assertEquals(5000L, state.positionMs)
    }

    // ── resolveIsBuffering ───────────────────────────────────────────────

    @Test
    fun isBufferingTrueWhenPlaybackStatusIsBuffering() {
        val state = PlayerStateMapper.toUiState(
            snapshot(playbackState = PlaybackStatus.BUFFERING.playerValue), emptyList(), false, null,
        )
        assertTrue(state.isBuffering)
    }

    @Test
    fun isBufferingTrueWhenPlayingAndLoading() {
        val state = PlayerStateMapper.toUiState(
            snapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                isPlaying = true,
                isLoading = true,
            ), emptyList(), false, null,
        )
        assertTrue(state.isBuffering)
    }

    @Test
    fun isBufferingFalseWhenReadyAndNotLoading() {
        val state = PlayerStateMapper.toUiState(
            snapshot(
                playbackState = PlaybackStatus.READY.playerValue,
                isPlaying = true,
                isLoading = false,
            ), emptyList(), false, null,
        )
        assertFalse(state.isBuffering)
    }

    @Test
    fun isBufferingFalseWhenLoadingButNotPlaying() {
        val state = PlayerStateMapper.toUiState(
            snapshot(
                playbackState = PlaybackStatus.BUFFERING.playerValue,
                isPlaying = false,
                isLoading = true,
            ), emptyList(), false, null,
        )
        assertTrue(state.isBuffering)
    }

    @Test
    fun isBufferingFalseWhenIdle() {
        val state = PlayerStateMapper.toUiState(snapshot(), emptyList(), false, null)
        assertFalse(state.isBuffering)
    }

    // ── resolveCurrentQueueIndex ─────────────────────────────────────────

    @Test
    fun currentQueueIndexResolvedFromMediaId() {
        val items = listOf(queueItem("a"), queueItem("b"), queueItem("c"))
        val state = PlayerStateMapper.toUiState(
            snapshot(itemCount = 3, currentMediaId = "b"),
            items, false, null,
        )
        assertEquals(1, state.currentQueueIndex)
    }

    @Test
    fun currentQueueIndexMinusOneWhenMediaIdNull() {
        val state = PlayerStateMapper.toUiState(snapshot(), emptyList(), false, null)
        assertEquals(-1, state.currentQueueIndex)
    }

    @Test
    fun currentQueueIndexMinusOneWhenMediaIdNotFoundInQueue() {
        val items = listOf(queueItem("a"), queueItem("b"))
        val state = PlayerStateMapper.toUiState(
            snapshot(itemCount = 2, currentMediaId = "z"),
            items, false, null,
        )
        assertEquals(-1, state.currentQueueIndex)
    }

    // ── buildCurrentItem ─────────────────────────────────────────────────

    @Test
    fun currentItemIsNullWhenItemCountZero() {
        val state = PlayerStateMapper.toUiState(snapshot(itemCount = 0), emptyList(), false, null)
        assertNull(state.currentItem)
    }

    @Test
    fun currentItemUsesSnapshotMetadata() {
        val items = listOf(queueItem("a"))
        val state = PlayerStateMapper.toUiState(
            snapshot(
                itemCount = 1,
                currentMediaId = "a",
                currentTitle = "Snapshot Title",
                currentArtist = "Snapshot Artist",
                currentAlbum = "Snapshot Album",
            ),
            items, false, null,
        )
        val item = state.currentItem!!
        assertEquals("a", item.mediaId)
        assertEquals("Snapshot Title", item.title)
        assertEquals("Snapshot Artist", item.artist)
        assertEquals("Snapshot Album", item.album)
    }

    @Test
    fun currentItemFallsBackToQueueMetadata() {
        val items = listOf(queueItem("a", title = "Queue Title"))
        val state = PlayerStateMapper.toUiState(
            snapshot(
                itemCount = 1,
                currentItemIndex = 0,
                currentMediaId = null,
                currentTitle = null,
                currentArtist = null,
                currentAlbum = null,
            ),
            items, false, null,
        )
        val item = state.currentItem!!
        assertEquals("a", item.mediaId)
        assertEquals("Queue Title", item.title)
    }

    @Test
    fun currentItemArtworkComesFromSnapshot() {
        val artwork = byteArrayOf(0xFF.toByte())
        val items = listOf(queueItem("a"))
        val state = PlayerStateMapper.toUiState(
            snapshot(itemCount = 1, artworkBytes = artwork),
            items, false, null,
        )
        assertEquals(artwork, state.currentItem!!.artworkBytes)
    }
}

// ── DurationNormalizer ───────────────────────────────────────────────────

class DurationNormalizerTest {

    @Test
    fun normalizeReturnsPlayerDurationWhenPositive() {
        assertEquals(120_000L, DurationNormalizer.normalize(120_000L, 90_000L))
    }

    @Test
    fun normalizeReturnsPlayerDurationEvenWhenMetadataAlsoPositive() {
        assertEquals(200_000L, DurationNormalizer.normalize(200_000L, 100_000L))
    }

    @Test
    fun normalizeFallsBackToMetadataWhenPlayerDurationIsTimeUnset() {
        assertEquals(90_000L, DurationNormalizer.normalize(PlaybackConstants.TIME_UNSET, 90_000L))
    }

    @Test
    fun normalizeFallsBackToMetadataWhenPlayerDurationIsZero() {
        assertEquals(60_000L, DurationNormalizer.normalize(0L, 60_000L))
    }

    @Test
    fun normalizeFallsBackToMetadataWhenPlayerDurationIsNegative() {
        assertEquals(50_000L, DurationNormalizer.normalize(-1L, 50_000L))
    }

    @Test
    fun normalizeReturnsZeroWhenBothDurationsAreUnknown() {
        assertEquals(0L, DurationNormalizer.normalize(PlaybackConstants.TIME_UNSET, null))
    }

    @Test
    fun normalizeReturnsZeroWhenBothDurationsAreZero() {
        assertEquals(0L, DurationNormalizer.normalize(0L, 0L))
    }

    @Test
    fun normalizeReturnsZeroWhenBothDurationsAreNegative() {
        assertEquals(0L, DurationNormalizer.normalize(-1L, -5L))
    }

    @Test
    fun normalizeReturnsZeroWhenMetadataIsNull() {
        assertEquals(0L, DurationNormalizer.normalize(0L, null))
    }

    @Test
    fun normalizeReturnsZeroWhenMetadataIsZero() {
        assertEquals(0L, DurationNormalizer.normalize(0L, 0L))
    }

    @Test
    fun normalizePrefersPlayerDurationOverMetadata() {
        assertEquals(300_000L, DurationNormalizer.normalize(300_000L, 150_000L))
    }
}
