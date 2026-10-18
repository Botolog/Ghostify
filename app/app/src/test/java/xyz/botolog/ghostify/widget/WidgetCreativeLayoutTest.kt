package xyz.botolog.ghostify.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the three new widgets' pure, size-driven layout logic
 * ([sleepLayout], [nextUpLayout], [nextUpRowsForFreeHeight], [rouletteLayout]).
 *
 * These pin down the two rules the RemoteViews backend enforces — everything fits its cell,
 * and no `Column` gets more than [WIDGET_COLUMN_MAX_CHILDREN] direct children — without an
 * emulator. The visual judgement (legibility, balance) is done from the lab captures.
 */
class WidgetCreativeLayoutTest {

    // ── Sleep timer ──────────────────────────────────────────────────────

    @Test
    fun sleepWideCellUsesTheRingRowAndPresets() {
        // 4x2 content box ≈ 234x94dp.
        val layout = sleepLayout(234, 94)
        assertTrue("wide cells get the ring row", layout.wide)
        assertTrue("wide cells offer three presets", layout.showPresets)
        assertTrue("the ring stays ring-sized", layout.ringDp >= 48)
    }

    @Test
    fun sleepSmallSquareKeepsTheRing() {
        // 2x2 content box ≈ 94x94dp: the caption yields to the ring (which carries the
        // countdown inside it), so the smallest cell is still the widget's instrument rather
        // than a number over a bar.
        val layout = sleepLayout(94, 94)
        assertFalse("a small square is not the wide row", layout.wide)
        assertFalse("a small square offers one start button", layout.showPresets)
        assertTrue("the 2x2 keeps a real ring", layout.activeRing)
        assertTrue(
            "the 2x2 ring stays above the legible floor",
            layout.ringDp >= SLEEP_COMPACT_RING_MIN_DP,
        )
        assertFalse("the caption yields to the ring", layout.activeCaption)
        val needed = layout.ringDp + SLEEP_STACK_GAP_DP + layout.cancelDp
        assertTrue("compact active stack overflows (needed $needed)", needed <= 94 - 16)
    }

    @Test
    fun sleepOnlyADegenerateCellFallsBackToTheBar() {
        // Below the ring floor (a cell far under the 2x2 minimum) the number-over-bar shape is
        // still returned so nothing is ever clipped.
        val tiny = sleepLayout(94, 60)
        assertFalse(tiny.activeRing)
        assertTrue("the fallback number is large", tiny.timeSp >= 22)
    }

    @Test
    fun sleepRingContentsClearTheStroke() {
        // The moon glyph (when present) plus the `mm:ss` must clear the ring stroke in every
        // active shape; a line box is ~1.4x its sp.
        for (w in listOf(94, 234)) {
            for (h in listOf(94, 120, 164, 234, 304)) {
                val layout = sleepLayout(w, h)
                if (!layout.activeRing) continue
                val inner = layout.ringDp - 2 * layout.ringStrokeDp
                val text = layout.ringTimeSp * 14 / 10
                val needed = if (layout.ringGlyphDp > 0) layout.ringGlyphDp + 2 + text else text
                assertTrue(
                    "ring contents touch the stroke at ${w}x$h (inner=$inner needed=$needed)",
                    needed <= inner,
                )
            }
        }
    }

    @Test
    fun sleepRingNeverOverflowsTheCellHeight() {
        for (h in listOf(60, 88, 94, 120, 164, 234, 304)) {
            val layout = sleepLayout(250, h)
            assertTrue("ring $h", layout.ringDp <= h)
        }
    }

    @Test
    fun sleepTallCellStacksTheRingAboveCancel() {
        // 4x3 content box ≈ 233x163dp: not wide-and-short, so the ring stacks under the
        // caption with Cancel beneath it.
        val layout = sleepLayout(233, 163)
        assertFalse("4x3 is not the wide row", layout.wide)
        assertTrue("4x3 still draws the real ring", layout.activeRing)
        assertTrue(layout.showLabel)
        val needed = SLEEP_CAPTION_DP + SLEEP_STACK_GAP_DP + layout.ringDp +
            SLEEP_STACK_GAP_DP + layout.cancelDp
        assertTrue("4x3 active stack overflows (needed $needed)", needed <= 163 - 16)
    }

