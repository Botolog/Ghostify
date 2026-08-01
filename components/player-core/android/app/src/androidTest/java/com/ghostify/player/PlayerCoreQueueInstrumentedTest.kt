package com.ghostify.player

import com.ghostify.player.core.PlayerUiState
import com.ghostify.player.core.SongStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-079 / T-086 / T-087 / T-092 — queue construction from DOWNLOADED songs only,
 * the "nothing to play" state, and queue stability while playing.
 */
class PlayerCoreQueueInstrumentedTest : PlayerCoreInstrumentedTest() {

    private lateinit var wavs: List<File>

    @Before
    fun setUp() {
        wavs = (1..3).map { TestAudioFactory.wav(context, "q$it.wav", durationMs = 3000) }
        createController()
    }

    @Test
    fun `T-079 queue builds one MediaItem per downloaded song in playlist order`() {
        val shuffledInput = listOf(
            downloadedSong("s3", wavs[2]),
            downloadedSong("s1", wavs[0]),
            downloadedSong("s2", wavs[1]),
        )
        play(shuffledInput)

        val state = awaitState { it.queue.size == 3 }
        assertEquals(listOf("s3", "s1", "s2"), state.queue.map { it.songId })
        assertEquals(listOf("s3", "s1", "s2"), state.queue.map { it.filePath })
        assertEquals("s3", state.currentItem?.mediaId)
    }

    @Test
    fun `T-086 non-downloaded songs are excluded from the queue with no errors`() {
        play(
            listOf(
                downloadedSong("down-1", wavs[0]),
                nonDownloadedSong("pending", status = SongStatus.PENDING),
                nonDownloadedSong("failed", status = SongStatus.FAILED),
                nonDownloadedSong("downloading", status = SongStatus.DOWNLOADING),
                downloadedSong("down-2", wavs[1]),
            ),
        )

        val state = awaitState { it.queue.size == 2 && it.isPlaying }
        assertEquals(listOf("down-1", "down-2"), state.queue.map { it.songId })
        assertNull("no player error for a downloaded-only queue", state.lastError)
    }

    @Test
    fun `T-087 zero downloaded songs shows nothing-to-play and never crashes`() {
        play(
            listOf(
                nonDownloadedSong("p1"),
                nonDownloadedSong("p2"),
                nonDownloadedSong("f1", status = SongStatus.FAILED),
            ),
        )

        val state = awaitState { it.nothingToPlay }
        assertTrue(state.nothingToPlay)
        assertTrue(state.queue.isEmpty())
        assertFalse(state.hasQueue)
        assertNull(state.currentItem)
        assertEquals(-1, state.currentQueueIndex)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `T-092 playing queue stays stable until an explicit rebuild`() {
        play(listOf(downloadedSong("s1", wavs[0]), downloadedSong("s2", wavs[1]), downloadedSong("s3", wavs[2])))

        val started = awaitState { it.isPlaying && it.queue.size == 3 }
        assertEquals(0, started.currentQueueIndex)
        assertEquals(listOf("s1", "s2", "s3"), started.queue.map { it.songId })

        val p1 = started.positionMs
        // While playing, the queue must not be rebuilt underneath us — same order, same song,
        // position simply advances.
        val settled = awaitState { it.positionMs >= p1 + 500 }
        assertEquals(listOf("s1", "s2", "s3"), settled.queue.map { it.songId })
        assertEquals("s1", settled.currentItem?.mediaId)
        assertEquals(0, settled.currentQueueIndex)

        // An explicit rebuild is the *only* thing that changes the queue.
        play(listOf(downloadedSong("s2", wavs[1]), downloadedSong("s3", wavs[2])))
        val rebuilt = awaitState { it.queue.size == 2 }
        assertEquals(listOf("s2", "s3"), rebuilt.queue.map { it.songId })
    }
}
