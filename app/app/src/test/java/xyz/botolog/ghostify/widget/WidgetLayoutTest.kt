package xyz.botolog.ghostify.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure, size-driven responsive layout logic ([playerLayout],
 * [lyricsLayout], [lyricLinesForHeight]). No Glance/Android types are involved, so the
 * breakpoints the widgets rely on are pinned down here rather than only being eyeballed.
 */
class WidgetLayoutTest {

    // ── Player: breakpoint selection across the size matrix ──────────────

    @Test
    fun playerTwoByOneIsShortWithoutSkipButtonsAndWithoutText() {
        val layout = playerLayout(110, 60)
        assertEquals(PlayerLayoutKind.SHORT, layout.kind)
        assertFalse(layout.showProgress)
        assertFalse(layout.showTimes)
        // 110dp is too narrow for prev/next beside the play button.
        assertFalse(layout.showSkip)
        // ...and too narrow for any legible text next to the cover.
        assertFalse("2x1 drops the text column", layout.showText)
    }

    @Test
    fun playerFourByOneIsShortWithTextAndSkipButtons() {
        val layout = playerLayout(250, 60)
        assertEquals(PlayerLayoutKind.SHORT, layout.kind)
        assertFalse("short row has no progress bar", layout.showProgress)
        assertTrue(layout.showSkip)
        assertTrue(layout.showText)
        assertTrue(layout.showArtist)
    }

    @Test
    fun playerFourByTwoIsTheStandardDesign() {
        val layout = playerLayout(250, 110)
        assertEquals(PlayerLayoutKind.STANDARD, layout.kind)
        assertTrue(layout.showProgress)
        assertTrue(layout.showTimes)
        assertTrue(layout.showSkip)
        assertFalse("4x2 stays compact: no album line", layout.showAlbum)
    }

    @Test
    fun playerSixByTwoIsWide() {
        val layout = playerLayout(400, 110)
        assertEquals(PlayerLayoutKind.WIDE, layout.kind)
        assertTrue(layout.showProgress)
        assertTrue("wide cell spreads elapsed/duration", layout.spreadTimes)
        // A 6x2 cell is exactly as tall as a 4x2 one: the album must NOT be stacked as a
        // third line (that clipped the transport), it rides on the artist line instead.
        assertFalse("wide cell does not stack a third text line", layout.showAlbum)
        assertEquals(
            "Neon Tides · Static Bloom",
            playerSecondaryLine("Neon Tides", "Static Bloom"),
        )
    }

    @Test
    fun playerFourByThreeAndFourByFourAreTall() {
        assertEquals(PlayerLayoutKind.TALL, playerLayout(250, 180).kind)
        assertEquals(PlayerLayoutKind.TALL, playerLayout(250, 250).kind)
        val tall = playerLayout(250, 250)
        assertTrue(tall.showProgress)
        assertTrue(tall.showTimes)
        assertTrue(tall.spreadTimes)
        assertTrue(tall.showSkip)
        assertTrue("tall cells show the album once there is room", tall.showAlbum)
        assertFalse("4x3 is too short for the album line", playerLayout(250, 180).showAlbum)
    }

    @Test
    fun shortTransportFitsThePaddedHeight() {
        // The 2x1/4x1 content box is only 28dp tall, so a fixed 30dp play button was
        // sliced flat by the row (it rendered as a 30x27dp ellipse). Nothing — cover or
        // transport — may exceed the padded height.
        for (h in listOf(44, 52, 60, 80, 87)) {
            val layout = playerLayout(250, h)
            if (layout.kind != PlayerLayoutKind.SHORT) continue
            val tallest = maxOf(layout.coverDp, layout.playDp, layout.skipDp)
            assertTrue("short row overflows at h=$h ($tallest > ${h - 16})", tallest <= h - 16)
        }
    }