    @Test
    fun sleepStackedRingAlwaysFitsTheCell() {
        for (h in listOf(120, 140, 164, 200, 234, 304)) {
            val layout = sleepLayout(234, h)
            if (layout.wide || !layout.activeRing) continue
            val caption = if (layout.showLabel) SLEEP_CAPTION_DP + SLEEP_STACK_GAP_DP else 0
            val needed = caption + layout.ringDp + SLEEP_STACK_GAP_DP + layout.cancelDp
            assertTrue("stacked ring overflows at h=$h (needed $needed)", needed <= h - 16)
        }
    }

    @Test
    fun sleepIdleStackFitsTheCell() {
        // The idle shape shares the same cell: caption + one preset row (or single pill).
        for (h in listOf(88, 94, 120, 163, 234, 304)) {
            val layout = sleepLayout(234, h)
            val caption = if (layout.showLabel) SLEEP_CAPTION_DP + SLEEP_STACK_GAP_DP else 0
            val needed = caption + layout.buttonDp
            assertTrue("idle stack overflows at h=$h (needed $needed)", needed <= h - 16)
        }
    }

    @Test
    fun sleepPresetsFitTheCellWidth() {
        for (w in listOf(170, 180, 234, 320, 370)) {
            val layout = sleepLayout(w, 110)
            if (!layout.showPresets) continue
            val needed = layout.buttonDp * 3 + 16 // two 8dp gaps
            assertTrue("presets overflow at w=$w (needed $needed)", needed <= w)
        }
    }

    @Test
    fun sleepLayoutIsStableForDegenerateSizes() {
        val layout = sleepLayout(0, 0)
        assertTrue(layout.ringDp > 0)
        assertTrue(layout.buttonDp > 0)
        assertTrue(layout.cancelDp > 0)
        assertTrue(layout.timeSp > 0)
    }

    @Test
    fun sleepTallIdleStacksPillsInsteadOfFloatingCircles() {
        // 4x4 ≈ 234x234dp: a circle row would float in empty space, so the presets stack.
        val tall = sleepLayout(234, 234)
        assertTrue("a 4x4 idle cell stacks its presets", tall.stackPresets)
        val needed = SLEEP_CAPTION_DP + SLEEP_STACK_GAP_DP + tall.buttonDp * 3 + 6 * 2
        assertTrue("stacked presets overflow (needed $needed)", needed <= 234 - 16)

        val short = sleepLayout(234, 94)
        assertFalse("a 4x2 idle cell keeps the circle row", short.stackPresets)
        assertTrue("a 4x2 idle cell still offers presets", short.showPresets)
    }

    @Test
    fun sleepPresetRowLeavesAGutter() {
        // The three circles plus their gaps must sit inside the padded cell, otherwise the
        // outermost circle is clipped by the widget edge.
        val layout = sleepLayout(234, 164)
        val row = layout.buttonDp * 3 + SLEEP_GAP_PRESETS_DP * 2
        assertTrue("the circle row must not touch the cell edge (row $row)", row <= 234 - 16)
    }

    @Test
    fun sleepCompactCellNeverEllipsisesItsLabels() {
        // 2x2 ≈ 93x93dp: the caption drops its words and Cancel tightens its padding, so
        // neither "Sleep timer" nor "Cancel" ends up truncated.
        val layout = sleepLayout(93, 93)
        assertTrue(layout.showLabel)
        assertFalse("a 2x2 caption keeps only the moon", layout.showLabelText)
        assertFalse(layout.showPresets)
        assertTrue("Cancel padding must shrink on a 2x2 cell", layout.cancelPadDp <= 8)
    }

    @Test
    fun sleepNarrowTallCellFitsTheFullCancelLabel() {
        // 2x3 ≈ 93x164dp: the ring is tall enough to keep the caption and a full-height
        // Cancel, but the cell is only ~93dp wide, so Cancel must tighten or "× Cancel" is
        // clipped to "× Ca…".
        val layout = sleepLayout(93, 164)
        assertFalse("2x3 is not the wide row", layout.wide)
        assertTrue("2x3 still draws the real ring", layout.activeRing)
        assertTrue(layout.showLabel)
        assertTrue("2x3 has the height for a full-height Cancel", layout.cancelDp >= 34)
        val width = sleepCancelWidthDp(layout)
        assertTrue("Cancel pill overflows the 93dp cell (needed ${width}dp)", width <= 93)
    }

