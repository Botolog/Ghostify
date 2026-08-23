package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ── PlayerUiState ────────────────────────────────────────────────────────

class PlayerUiStateTest {

    @Test
    fun defaultValuesAreCorrect() {
        val state = PlayerUiState()
        assertEquals(PlaybackStatus.IDLE, state.playbackStatus)
        assertFalse(state.isPlaying)
        assertFalse(state.isBuffering)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertNull(state.currentItem)
        assertEquals(-1, state.currentQueueIndex)
        assertEquals(emptyList<QueueItem>(), state.queue)
        assertFalse(state.hasQueue)
        assertFalse(state.nothingToPlay)
        assertFalse(state.shuffleEnabled)
        assertEquals(RepeatMode.OFF, state.repeatMode)
        assertEquals(1f, state.volume)
        assertNull(state.lastError)
    }

    @Test
    fun dataClassEqualityWorks() {
        val a = PlayerUiState(isPlaying = true, volume = 0.5f)
        val b = PlayerUiState(isPlaying = true, volume = 0.5f)
        assertEquals(a, b)
    }

    @Test
    fun dataClassCopyPreservesUnmodifiedFields() {
        val original = PlayerUiState(
            playbackStatus = PlaybackStatus.READY,
            isPlaying = true,
            volume = 0.8f,
        )
        val copied = original.copy(isPlaying = false)
        assertFalse(copied.isPlaying)
        assertEquals(PlaybackStatus.READY, copied.playbackStatus)
        assertEquals(0.8f, copied.volume)
    }
}

// ── PlayerError ──────────────────────────────────────────────────────────

class PlayerErrorTest {

    @Test
    fun dataClassCreationWorks() {
        val error = PlayerError(errorCode = 42, message = "something broke")
        assertEquals(42, error.errorCode)
        assertEquals("something broke", error.message)
    }

    @Test
    fun messageCanBeNull() {
        val error = PlayerError(errorCode = 1, message = null)
        assertNull(error.message)
    }

    @Test
    fun dataClassEqualityWorks() {
        val a = PlayerError(1, "msg")
        val b = PlayerError(1, "msg")
        assertEquals(a, b)
    }
}

// ── CurrentItem ──────────────────────────────────────────────────────────

class CurrentItemTest {

    @Test
    fun allFieldsCanBeSet() {
        val item = CurrentItem(
            mediaId = "m1",
            title = "T",
            artist = "A",
            album = "B",
            artworkBytes = byteArrayOf(1, 2, 3),
            coverUrl = "https://example.com/c.jpg",
        )
        assertEquals("m1", item.mediaId)
        assertEquals("T", item.title)
        assertEquals("A", item.artist)
        assertEquals("B", item.album)
        assertEquals(3, item.artworkBytes!!.size)
        assertEquals("https://example.com/c.jpg", item.coverUrl)
    }

    @Test
    fun allFieldsCanBeNullable() {
        val item = CurrentItem(null, null, null, null, null, null)
        assertNull(item.mediaId)
        assertNull(item.title)
        assertNull(item.artist)
        assertNull(item.album)
        assertNull(item.artworkBytes)
        assertNull(item.coverUrl)
    }

    @Test
    fun coverUrlDefaultsToNull() {
        val item = CurrentItem("m1", null, null, null, null)
        assertNull(item.coverUrl)
    }
}

// ── PlayerSnapshot ───────────────────────────────────────────────────────

class PlayerSnapshotTest {

    @Test
    fun defaultValuesAreCorrect() {
        val snap = PlayerSnapshot()
        assertEquals(PlaybackStatus.IDLE.playerValue, snap.playbackState)
        assertFalse(snap.isPlaying)
        assertFalse(snap.playWhenReady)
        assertFalse(snap.isLoading)
        assertEquals(0, snap.currentItemIndex)
        assertEquals(0, snap.itemCount)
        assertEquals(0L, snap.currentPositionMs)
        assertEquals(PlaybackConstants.TIME_UNSET, snap.currentDurationMs)
        assertNull(snap.itemMetadataDurationMs)
        assertEquals(RepeatMode.OFF.media3Value, snap.repeatMode)
        assertFalse(snap.shuffleEnabled)
        assertEquals(1f, snap.volume)
        assertNull(snap.currentMediaId)
        assertNull(snap.currentTitle)
        assertNull(snap.currentArtist)
        assertNull(snap.currentAlbum)
        assertNull(snap.artworkBytes)
    }

