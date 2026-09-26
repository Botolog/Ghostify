package xyz.botolog.ghostify.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        lyricsAutoScrollTarget(
            currentIndex = currentLineIndex(lines, positionMs),
            lineCount = lines.size,
            viewportMeasured = true,
        )

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
            lyricsAutoScrollTarget(
                currentIndex = currentLineIndex(late, 89_999L),
                lineCount = late.size,
                viewportMeasured = true,
            ),
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
        assertNull(
            lyricsAutoScrollTarget(
                currentIndex = currentLineIndex(emptyList(), 0L),
                lineCount = 0,
                viewportMeasured = true,
            ),
        )
        assertNull(lyricsAutoScrollTarget(currentIndex = -1, lineCount = 0, viewportMeasured = true))
    }

    @Test
    fun outOfRangeLineIndexNeverScrolls() {
        assertNull(lyricsAutoScrollTarget(currentIndex = 3, lineCount = 3, viewportMeasured = true))
        assertNull(lyricsAutoScrollTarget(currentIndex = 9, lineCount = 3, viewportMeasured = true))
    }

    @Test
    fun anUnmeasuredViewportIsNeverScrolled() {
        listOf(-1, 0, 1, 2, 9).forEach { currentIndex ->
            assertNull(
                "current index $currentIndex",
                lyricsAutoScrollTarget(
                    currentIndex = currentIndex,
                    lineCount = lines.size,
                    viewportMeasured = false,
                ),
            )
        }
    }

    @Test
    fun theDestinationWalksWithTheActiveLine() {
        val destinations = listOf(0L, 4_999L, 5_000L, 9_999L, 10_000L, 14_999L, 15_000L)
            .map { requireNotNull(targetFor(it)) }

        assertEquals(listOf(0, 0, 0, 0, 1, 1, 2), destinations.map { it.index })
        assertEquals(
            listOf(0, 0, -200, -200, -200, -200, -200),
            destinations.map { it.offsetPx },
        )
    }

    @Test
    fun everyActiveLineHasOneUnchangingDestination() {
        val positions = listOf(0L, 2_500L, 4_999L, 5_000L, 7_500L, 9_999L, 10_000L, 12_500L, 14_999L, 15_000L)
        val perLine = positions
            .map { currentLineIndex(lines, it) to requireNotNull(targetFor(it)) }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        perLine.forEach { (activeIndex, targets) ->
            assertEquals("line $activeIndex moved its destination", 1, targets.distinct().size)
            // A line that has not started yet still rests on the first one.
            assertEquals(maxOf(activeIndex, 0), targets.first().index)
        }
        assertEquals(
            "every line but the resting top one lands at the active edge",
            listOf(-200, 0),
            perLine.mapValues { it.value.first().offsetPx }.values.distinct().sorted(),
        )
    }

    @Test
    fun aLineAlreadyOnItsMeasuredPlaceNeedsNoCorrectiveScroll() {
        val target = LyricsScrollTarget(index = 2, offsetPx = -200)

        assertEquals(
            0,
            requireNotNull(
                lyricsScrollDeltaPx(
                    target,
                    listOf(LyricsMeasuredLine(index = 2, offsetInViewportPx = 200, heightPx = 60)),
                ),
            ),
        )
    }

    @Test
    fun aMeasuredLineIsMovedTheRestOfTheDistanceOnly() {
        val measured = listOf(
            LyricsMeasuredLine(index = 2, offsetInViewportPx = 0, heightPx = 100),
            LyricsMeasuredLine(index = 3, offsetInViewportPx = 100, heightPx = 100),
        )

        // The active line rests 200px below the top edge, so a line 100px down still has to go up.
        assertEquals(
            -100,
            requireNotNull(lyricsScrollDeltaPx(LyricsScrollTarget(index = 3, offsetPx = -200), measured)),
        )
        assertEquals(
            0,
            requireNotNull(lyricsScrollDeltaPx(LyricsScrollTarget(index = 2, offsetPx = 0), measured)),
        )
        assertEquals(
            200,
            requireNotNull(lyricsScrollDeltaPx(LyricsScrollTarget(index = 4, offsetPx = 0), measured)),
        )
    }

    @Test
    fun aLineTheListHasNotPlacedIsAimedAtFromTheMeasuredLineHeights() {
        val measured = listOf(
            LyricsMeasuredLine(index = 4, offsetInViewportPx = 0, heightPx = 100),
            LyricsMeasuredLine(index = 5, offsetInViewportPx = 100, heightPx = 100),
            LyricsMeasuredLine(index = 6, offsetInViewportPx = 200, heightPx = 100),
        )

        assertEquals(300, requireNotNull(lyricsLineOffsetInViewportPx(7, measured)))
        assertEquals(400, requireNotNull(lyricsLineOffsetInViewportPx(8, measured)))
        assertEquals(-100, requireNotNull(lyricsLineOffsetInViewportPx(3, measured)))
        assertEquals(200, requireNotNull(lyricsLineOffsetInViewportPx(6, measured)))
    }

    @Test
    fun aListThatHasMeasuredNothingIsNeverAimedAt() {
        assertNull(lyricsScrollDeltaPx(LyricsScrollTarget(index = 0, offsetPx = 0), emptyList()))
        assertNull(
            lyricsScrollDeltaPx(
                LyricsScrollTarget(index = 3, offsetPx = -200),
                listOf(LyricsMeasuredLine(index = 2, offsetInViewportPx = 0, heightPx = 0)),
            ),
        )
    }

    @Test
    fun theAutoscrollLandsOnTheMeasuredTargetWhenALineMovesUnderneathIt() {
        val list = FakeLyricsList(viewportHeightPx = 800, lineHeights = List(12) { 100 })
        val target = LyricsScrollTarget(index = 3, offsetPx = -200)

        // The line above the target grows while the list is scrolling, as the line that just lost
        // the active emphasis does, which moves the target's measured destination.
        val aims = settleOn(list, target) { aim -> aim == 1 }
        val measured = list.measuredLines()

        assertEquals(0, requireNotNull(lyricsScrollDeltaPx(target, measured)))
        assertEquals(200, measured.first { it.index == 3 }.offsetInViewportPx)
        assertEquals("the line that moved underneath cost one more move, not a snap", 2, aims)
    }

    @Test
    fun aLineThatMovedUnderneathTheScrollWouldHaveNeededASnap() {
        val target = LyricsScrollTarget(index = 3, offsetPx = -200)
        val list = FakeLyricsList(viewportHeightPx = 800, lineHeights = List(12) { 100 })

        settleOn(list, target, aimLimit = 1) { aim -> aim == 1 }

        assertEquals(
            "the one-shot move used to land off target and snap the rest",
            12,
            requireNotNull(lyricsScrollDeltaPx(target, list.measuredLines())),
        )
    }

    @Test
    fun theAutoscrollStaysWithinItsAimBudget() {
        val list = FakeLyricsList(viewportHeightPx = 400, lineHeights = List(40) { 100 })
        val target = LyricsScrollTarget(index = 30, offsetPx = -200)

        val aims = settleOn(list, target) { aim -> aim < 4 }

        assertTrue("aimed $aims times", aims <= LYRICS_SCROLL_MAX_AIMS)
        assertEquals(0, requireNotNull(lyricsScrollDeltaPx(target, list.measuredLines())))
    }

    @Test
    fun everyDistanceGetsABoundedAnimation() {
        var distance = 0
        var previous = 0

        while (distance <= 6_000) {
            val duration = lyricsScrollDurationMs(distance)

            assertEquals("distance $distance", lyricsScrollDurationMs(-distance), duration)
            assertTrue("distance $distance ran for $duration", duration in 90..420)
            if (distance > 0) assertTrue("distance $distance got shorter", duration >= previous)
            previous = duration
            distance += 37
        }
    }

    @Test
    fun aMiniBoxRestsAtItsTopBeforeTheFirstTimestampAndOnlyOnce() {
        val targets = listOf(0L, 1L, 2_500L, 4_999L)
            .map {
                miniLyricsAutoScrollTarget(
                    currentIndex = currentLineIndex(lines, it),
                    lineCount = lines.size,
                    viewportMeasured = true,
                )
            }

        assertEquals(1, targets.distinct().size)
        assertEquals(LyricsScrollTarget(index = 0, offsetPx = 0), targets.first())
        assertEquals(
            0,
            requireNotNull(
                lyricsScrollDeltaPx(
                    requireNotNull(targets.first()),
                    listOf(LyricsMeasuredLine(index = 0, offsetInViewportPx = 0, heightPx = 40)),
                ),
            ),
        )
    }

    @Test
    fun aMiniBoxKeepsTheActiveLineNearTheTopOfItsBox() {
        assertEquals(
            LyricsScrollTarget(index = 1, offsetPx = -100),
            miniLyricsAutoScrollTarget(
                currentIndex = currentLineIndex(lines, 10_000L),
                lineCount = lines.size,
                viewportMeasured = true,
            ),
        )
    }

    @Test
    fun aMiniBoxWaitsForItsBoxToBeMeasured() {
        assertNull(
            miniLyricsAutoScrollTarget(currentIndex = 1, lineCount = lines.size, viewportMeasured = false),
        )
    }

    /**
     * A stand-in for the lyrics list: lines of a known height in a content strip, scrolled inside
     * a viewport, and re-measured when a line grows underneath the autoscroll.
     */
    private class FakeLyricsList(
        private val viewportHeightPx: Int,
        lineHeights: List<Int>,
    ) {
        var growsNextLine: Boolean = false

        private val lineHeightPx = lineHeights.toMutableList()
        private val lineStartPx = MutableList(lineHeights.size) { index ->
            lineHeights.take(index).sum()
        }

        var scrollPx: Int = 0
            private set

        fun measuredLines(): List<LyricsMeasuredLine> =
            lineStartPx.indices
                .filter { index ->
                    val start = lineStartPx[index]
                    start + lineHeightPx[index] > scrollPx && start < scrollPx + viewportHeightPx
                }
                .map { index -> LyricsMeasuredLine(index, lineStartPx[index] - scrollPx, lineHeightPx[index]) }

        fun scrollBy(deltaPx: Int) {
            scrollPx = (scrollPx + deltaPx).coerceIn(0, lastLineEndPx() - viewportHeightPx)
            if (!growsNextLine) return
            lineHeightPx[1] += 12
            lineStartPx.indices
                .filter { index -> index > 1 }
                .forEach { index -> lineStartPx[index] += 12 }
            growsNextLine = false
        }

        private fun lastLineEndPx(): Int = lineStartPx.last() + lineHeightPx.last()
    }

    /**
     * Aims [list] at [target] the way the autoscroll does: measure the line, move the measured
     * distance, and aim again — with a line above the target growing while a move is still running.
     */
    private fun settleOn(
        list: FakeLyricsList,
        target: LyricsScrollTarget,
        aimLimit: Int = Int.MAX_VALUE,
        growsOnAim: (Int) -> Boolean = { false },
    ): Int {
        var aims = 0

        while (aims < aimLimit) {
            val deltaPx = requireNotNull(lyricsScrollDeltaPx(target, list.measuredLines()))
            if (deltaPx == 0) break
            aims++
            list.growsNextLine = growsOnAim(aims)
            list.scrollBy(deltaPx)
        }
        return aims
    }
}
