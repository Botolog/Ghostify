package xyz.botolog.ghostify.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the widget's pure lyric logic ([lyricsWindow], [lyricRowDistance],
 * [plainLyricsLines], [widgetLyricsLines]). No Glance/Android types are involved.
 */
class WidgetLyricsTest {

    private val lines = (0..19).map { "line $it" }

    // ── window sizing + the RemoteViews Column cap ───────────────────────

    @Test
    fun defaultWindowSizeIsFive() {
        assertEquals(5, LYRICS_WINDOW_SIZE)
    }

    @Test
    fun windowSizeStaysWithinTheColumnChildCap() {
        // Each lyric row is ONE Column child, so the window must never exceed the
        // 10-child RemoteViews cap that used to truncate the highlighted line.
        assertTrue(
            "LYRICS_WINDOW_SIZE=$LYRICS_WINDOW_SIZE must be <= $WIDGET_COLUMN_MAX_CHILDREN",
            LYRICS_WINDOW_SIZE <= WIDGET_COLUMN_MAX_CHILDREN,
        )
        assertTrue(lyricsWindow(lines, currentLineIndex = 10).size <= WIDGET_COLUMN_MAX_CHILDREN)
    }

    // ── lyricsWindow: windowing + clamping ───────────────────────────────

    @Test
    fun windowIsCentredOnTheActiveLine() {
        val window = lyricsWindow(lines, currentLineIndex = 10)
        assertEquals(LYRICS_WINDOW_SIZE, window.size)
        assertEquals(8, window.first().index)
        assertEquals(12, window.last().index)
        // The active line sits in the middle of the window.
        assertEquals(10, window[LYRICS_WINDOW_SIZE / 2].index)
        assertEquals(1, window.count { it.isCurrent })
        assertTrue(window[LYRICS_WINDOW_SIZE / 2].isCurrent)
        assertEquals("line 10", window[LYRICS_WINDOW_SIZE / 2].text)
    }

    @Test
    fun windowPutsTheActiveLineAtTheMidpointIndex() {
        val window = lyricsWindow(lines, currentLineIndex = 7)
        assertEquals(LYRICS_WINDOW_SIZE / 2, window.indexOfFirst { it.isCurrent })
    }

    @Test
    fun windowClampsAtTheTop() {
        val window = lyricsWindow(lines, currentLineIndex = 0)
        assertEquals(0, window.first().index)
        assertEquals(4, window.last().index)
        assertTrue(window.first().isCurrent)
    }

    @Test
    fun windowClampsNearTheTopWithoutLosingTheActiveLine() {
        val window = lyricsWindow(lines, currentLineIndex = 1)
        assertEquals(0, window.first().index)
        assertEquals(4, window.last().index)
        assertTrue(window[1].isCurrent)
    }

    @Test
    fun windowClampsAtTheBottom() {
        val window = lyricsWindow(lines, currentLineIndex = 19)
        assertEquals(15, window.first().index)
        assertEquals(19, window.last().index)
        assertTrue(window.last().isCurrent)
    }

    @Test
    fun windowClampsNearTheBottomWithoutLosingTheActiveLine() {
        val window = lyricsWindow(lines, currentLineIndex = 18)
        assertEquals(15, window.first().index)
        assertEquals(19, window.last().index)
        assertTrue(window[3].isCurrent)
    }

    @Test
    fun theActiveLineStaysVisibleAtEveryPositionOfTheSong() {
        for (current in lines.indices) {
            val window = lyricsWindow(lines, currentLineIndex = current)
            assertEquals(LYRICS_WINDOW_SIZE, window.size)
            assertEquals(
                "highlight lost at index $current",
                1,
                window.count { it.isCurrent },
            )
            assertEquals(current, window.first { it.isCurrent }.index)
        }
    }

    @Test
    fun windowReturnsAllLinesWhenFewerThanTheWindowSize() {
        val short = listOf("a", "b", "c")
        val window = lyricsWindow(short, currentLineIndex = 1)
        assertEquals(listOf("a", "b", "c"), window.map { it.text })
        assertEquals(1, window.count { it.isCurrent })
        assertTrue(window[1].isCurrent)
    }

    @Test
    fun windowHasNoHighlightWhenIndexIsNegative() {
        val window = lyricsWindow(lines, currentLineIndex = -1)
        assertEquals(LYRICS_WINDOW_SIZE, window.size)
        assertEquals(0, window.first().index)
        assertFalse(window.any { it.isCurrent })
    }

    @Test
    fun windowIgnoresAnOutOfRangeIndexButStillRendersText() {
        val window = lyricsWindow(lines, currentLineIndex = 99)
        assertEquals(0, window.first().index)
        assertEquals(LYRICS_WINDOW_SIZE, window.size)
        assertFalse(window.any { it.isCurrent })
    }

    @Test
    fun windowIsEmptyForNoLines() {
        assertTrue(lyricsWindow(emptyList(), currentLineIndex = 0).isEmpty())
    }

    @Test
    fun windowSizeIsAtLeastOne() {
        val window = lyricsWindow(lines, currentLineIndex = 3, windowSize = 0)
        assertEquals(1, window.size)
        assertEquals(3, window.first().index)
        assertTrue(window.first().isCurrent)
    }

    // ── lyricRowDistance: karaoke fade distance ──────────────────────────

    @Test
    fun rowDistanceIsZeroForTheActiveLine() {
        assertEquals(0, lyricRowDistance(index = 10, currentLineIndex = 10))
    }

    @Test
    fun rowDistanceGrowsWithDistanceInEitherDirection() {
        assertEquals(1, lyricRowDistance(index = 9, currentLineIndex = 10))
        assertEquals(1, lyricRowDistance(index = 11, currentLineIndex = 10))
        assertEquals(3, lyricRowDistance(index = 7, currentLineIndex = 10))
    }

    @Test
    fun rowDistanceIsMinusOneWhenNothingIsHighlighted() {
        // Plain / unsynced lyrics pass -1, so every row fades uniformly.
        assertEquals(-1, lyricRowDistance(index = 4, currentLineIndex = -1))
    }

    // ── plainLyricsLines: plain-text fallback ────────────────────────────

    @Test
    fun plainLyricsSplitsTrimsAndDropsBlankLines() {
        val raw = "  First line \n\nSecond line\n   \n\tThird line\t\n"
        assertEquals(listOf("First line", "Second line", "Third line"), plainLyricsLines(raw))
    }

    @Test
    fun plainLyricsIsEmptyForNull() {
        assertTrue(plainLyricsLines(null).isEmpty())
    }

    @Test
    fun plainLyricsIsEmptyForBlankText() {
        assertTrue(plainLyricsLines("   \n\n\t").isEmpty())
    }

    // ── widgetLyricsLines: synced wins, plain falls back ─────────────────

    @Test
    fun syncedLinesWinOverRawText() {
        val synced = listOf("synced one", "synced two")
        assertEquals(synced, widgetLyricsLines(synced, rawLyrics = "raw one\nraw two"))
    }

    @Test
    fun plainFallbackIsUsedWhenThereAreNoSyncedLines() {
        val raw = "raw one\n\nraw two"
        assertEquals(listOf("raw one", "raw two"), widgetLyricsLines(emptyList(), raw))
    }

    @Test
    fun bothEmptyYieldNoLines() {
        assertTrue(widgetLyricsLines(emptyList(), null).isEmpty())
    }
}