    @Test
    fun dataClassCreationWithAllFields() {
        val snap = PlayerSnapshot(
            playbackState = PlaybackStatus.READY.playerValue,
            isPlaying = true,
            playWhenReady = true,
            isLoading = false,
            currentItemIndex = 2,
            itemCount = 5,
            currentPositionMs = 15_000L,
            currentDurationMs = 200_000L,
            itemMetadataDurationMs = 200_000L,
            repeatMode = RepeatMode.ONE.media3Value,
            shuffleEnabled = true,
            volume = 0.7f,
            currentMediaId = "song-42",
            currentTitle = "My Song",
            currentArtist = "My Artist",
            currentAlbum = "My Album",
            artworkBytes = byteArrayOf(0xFF.toByte()),
        )
        assertEquals(PlaybackStatus.READY.playerValue, snap.playbackState)
        assertTrue(snap.isPlaying)
        assertTrue(snap.playWhenReady)
        assertEquals(2, snap.currentItemIndex)
        assertEquals(5, snap.itemCount)
        assertEquals(15_000L, snap.currentPositionMs)
        assertEquals(200_000L, snap.currentDurationMs)
        assertEquals(200_000L, snap.itemMetadataDurationMs)
        assertEquals(RepeatMode.ONE.media3Value, snap.repeatMode)
        assertTrue(snap.shuffleEnabled)
        assertEquals(0.7f, snap.volume)
        assertEquals("song-42", snap.currentMediaId)
        assertEquals("My Song", snap.currentTitle)
        assertEquals("My Artist", snap.currentArtist)
        assertEquals("My Album", snap.currentAlbum)
    }

    @Test
    fun dataClassEqualityWorks() {
        val a = PlayerSnapshot(isPlaying = true, itemCount = 3)
        val b = PlayerSnapshot(isPlaying = true, itemCount = 3)
        assertEquals(a, b)
    }
}

// ── QueueBuildResult ─────────────────────────────────────────────────────

class QueueBuildResultTest {

    private fun makeItem(id: String = "s1") = QueueItem(
        songId = id, title = "T", artist = "A", album = "B",
        durationMs = 100L, filePath = "/f.mp3", indexInQueue = 0,
    )

    @Test
    fun readyResultCanBeCreated() {
        val items = listOf(makeItem())
        val result = QueueBuildResult.Ready(items = items, startIndex = 0)
        assertEquals(items, result.items)
        assertEquals(0, result.startIndex)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyResultRejectsEmptyItems() {
        QueueBuildResult.Ready(items = emptyList(), startIndex = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyResultRejectsStartIndexOutOfBounds() {
        QueueBuildResult.Ready(items = listOf(makeItem()), startIndex = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyResultRejectsNegativeStartIndex() {
        QueueBuildResult.Ready(items = listOf(makeItem()), startIndex = -1)
    }

    @Test
    fun readyResultAcceptsStartIndexAtLastPosition() {
        val items = listOf(makeItem("a"), makeItem("b"), makeItem("c"))
        val result = QueueBuildResult.Ready(items = items, startIndex = 2)
        assertEquals(2, result.startIndex)
    }

    @Test
    fun nothingToPlayIsSingleton() {
        val a = QueueBuildResult.NothingToPlay
        val b = QueueBuildResult.NothingToPlay
        assertEquals(a, b)
    }

    @Test
    fun readyAndNothingToPlayAreDifferentTypes() {
        val ready = QueueBuildResult.Ready(listOf(makeItem()), 0)
        assertTrue(ready is QueueBuildResult)
        assertTrue(QueueBuildResult.NothingToPlay is QueueBuildResult)
    }
}