    // ── Next up ──────────────────────────────────────────────────────────

    @Test
    fun nextUpShowsFewerRowsAsTheCellShrinks() {
        val tall = nextUpLayout(234, 250, hasMore = false).rows
        val mid = nextUpLayout(234, 164, hasMore = false).rows
        val short = nextUpLayout(234, 94, hasMore = false).rows
        assertTrue("rows must not grow as height falls: $tall/$mid/$short", tall >= mid && mid >= short)
    }

    @Test
    fun nextUpRowsAreAlwaysWithinTheCap() {
        for (w in listOf(110, 180, 250, 320, 400)) {
            for (h in listOf(40, 60, 94, 110, 164, 234, 320, 500)) {
                val rows = nextUpLayout(w, h, hasMore = true).rows
                assertTrue("rows >= 0 at ${w}x$h", rows >= 0)
                assertTrue("rows <= cap at ${w}x$h", rows <= NEXT_UP_MAX_ROWS)
            }
        }
    }

    @Test
    fun nextUpReservesRoomForTheMoreLine() {
        val withMore = nextUpLayout(234, 164, hasMore = true).rows
        val withoutMore = nextUpLayout(234, 164, hasMore = false).rows
        assertTrue("the +N line must never push the count up", withMore <= withoutMore)
    }

    @Test
    fun nextUpOuterColumnStaysUnderTheChildCap() {
        for (w in listOf(110, 234, 400)) {
            for (h in listOf(60, 94, 164, 250, 400)) {
                for (hasMore in listOf(true, false)) {
                    val layout = nextUpLayout(w, h, hasMore = hasMore)
                    // One header, an optional "now playing" line, the rows, an optional "+N".
                    val children = 1 + (if (layout.showNowPlaying) 1 else 0) + layout.rows +
                        (if (layout.showMore) 1 else 0)
                    assertTrue(
                        "next-up children $children > cap at ${w}x$h (more=$hasMore)",
                        children <= WIDGET_COLUMN_MAX_CHILDREN,
                    )
                }
            }
        }
    }

    @Test
    fun nextUpRowsForFreeHeightPacksTightly() {
        val row = NEXT_UP_ROW_HEIGHT_DP
        assertTrue(nextUpRowsForFreeHeight(0) == 0)
        assertTrue(nextUpRowsForFreeHeight(row - 1) == 0)
        assertTrue(nextUpRowsForFreeHeight(row) == 1)
        assertTrue(nextUpRowsForFreeHeight(row * 3) == 3)
        assertTrue(nextUpRowsForFreeHeight(row * 100) == NEXT_UP_MAX_ROWS)
    }

    @Test
    fun nextUpDropsTheMoreLineRatherThanShowingNoRows() {
        // 6x2 ≈ 369x93dp: reserving the "+N more" line would leave zero rows, which paints
        // an almost-empty card, so the line is dropped in favour of a real row.
        val short = nextUpLayout(369, 93, hasMore = true)
        assertFalse("the +N line yields to a row", short.showMore)
        assertTrue("a short cell still shows one upcoming row", short.rows >= 1)

        val tall = nextUpLayout(234, 234, hasMore = true)
        assertTrue("a tall cell keeps both rows and the +N line", tall.showMore && tall.rows >= 2)
    }

    @Test
    fun nextUpWideShortCellUsesDenseRows() {
        // 6x2 ≈ 369x93dp: a two-line row would show one item and leave the lower half empty,
        // so the rows go dense and the header's own "n of m" replaces the "+N more" line.
        val wide = nextUpLayout(369, 93, hasMore = true)
        assertTrue("a wide-short cell goes dense", wide.dense)
        assertTrue("a 6x2 lists two upcoming items", wide.rows >= 2)
        assertFalse("the header already carries the count", wide.showMore)

        val square = nextUpLayout(94, 94, hasMore = true)
        assertFalse("a 2x2 keeps its two-line rows", square.dense)
    }

    @Test
    fun nextUpHeaderlessFloorStillListsAnItem() {
        // 4x1 ≈ 234x44dp (the minResizeHeight floor): no header, so the row must go dense and
        // at least one item must still be painted rather than an empty card.
        val layout = nextUpLayout(234, 44, hasMore = true)
        assertFalse("a 4x1 drops the header", layout.showHeader)
        assertTrue("a 4x1 goes dense", layout.dense)
        assertTrue("a 4x1 still lists an item", layout.rows >= 1)
    }

