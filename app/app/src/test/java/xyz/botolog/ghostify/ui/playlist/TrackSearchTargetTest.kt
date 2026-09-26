package xyz.botolog.ghostify.ui.playlist

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landing on a song from a search result has to do two things: bring that song's row into view
 * and mark it for a moment. Both are decided here, apart from Compose itself — which list item
 * carries the song, and when the highlight has run out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackSearchTargetTest {

    private val trackIds = listOf("a", "b", "c", "d")

    @Test
    fun `the first track sits behind the header and the syncing row`() {
        assertEquals(2, trackListIndexOfSong(trackIds, "a"))
    }

    @Test
    fun `a track further down is reached through its own list index`() {
        assertEquals(3, trackListIndexOfSong(trackIds, "b"))
        assertEquals(5, trackListIndexOfSong(trackIds, "d"))
    }

    @Test
    fun `the last track never lands outside the list`() {
        val index = requireNotNull(trackListIndexOfSong(trackIds, "d"))

        assertEquals(trackIds.size + 1, index)
        assertTrue("index $index of ${trackIds.size + 2} items", index < trackIds.size + 2)
    }

    @Test
    fun `a song that is not in the playlist never scrolls the list`() {
        assertNull(trackListIndexOfSong(trackIds, "missing"))
    }

    @Test
    fun `no requested song never scrolls the list`() {
        assertNull(trackListIndexOfSong(trackIds, null))
        assertNull(trackListIndexOfSong(trackIds, ""))
    }

    @Test
    fun `a playlist with no tracks yet is left alone until the tracks arrive`() {
        assertNull(trackListIndexOfSong(emptyList(), "a"))

        // The same search result after the playlist has loaded its tracks.
        assertEquals(2, trackListIndexOfSong(trackIds, "a"))
    }

    @Test
    fun `a repeated id resolves to the row the list shows first`() {
        assertEquals(2, trackListIndexOfSong(listOf("a", "b", "a"), "a"))
    }

    @Test
    fun `the highlight is held for a short tasteful moment and then dropped`() {
        val duration = 1_000L

        runTest {
            val seen = mutableListOf<String?>()
            val highlight = launch { highlightSongWhileVisible("b", duration) { seen += it } }

            advanceTimeBy(duration - 1)
            assertEquals("highlighted before it expires", listOf("b"), seen)

            advanceUntilIdle()
            assertEquals("highlight expires instead of lasting", listOf("b", null), seen)

            highlight.join()
            advanceTimeBy(duration * 10)
            assertEquals("nothing re-highlights it afterwards", listOf("b", null), seen)
        }
    }

    @Test
    fun `the shipped highlight duration is about a second, never permanent`() {
        assertTrue(
            "highlight lasts ${HIGHLIGHT_DURATION_MS}ms",
            HIGHLIGHT_DURATION_MS in 500L..2_000L,
        )
    }

    @Test
    fun `no song never highlights anything`() {
        runTest {
            val seen = mutableListOf<String?>()
            launch { highlightSongWhileVisible(null) { seen += it } }
            launch { highlightSongWhileVisible("") { seen += it } }

            advanceUntilIdle()

            assertEquals(emptyList<String?>(), seen)
        }
    }

    @Test
    fun `searching the same song again highlights it again`() {
        val duration = 500L

        runTest {
            val seen = mutableListOf<String?>()
            repeat(2) { highlightSongWhileVisible("c", duration) { seen += it } }

            assertEquals(listOf("c", null, "c", null), seen)
        }
    }
}
