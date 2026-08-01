package com.ghostify.player

import com.ghostify.player.core.PlaybackStatus
import com.ghostify.player.core.PlayerUiState
import com.ghostify.player.core.RepeatMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-080..T-085 — transport controls: play/pause, next/prev at the ends, seek,
 * shuffle on/off, repeat off/all/one, and volume.
 */
class PlayerCorePlaybackInstrumentedTest : PlayerCoreInstrumentedTest() {

    private lateinit var wavs: List<File>

    @Before
    fun setUp() {
        wavs = (1..6).map { TestAudioFactory.wav(context, "p$it.wav", durationMs = 3000) }
        createController()
    }

    @Test
    fun `T-080 play and pause toggle playback state`() {
        play(listOf(downloadedSong("s1", wavs[0])))
        awaitState { it.isPlaying }
        assertTrue(state().isPlaying)

        runOnMain { controller?.pause() }
        val paused = awaitState { !it.isPlaying }
        assertEquals(PlaybackStatus.READY, paused.playbackStatus)

        runOnMain { controller?.play() }
        awaitState { it.isPlaying }
    }

    @Test
    fun `T-081 next and previous move through the queue including at the ends`() {
        play(listOf(downloadedSong("s1", wavs[0]), downloadedSong("s2", wavs[1]), downloadedSong("s3", wavs[2])))
        awaitState { it.isPlaying && it.currentItem?.mediaId == "s1" }

        runOnMain { controller?.next() }
        awaitState { it.currentItem?.mediaId == "s2" }

        runOnMain { controller?.next() }
        awaitState { it.currentItem?.mediaId == "s3" }

        // At the last item with repeat off, next does not wrap — we stay on the last song.
        runOnMain { controller?.next() }
        Thread.sleep(600)
        assertEquals("s3", state().currentItem?.mediaId)

        runOnMain { controller?.previous() }
        awaitState { it.currentItem?.mediaId == "s2" }

        runOnMain { controller?.previous() }
        awaitState { it.currentItem?.mediaId == "s1" }

        // At the first item, previous restarts it instead of wrapping to the end.
        runOnMain { controller?.previous() }
        Thread.sleep(600)
        assertEquals("s1", state().currentItem?.mediaId)
    }

    @Test
    fun `T-082 seek to an arbitrary position resumes from that position`() {
        play(listOf(downloadedSong("s1", wavs[0])))
        awaitState { it.isPlaying }

        runOnMain { controller?.seekTo(1500) }
        val state = awaitState { it.positionMs >= 1450 && it.isPlaying }
        assertTrue(
            "position should be near the seek target, was ${state.positionMs}",
            state.positionMs in 1400..2400,
        )
        assertTrue(state.isPlaying)
    }

    @Test
    fun `T-083 shuffle on randomises order and shuffle off restores playlist order`() {
        val songs = wavs.mapIndexed { i, f -> downloadedSong("s${i + 1}", f) }
        play(songs)
        awaitState { it.isPlaying && it.queue.size == 6 }

        val playlistOrder = listOf("s1", "s2", "s3", "s4", "s5", "s6")

        runOnMain { controller?.setShuffleEnabled(true) }
        awaitState { it.shuffleEnabled }
        val shuffledOrder = readTimelineOrder(6)
        // 6! permutations; probability the shuffle happens to equal the original order is 1/720.
        assertNotEquals("shuffled order should differ from playlist order", playlistOrder, shuffledOrder)

        runOnMain { controller?.setShuffleEnabled(false) }
        awaitState { !it.shuffleEnabled }
        assertEquals("shuffle off restores playlist order", playlistOrder, readTimelineOrder(6))
    }

    @Test
    fun `T-084 repeat off stops after last, repeat all loops, repeat one repeats current`() {
        // --- Repeat OFF: stops after the last item ---
        val shortA = TestAudioFactory.wav(context, "short-a.wav", 400)
        val shortB = TestAudioFactory.wav(context, "short-b.wav", 400)
        runOnMain { controller?.setRepeatMode(RepeatMode.OFF) }
        play(listOf(downloadedSong("a", shortA), downloadedSong("b", shortB)))

        awaitState { it.currentItem?.mediaId == "a" && it.isPlaying }
        awaitState { it.currentItem?.mediaId == "b" && it.isPlaying } // auto-advanced
        val ended = awaitState { it.playbackStatus == PlaybackStatus.ENDED }
        assertEquals("b", ended.currentItem?.mediaId)
        assertFalse(ended.isPlaying)

        // --- Repeat ALL: loops from last back to first ---
        runOnMain { controller?.setRepeatMode(RepeatMode.ALL) }
        play(listOf(downloadedSong("a", shortA), downloadedSong("b", shortB)))
        val observed = mutableListOf<String>()
        awaitState { s ->
            s.currentItem?.mediaId?.let { if (observed.isEmpty() || observed.last() != it) observed.add(it) }
            observed.contains("a") && observed.contains("b") && observed.last() == "a"
        }
        assertTrue("repeat all wraps b -> a", observed == listOf("a", "b", "a"))

        // --- Repeat ONE: the same item keeps restarting ---
        runOnMain { controller?.setRepeatMode(RepeatMode.ONE) }
        play(listOf(downloadedSong("only", shortA)))
        awaitState { it.isPlaying && it.positionMs > 300 }
        val wrapped = awaitState { it.currentItem?.mediaId == "only" && it.isPlaying && it.positionMs < 200 }
        assertTrue(wrapped.isPlaying)
        assertEquals("only", wrapped.currentItem?.mediaId)
    }

    @Test
    fun `T-085 volume slider changes player volume`() {
        play(listOf(downloadedSong("s1", wavs[0])))
        awaitState { it.queue.size == 1 }

        runOnMain { controller?.setVolume(0f) }
        assertEquals(0f, awaitState { it.volume == 0f }.volume, 0f)

        runOnMain { controller?.setVolume(0.5f) }
        assertEquals(0.5f, awaitState { it.volume == 0.5f }.volume, 1e-4f)

        runOnMain { controller?.setVolume(1f) }
        assertEquals(1f, awaitState { it.volume == 1f }.volume, 0f)
    }

    /**
     * Walks every position of the current (possibly shuffled) timeline via [PlayerUiState.queue]
     * indices and returns the mediaId at each. This is how the effective playback order — shuffle
     * on or off — is read back from the player.
     */
    private fun readTimelineOrder(itemCount: Int): List<String> {
        val order = ArrayList<String>(itemCount)
        var last: String? = null
        for (i in 0 until itemCount) {
            runOnMain { controller?.skipToMediaItem(i) }
            val s = awaitState { it.currentItem?.mediaId != null && it.currentItem.mediaId != last }
            last = s.currentItem!!.mediaId
            order.add(last!!)
        }
        return order
    }
}