    @Test
    fun wideContentFitsInsideTheCell() {
        // 6x2 is no taller than 4x2, so the text column must hold title + artist·album +
        // progress + gap + transport inside the padded height. Before the fix a stacked
        // album line made it ~10dp too tall and the transport was clipped at the bottom.
        for (h in listOf(90, 93, 110)) {
            val layout = playerLayout(400, h)
            if (layout.kind != PlayerLayoutKind.WIDE) continue
            val textBlock = 17 + 14 // title line + secondary line
            val needed = textBlock + 3 + 4 + layout.playDp
            assertTrue("wide layout overflows at h=$h (needed $needed)", needed <= h - 16)
        }
    }

    @Test
    fun tallContentFitsInsideTheCell() {
        // cover + 10dp gap + text block + progress + 6dp gap + transport must stay
        // inside the padded height, else the transport row clips (seen at 4x3).
        for (h in listOf(150, 180, 200, 250, 320)) {
            val layout = playerLayout(250, h)
            if (layout.kind != PlayerLayoutKind.TALL) continue
            val textBlock = if (layout.showAlbum) 58 else 38
            val needed = layout.coverDp + 10 + textBlock + 3 + 6 + layout.playDp
            assertTrue("tall layout overflows at h=$h (needed $needed)", needed <= h - 16)
        }
    }

    @Test
    fun tallPlayerCoverAbsorbsTheSpareHeight() {
        // 4x5 used to leave a ~50dp dead band between the album line and the progress bar
        // because the cover was capped at 116dp. The cover must take the leftover height.
        val h = 304
        val layout = playerLayout(234, h)
        assertEquals(PlayerLayoutKind.TALL, layout.kind)
        val textBlock = if (layout.showAlbum) 58 else 38
        val used = layout.coverDp + 10 + textBlock + 3 + 6 + layout.playDp
        val leftover = (h - 16) - used
        assertTrue("4x5 cover too small: leftover $leftover dp", leftover <= 20)
        assertTrue("the cover must not overflow the width", layout.coverDp <= 234 - 16)
    }

    @Test
    fun playerTwoByTwoIsCompact() {
        val layout = playerLayout(110, 110)
        assertEquals(PlayerLayoutKind.COMPACT, layout.kind)
        assertFalse(layout.showProgress)
        assertFalse(layout.showSkip)
        assertTrue(layout.showText)
    }

    @Test
    fun compactContentFitsInsideTheCell() {
        // cover + 3dp + title(≈22dp box) + 5dp + play must fit the padded height, else the
        // play button clips at the bottom edge (seen at 2x2 before the fit-based sizing).
        for (h in listOf(96, 110, 130)) {
            val layout = playerLayout(110, h)
            if (layout.kind != PlayerLayoutKind.COMPACT) continue
            val needed = layout.coverDp + 3 + 22 + 5 + layout.playDp
            assertTrue("compact layout overflows at 110x$h (needed $needed)", needed <= h - 16)
        }
    }

    @Test
    fun shortDropsTheArtistLineWhenTheRowIsTooShort() {
        // A 4x1 cell whose padded content is ~36dp tall cannot stack a title above an
        // artist line without clipping the artist's descenders — drop the artist.
        val clipped = playerLayout(250, 52)
        assertEquals(PlayerLayoutKind.SHORT, clipped.kind)
        assertTrue("the title still fits at 52dp", clipped.showText)
        assertFalse("the artist is dropped at 52dp", clipped.showArtist)

        // Once there is room for both lines, the artist returns.
        assertTrue("the artist returns at 60dp", playerLayout(250, 60).showArtist)
    }

    @Test
    fun shortDropsAllTextOnlyWhenThereIsNoVerticalRoom() {
        val tiny = playerLayout(250, 30)
        assertEquals(PlayerLayoutKind.SHORT, tiny.kind)
        assertFalse("no room for even the title at 30dp", tiny.showText)
    }

    @Test
    fun compactKeepsThePlayButtonInsideJustBelowTheStandardBreakpoint() {
        val layout = playerLayout(160, 88)
        assertEquals(PlayerLayoutKind.COMPACT, layout.kind)
        val needed = layout.coverDp + 3 + 22 + 5 + layout.playDp
        assertTrue("compact layout overflows at 160x88 (needed $needed)", needed <= 88 - 16)
    }

