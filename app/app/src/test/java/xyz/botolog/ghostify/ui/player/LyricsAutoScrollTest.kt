package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsAutoScrollTest {

    private val lines = parseLrc(
        """
        [00:05.00]first line
        [00:10.00]second line
        [00:15.00]third line
        """.trimIndent(),
    )

    private fun targetFor(positionMs: Long): LyricsScrollTarget? =
        lyricsAutoScrollTarget(currentLineIndex(lines, positionMs), lines.size)

    @Test
    fun positionBeforeFirstTimestampScrollsToTop() {
        assertEquals(5_000L, lines.first().timeMs)
        assertEquals(LyricsScrollTarget(index = 0, offsetPx = 0), targetFor(0L))
    }

    @Test
    fun positionBeforeFirstTimestampKeepsTheSameTopTarget() {
        val targets = listOf(0L, 1L, 2L, 4_999L).map { targetFor(it) }

        assertEquals(1, targets.distinct().size)
        assertEquals(LyricsScrollTarget(index = 0, offsetPx = 0), targets.first())
    }

    @Test
    fun positionBeforeALateFirstTimestampScrollsToTop() {
        val late = parseLrc("[01:30.00]late start")

        assertEquals(
            LyricsScrollTarget(index = 0, offsetPx = 0),
            lyricsAutoScrollTarget(currentLineIndex(late, 89_999L), late.size),
        )
    }

    @Test
    fun activeLineIsScrolledToWithTheSameOffset() {
        val expected = listOf(
            5_000L to 0,
            10_000L to 1,
            14_999L to 1,
            15_000L to 2,
        )

        expected.forEach { (positionMs, index) ->
            assertEquals(
                "position $positionMs",
                LyricsScrollTarget(index = index, offsetPx = -200),
                targetFor(positionMs),
            )
        }
    }

    @Test
    fun emptyLyricsNeverScroll() {
        assertNull(lyricsAutoScrollTarget(currentLineIndex(emptyList(), 0L), 0))
        assertNull(lyricsAutoScrollTarget(-1, 0))
    }

    @Test
    fun outOfRangeLineIndexNeverScrolls() {
        assertNull(lyricsAutoScrollTarget(currentIndex = 3, lineCount = 3))
        assertNull(lyricsAutoScrollTarget(currentIndex = 9, lineCount = 3))
    }

    @Test
    fun scrollDurationGrowsWithDistanceAndIsCapped() {
        assertEquals(400, lyricsScrollDurationMillis(0))
        assertEquals(520, lyricsScrollDurationMillis(1))
        assertEquals(880, lyricsScrollDurationMillis(4))
        assertEquals(1_000, lyricsScrollDurationMillis(50))
    }
}
