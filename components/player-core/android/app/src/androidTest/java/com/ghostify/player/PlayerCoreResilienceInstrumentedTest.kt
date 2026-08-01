package com.ghostify.player

import com.ghostify.player.core.SongStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-088 / T-089 — graceful handling of corrupt files and auto-advance on song end.
 */
class PlayerCoreResilienceInstrumentedTest : PlayerCoreInstrumentedTest() {

    private lateinit var good: File
    private lateinit var short: File

    @Before
    fun setUp() {
        good = TestAudioFactory.wav(context, "good.wav", durationMs = 5000)
        short = TestAudioFactory.wav(context, "short.wav", durationMs = 400)
        createController()
    }

    @Test
    fun `T-088 corrupt mp3 is skipped gracefully and playback continues`() {
        val corrupt = TestAudioFactory.corruptMp3(context, "broken.mp3")
        play(
            listOf(
                downloadedSong("good-1", good),
                downloadedSong("corrupt", corrupt),
                downloadedSong("good-2", good),
            ),
        )
        awaitState { it.isPlaying && it.currentItem?.mediaId == "good-1" }

        // Advance into the corrupt file -> it must be skipped forward to the next good one.
        runOnMain { controller?.next() }
        val state = awaitState { it.currentItem?.mediaId == "good-2" && it.isPlaying }
        assertEquals("good-2", state.currentItem?.mediaId)
        assertTrue("player keeps playing after the skip", state.isPlaying)
    }

    @Test
    fun `T-088 a corrupt last item stops cleanly instead of crashing`() {
        val corrupt = TestAudioFactory.corruptMp3(context, "broken-last.mp3")
        play(listOf(downloadedSong("good-1", good), downloadedSong("corrupt", corrupt)))
        awaitState { it.isPlaying && it.currentItem?.mediaId == "good-1" }

        runOnMain { controller?.next() }
        // No crash: the player settles in an idle/ended-like state, still alive.
        awaitState { !it.isPlaying }
        assertTrue(state().queue.size == 2)
    }

    @Test
    fun `T-089 song end auto-advances to the next item`() {
        val first = TestAudioFactory.wav(context, "first.wav", durationMs = 400)
        play(listOf(downloadedSong("first", first), downloadedSong("second", good)))

        awaitState { it.currentItem?.mediaId == "first" && it.isPlaying }
        val state = awaitState { it.currentItem?.mediaId == "second" && it.isPlaying }
        assertEquals(1, state.currentQueueIndex)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `T-086 downloaded-only queue when some songs are missing on disk never errors`() {
        play(
            listOf(
                downloadedSong("present", good),
                downloadedSong("missing", File(context.cacheDir, "does-not-exist.mp3")),
                downloadedSong("present-2", short),
            ),
        )
        val state = awaitState { it.queue.size == 2 && it.isPlaying }
        assertEquals(listOf("present", "present-2"), state.queue.map { it.songId })
        assertNull(state.lastError)
    }
}