    @Test
    fun playerThreeByTwoIsCompactRatherThanClippingTheStandardTransport() {
        // 3x2 ≈ 164x94dp: just above the old `w < 160 || h < 90` gates, it fell into
        // STANDARD and its painted transport (~150dp) overflowed the ~86dp text column,
        // slicing the play button in half at the right edge.
        val layout = playerLayout(164, 94)
        assertEquals(PlayerLayoutKind.COMPACT, layout.kind)
        val needed = layout.coverDp + 3 + 22 + 5 + layout.playDp
        assertTrue("compact layout overflows at 164x94 (needed $needed)", needed <= 94 - 16)
        // The 4x2 reference and the wider standard cells keep their design.
        assertEquals(PlayerLayoutKind.STANDARD, playerLayout(234, 94).kind)
        assertEquals(PlayerLayoutKind.STANDARD, playerLayout(304, 94).kind)
    }

    @Test
    fun shortAlwaysWinsOverWide() {
        // A short-but-wide cell (e.g. 6x1) must still be a single row.
        assertEquals(PlayerLayoutKind.SHORT, playerLayout(400, 70).kind)
    }

    @Test
    fun tallAlwaysWinsOverWide() {
        assertEquals(PlayerLayoutKind.TALL, playerLayout(400, 250).kind)
    }

    @Test
    fun playerMetricsStayInsideTheCell() {
        for (w in listOf(110, 180, 250, 320, 400)) {
            for (h in listOf(60, 110, 180, 250, 320)) {
                val layout = playerLayout(w, h)
                assertTrue("cover must be positive at ${w}x$h", layout.coverDp > 0)
                assertTrue("cover must fit the height at ${w}x$h", layout.coverDp <= h)
                assertTrue("play button must fit the height at ${w}x$h", layout.playDp <= h)
                assertTrue("title must be positive at ${w}x$h", layout.titleSp > 0)
            }
        }
    }

    @Test
    fun playerLayoutIsStableForDegenerateSizes() {
        // Zero/negative sizes must not crash or yield negative metrics.
        val layout = playerLayout(0, 0)
        assertTrue(layout.coverDp > 0)
        assertTrue(layout.playDp > 0)
    }

    @Test
    fun lyricsWideShortPutsTheHeaderBesideTheLyrics() {
        // 6x2 has spare width but no spare height: stacking the header on top would leave
        // the right half empty, so the header moves beside the lyric block.
        val layout = lyricsLayout(370, 93)
        assertTrue("6x2 keeps the header", layout.showHeader)
        assertTrue("6x2 puts the header beside the lyrics", layout.spreadHeader)
        assertTrue("the header is width-bounded", layout.headerWidthDp in 112..160)
        assertTrue("the header leaves the lyrics the majority", layout.headerWidthDp < 370 / 2)
        assertTrue("more than one line fits once the header is beside", layout.windowSize >= 2)
        assertFalse(layout.showTransport)
        assertFalse(layout.showProgress)
    }

    @Test
    fun lyricsSpreadHeaderNeedsBothWidthAndShortness() {
        // Wide but tall enough for the transport: keep the stacked header.
        assertFalse("6x3 stays stacked", lyricsLayout(370, 164).spreadHeader)
        // Short but not wide: keep the stacked header.
        assertFalse("4x2 stays stacked", lyricsLayout(234, 93).spreadHeader)
        // Header dropped entirely when very short.
        assertFalse(lyricsLayout(370, 60).spreadHeader)
        assertFalse(lyricsLayout(370, 60).showHeader)
    }

    @Test
    fun lyricsSpreadHeaderStillRespectsTheColumnCap() {
        for (w in listOf(320, 340, 370, 400, 500)) {
            for (h in listOf(88, 93, 110, 140, 149)) {
                val size = lyricsLayout(w, h).windowSize
                assertTrue("window >= 1 at ${w}x$h", size >= 1)
                assertTrue("window <= cap at ${w}x$h", size <= WIDGET_COLUMN_MAX_CHILDREN)
            }
        }
    }

    // ── Lyrics: line count from height + graceful degradation ────────────