    @Test
    fun nextUpDenseRowsFitThePaddedCell() {
        for (w in listOf(NEXT_UP_DENSE_MIN_WIDTH, 370, 500)) {
            for (h in listOf(60, 76, 94)) {
                val layout = nextUpLayout(w, h, hasMore = true)
                if (!layout.dense) continue
                var chrome = 16
                if (layout.showHeader) chrome += 20 + 6 + 1 + 6
                if (layout.showNowPlaying) chrome += 10 + 6
                val used = chrome + layout.rows * NEXT_UP_DENSE_ROW_HEIGHT_DP
                assertTrue("dense rows overflow at ${w}x$h (used $used > $h)", used <= h)
            }
        }
    }

    // ── Shuffle roulette ─────────────────────────────────────────────────

    @Test
    fun rouletteSpinAlwaysFitsThePaddedHeight() {
        for (h in listOf(60, 94, 110, 164, 234, 320)) {
            val layout = rouletteLayout(234, h)
            assertTrue("spin $h", layout.spinDp <= h)
            assertTrue("spin positive $h", layout.spinDp >= 34)
        }
    }

    @Test
    fun rouletteAddsChromeAsTheCellGrows() {
        val short = rouletteLayout(234, 94)
        val tall = rouletteLayout(234, 234)
        assertFalse("4x2 drops the header", short.showHeader)
        assertTrue("a tall cell keeps the header", tall.showHeader)
        assertTrue("a tall cell keeps the artist", tall.showArtist)
    }

    @Test
    fun rouletteToggleNeedsWidth() {
        assertTrue(rouletteLayout(234, 110).showShuffleToggle)
        assertFalse("2x2 is too narrow for the toggle", rouletteLayout(94, 94).showShuffleToggle)
    }

    @Test
    fun rouletteTallCellGrowsSpinUntilItFillsTheLowerHalf() {
        val medium = rouletteLayout(234, 164)
        val tall = rouletteLayout(234, 234)
        assertTrue("SPIN never shrinks as the cell grows", tall.spinDp >= medium.spinDp)
        assertTrue("a 4x4 SPIN is substantial", tall.spinDp >= 80)
        assertTrue("SPIN stays inside the padded cell", tall.spinDp <= 234 - 16)
    }

    @Test
    fun rouletteTallCellAddsTheHintSoNoLopsidedBandRemains() {
        // A 4x4 cannot spend all of its spare height on SPIN alone; the hint line takes the
        // bottom band, and the two remaining spaces stay small and symmetric.
        val tall = rouletteLayout(234, 234)
        assertTrue("a 4x4 adds the hint", tall.showHint)
        val used = 18 + 20 + 16 + tall.spinDp + ROULETTE_HINT_BLOCK_DP
        assertTrue("the tall stack leaves a lopsided band (used $used)", used <= 234 - 16)
        val gap = (234 - 16 - used) / 2
        assertTrue("the tall band stays tight (gap $gap)", gap <= 24)

        val short = rouletteLayout(234, 94)
        assertFalse("a 4x2 has no room for the hint", short.showHint)
    }

    @Test
    fun rouletteWideShortCellSpreadsTextBesideSpin() {
        // 6x2 ≈ 369x93dp: too short for a header/artist and far too wide for a centred stack,
        // so the text block and SPIN sit side by side to fill the width.
        val wide = rouletteLayout(369, 93)
        assertFalse("a 6x2 drops the header", wide.showHeader)
        assertTrue("a 6x2 spreads the text beside SPIN", wide.spread)
        assertTrue("SPIN still fits the short cell", wide.spinDp <= 93)
    }

    @Test
    fun rouletteSpreadOnlyOnTheWidestHeaderlessCell() {
        assertFalse("a 4x2 stacks instead of spreading", rouletteLayout(234, 94).spread)
        assertFalse("a 2x2 stacks instead of spreading", rouletteLayout(94, 94).spread)
        // A tall-but-narrow-shaped 6x3 has its own proper header, so it never spreads.
        assertFalse("a headered cell never spreads", rouletteLayout(369, 164).spread)
    }
}