    @Test
    fun lyricsFourByFourKeepsTheReferenceLook() {
        // The host reports the *content box* after its own gutter: a 4x4 cell ≈ 234x234dp.
        val layout = lyricsLayout(234, 234)
        assertEquals("4x4 shows five lines", 5, layout.windowSize)
        assertTrue(layout.showHeader)
        assertTrue(layout.showProgress)
        assertTrue(layout.showTransport)
    }

    @Test
    fun lyricsFourByFiveShowsMoreLines() {
        // 4x5 ≈ 234x304dp: eight rows (the cap) fit under the same chrome.
        assertEquals(8, lyricsLayout(234, 304).windowSize)
        assertTrue(lyricsLayout(234, 304).windowSize > lyricsLayout(234, 234).windowSize)
    }

    @Test
    fun lyricsFourByThreeShowsFewerLines() {
        val layout = lyricsLayout(234, 164)
        assertTrue(layout.showHeader)
        assertTrue(layout.showTransport)
        assertTrue("progress follows the transport row", layout.showProgress)
        assertEquals(2, layout.windowSize)
    }

    @Test
    fun lyricsThreeByThreeMatchesFourByThree() {
        assertEquals(lyricsLayout(234, 164).windowSize, lyricsLayout(164, 164).windowSize)
    }

    @Test
    fun lyricsFourByTwoKeepsHeaderButDropsTransport() {
        val layout = lyricsLayout(234, 93)
        assertTrue(layout.showHeader)
        assertFalse(layout.showTransport)
        assertFalse(layout.showProgress)
        // Only the highlighted current line fits — better one correct line than two clipped.
        assertEquals(1, layout.windowSize)
    }

    @Test
    fun lyricsChromeDegradesTopDown() {
        val tall = lyricsLayout(250, 250)
        val mid = lyricsLayout(250, 180)
        val short = lyricsLayout(250, 110)
        val tiny = lyricsLayout(250, 80)
        assertTrue(tall.showHeader && tall.showTransport && tall.showProgress)
        assertTrue(mid.showHeader && mid.showTransport)
        assertTrue("mid height keeps the progress bar with its transport", mid.showProgress)
        assertTrue(short.showHeader)
        assertFalse(short.showTransport)
        assertFalse(short.showProgress)
        assertFalse("header is dropped when very short", tiny.showHeader)
        assertFalse(tiny.showTransport)
    }

    @Test
    fun lyricsProgressAppearsExactlyWhenTransportDoes() {
        // The slim progress bar must never out-live the transport row: a widget that shows
        // transport but no progress (or vice-versa) reads lopsided.
        for (h in listOf(60, 90, 104, 110, 140, 149, 150, 180, 250, 320, 500)) {
            val layout = lyricsLayout(250, h)
            assertEquals(
                "progress and transport must agree at h=$h",
                layout.showTransport,
                layout.showProgress,
            )
        }
    }

    @Test
    fun lyricLinesForFreeHeightUsesTheRowHeight() {
        val row = LYRICS_ROW_HEIGHT_DP
        val extra = LYRICS_CURRENT_ROW_EXTRA_DP
        assertEquals(1, lyricLinesForFreeHeight(row + extra - 1))
        assertEquals(1, lyricLinesForFreeHeight(row + extra))
        assertEquals(2, lyricLinesForFreeHeight(row * 2 + extra))
        assertEquals(5, lyricLinesForFreeHeight(row * 5 + extra))
        assertEquals(8, lyricLinesForFreeHeight(row * 8 + extra))
        // Beyond the tall-window threshold the cap grows to the RemoteViews child cap.
        assertEquals(10, lyricLinesForFreeHeight(row * 8 + extra + 100))
    }

    @Test
    fun lyricLinesForFreeHeightPacksTheSlotTightly() {
        // The count must be a genuine fit, not a conservative guess: one dp short of the
        // next row keeps the smaller count, one dp more grants it. A slack of ~6dp per
        // row (the old 27dp pitch) is what left the dead band under the lyrics.
        for (n in 1 until LYRICS_MAX_WINDOW_SIZE) {
            val exact = n * LYRICS_ROW_HEIGHT_DP + LYRICS_CURRENT_ROW_EXTRA_DP
            assertEquals(n, lyricLinesForFreeHeight(exact))
            assertEquals(n, lyricLinesForFreeHeight(exact + LYRICS_ROW_HEIGHT_DP - 1))
            assertEquals(n + 1, lyricLinesForFreeHeight(exact + LYRICS_ROW_HEIGHT_DP))
        }
    }

    @Test
    fun lyricsWindowNeverExceedsTheColumnChildCap() {
        for (w in listOf(110, 180, 250, 320, 400)) {
            for (h in listOf(40, 60, 90, 110, 150, 180, 250, 320, 500)) {
                val size = lyricsLayout(w, h).windowSize
                assertTrue("window must be >= 1 at ${w}x$h", size >= 1)
                assertTrue(
                    "window must be <= $WIDGET_COLUMN_MAX_CHILDREN at ${w}x$h",
                    size <= WIDGET_COLUMN_MAX_CHILDREN,
                )
            }
        }
    }

    @Test
    fun lyricLinesForFreeHeightIsMonotonicAndBounded() {
        var previous = 0
        for (free in 0..400 step 5) {
            val lines = lyricLinesForFreeHeight(free)
            assertTrue(lines >= 1)
            assertTrue(lines <= WIDGET_COLUMN_MAX_CHILDREN)
            assertTrue("line count must not decrease as space grows", lines >= previous)
            previous = lines
        }
    }

    @Test
    fun lyricLinesForHeightAccountsForChrome() {
        // Same height, more chrome => fewer lines.
        val withChrome = lyricLinesForHeight(250, showHeader = true, showProgress = true, showTransport = true)
        val withoutChrome = lyricLinesForHeight(250, showHeader = false, showProgress = false, showTransport = false)
        assertTrue(withoutChrome > withChrome)
    }

    @Test
    fun narrowLyricsShrinkHeaderAndText() {
        val narrow = lyricsLayout(110, 250)
        val wide = lyricsLayout(250, 250)
        assertTrue(narrow.headerCoverDp < wide.headerCoverDp)
        assertTrue(narrow.titleSp < wide.titleSp)
    }

    @Test
    fun narrowCellsFallBackToAPlayOnlyTransport() {
        // 2x3/2x4 ≈ 94dp wide: the trio (30+34+30 + 2x10 gaps ≈ 114dp) exceeds the ~77dp
        // inner width and sliced both skip glyphs at the widget edges. Those cells paint
        // the play/pause pill alone; the trio fits everywhere else with the transport.
        assertFalse(lyricsLayout(94, 164).showSkip)
        assertFalse(lyricsLayout(94, 234).showSkip)
        assertTrue("3x3 keeps the full trio", lyricsLayout(164, 164).showSkip)
        assertTrue("4x4 keeps the full trio", lyricsLayout(234, 234).showSkip)
        for (w in listOf(94, 110, 234, 370)) {
            for (h in listOf(164, 234, 373, 500)) {
                val layout = lyricsLayout(w, h)
                if (!layout.showTransport) continue
                val needed = if (layout.showSkip) {
                    layout.skipDp * 2 + layout.playDp + 2 * LYRICS_TRANSPORT_GAP_DP
                } else {
                    layout.playDp
                }
                assertTrue(
                    "transport overflows at ${w}x$h (needed $needed)",
                    needed <= w - 16,
                )
            }
        }
    }

    @Test
    fun aSixBySixCellGrowsTheWindowToTheChildCap() {
        // 6x6 ≈ 369x373dp: the flat 8-row cap left ~53dp dead above and below the lyric
        // block. A genuine tall cell fills to the RemoteViews 10-child cap instead.
        val layout = lyricsLayout(369, 373)
        assertTrue(layout.showTransport)
        assertEquals(WIDGET_COLUMN_MAX_CHILDREN, layout.windowSize)
        // Mid-size cells keep the modest cap that fits their height.
        assertEquals(8, lyricsLayout(234, 304).windowSize)
    }
}
