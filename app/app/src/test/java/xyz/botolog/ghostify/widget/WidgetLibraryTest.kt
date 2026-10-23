package xyz.botolog.ghostify.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.model.LibraryStats

/**
 * JVM tests for the two new widgets' pure logic: the "Playlists" grid
 * ([playlistLayout], [playlistTilesFrom], [coverSampleSize]) and the "Library" stats +
 * sync readout ([statsLayout], [libraryStatRows], [syncLabelKind], [syncAgeParts],
 * [formatStatCount], [ellipsize], [textCharBudget]).
 *
 * These pin down the two rules the RemoteViews backend enforces — everything fits its cell,
 * and no `Column` gets more than [WIDGET_COLUMN_MAX_CHILDREN] direct children — plus the
 * explicit truncation that substitutes for Glance 1.1's missing `TextOverflow`. The visual
 * judgement (legibility, balance) is done from the lab captures.
 */
class WidgetLibraryTest {

    private fun playlist(id: String, name: String, tracks: Int) = PlaylistEntity(
        id = id,
        spotifyId = "sp-$id",
        name = name,
        trackCount = tracks,
    )

    // ── Playlists: grid ──────────────────────────────────────────────────

    @Test
    fun playlistsMediumCellShowsOneRowOfTwoCoverTiles() {
        // 4x2 content box ≈ 234x94dp, the device's 233x93dp: one row of two covered tiles,
        // each with its count line.
        val layout = playlistLayout(234, 94)
        assertTrue("4x2 keeps the header", layout.showHeader)
        assertEquals(2, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(2, layout.tiles)
        assertTrue("4x2 is wide enough for covers", layout.showCover)
        assertTrue(layout.showTileCount)
    }

    @Test
    fun playlistsTallerMediumCellBecomesATwoByTwoGrid() {
        // 4x3 ≈ 234x164dp: the second covered row is affordable there, and no more.
        val layout = playlistLayout(234, 164)
        assertEquals(2, layout.columns)
        assertEquals(PLAYLIST_MAX_ROWS, layout.rows)
        assertEquals(PLAYLIST_TILE_CAP, layout.tiles)
        assertTrue(layout.showCover)
    }

    @Test
    fun playlistsWideCellWidensToFourColumnsInOneRow() {
        // 6x2 ≈ 369x94dp.
        val layout = playlistLayout(369, 94)
        assertEquals(4, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(PLAYLIST_TILE_CAP, layout.tiles)
    }

    @Test
    fun playlistsWideCellFitsAThreeDigitCountBesideTheCover() {
        // 6x2 ≈ 369x94dp: four covered columns, so each text strip is narrow — but a count
        // like "128 tracks" must still fit whole.
        val layout = playlistLayout(369, 94)
        assertEquals(4, layout.columns)
        assertTrue(
            "count budget ${layout.countChars} < \"128 tracks\"",
            layout.countChars >= "128 tracks".length,
        )
    }

    @Test
    fun playlistsSmallSquareDropsTheCoverAndStacksOneColumn() {
        // 2x2 ≈ 94x94dp: no width for a cover beside a legible name.
        val layout = playlistLayout(94, 94)
        assertEquals(1, layout.columns)
        assertFalse("a 2x2 tile spends the width on the name", layout.showCover)
        assertTrue(layout.tiles in 1..PLAYLIST_TILE_CAP)
    }

    @Test
    fun playlistsNarrowTallCellFillsWithTextOnlyRows() {
        // 2x3 ≈ 94x163dp: no cover, so the rows are allowed to fill the cell.
        val layout = playlistLayout(94, 163)
        assertEquals(1, layout.columns)
        assertEquals(3, layout.rows)
        assertEquals(3, layout.tiles)
        assertFalse("a 94dp tile never shows a cover", layout.showCover)
        assertTrue("each row still carries its count line", layout.showTileCount)
    }

    @Test
    fun playlistsTallTextOnlyCellUsesEveryTile() {
        // 2x4 ≈ 94x216dp.
        val layout = playlistLayout(94, 216)
        assertEquals(PLAYLIST_TILE_CAP, layout.tiles)
        assertEquals(PLAYLIST_TILE_CAP, layout.rows)
        assertFalse(layout.showCover)
    }

    @Test
    fun playlistsCoveredRowsStayCappedHoweverTallTheCellIs() {
        // A covered cell grows the tiles instead of adding rows.
        val layout = playlistLayout(234, 500)
        assertEquals(2, layout.rows)
        assertEquals(PLAYLIST_TILE_CAP, layout.tiles)
        assertTrue(layout.showCover)
    }

    @Test
    fun playlistsTallCoveredRowWrapsTheNameOntoTwoLines() {
        // 4x4 ≈ 234x234dp: the cover is large, so the name needs a second line to stay legible
        // ("Night Drive" must not be cut to "Night…").
        val layout = playlistLayout(234, 234)
        assertTrue(layout.showCover)
        assertEquals(2, layout.nameMaxLines)
        assertTrue(
            "the name must hold \"Night Drive\" across its lines: " +
                "${layout.nameChars}x${layout.nameMaxLines}",
            layout.nameChars * layout.nameMaxLines >= "Night Drive".length,
        )
    }

    @Test
    fun playlistsGridNeverExceedsTheCoverCap() {
        for (w in listOf(94, 110, 180, 234, 250, 320, 370, 440, 600)) {
            for (h in listOf(30, 60, 72, 94, 110, 164, 234, 320, 500)) {
                val layout = playlistLayout(w, h)
                assertTrue("columns >= 1 at ${w}x$h", layout.columns >= 1)
                assertTrue("rows >= 0 at ${w}x$h", layout.rows >= 0)
                assertTrue(
                    "covered rows capped at ${w}x$h",
                    !layout.showCover || layout.rows <= PLAYLIST_MAX_ROWS,
                )
                assertTrue(
                    "tiles ${layout.tiles} > cap at ${w}x$h",
                    layout.tiles <= PLAYLIST_TILE_CAP,
                )
                assertTrue("nameChars >= 1 at ${w}x$h", layout.nameChars >= 1)
                assertTrue("countChars >= 1 at ${w}x$h", layout.countChars >= 1)
                assertTrue("cover floor at ${w}x$h", layout.coverDp >= PLAYLIST_MIN_COVER_DP)
                assertTrue("row height >= 0 at ${w}x$h", layout.rowHeightDp >= 0)
            }
        }
    }

    @Test
    fun playlistsNamesReadWholeOnTheCoveredBesideLayouts() {
        // The common fixtures ("Night Drive", 11 chars) must survive: the cover yields to a
        // legible name strip on 4x2, 4x3 and the four-column 6x2.
        for (cell in listOf(234 to 94, 234 to 164, 369 to 94)) {
            val layout = playlistLayout(cell.first, cell.second)
            assertTrue("${cell.first}x${cell.second} shows covers", layout.showCover)
            assertTrue(
                "a covered tile must afford \"Night Drive\" at ${cell.first}x${cell.second}: " +
                    "${layout.nameChars}x${layout.nameMaxLines}",
                layout.nameChars * layout.nameMaxLines >= "Night Drive".length,
            )
        }
    }

    @Test
    fun playlistsTallCoveredCellStacksTheCoverAboveAFullWidthName() {
        // 4x4 ≈ 234x234dp: beside a large cover the strip is a few characters wide, so the
        // tile stacks and the name spans the whole tile width.
        val layout = playlistLayout(234, 234)
        assertTrue(layout.showCover)
        assertTrue("a tall covered row stacks", layout.stacked)
        assertEquals(2, layout.nameMaxLines)
        assertTrue(
            "a stacked name must afford \"Late Night Coding Beats\": ${layout.nameChars * layout.nameMaxLines}",
            layout.nameChars * layout.nameMaxLines >= "Late Night Coding Beats".length,
        )
        // The tile fills its row: cover + the text block is the row height (a couple of dp
        // of slack are absorbed by the tile's vertical centring).
        assertTrue(
            "stacked tile does not fill its row (cover ${layout.coverDp} row ${layout.rowHeightDp})",
            layout.coverDp + 40 <= layout.rowHeightDp,
        )
    }

    @Test
    fun playlistsPinnedRowsFillTheFreeHeight() {
        // rows * rowHeight + (rows - 1) * gap must never exceed the free height, so no band
        // pools above the bottom edge.
        for ((w, h) in listOf(234 to 94, 234 to 164, 234 to 234, 234 to 304, 369 to 94)) {
            val layout = playlistLayout(w, h)
            if (layout.rows == 0) continue
            var chrome = 16
            if (layout.showHeader) chrome += 26
            val used = layout.rows * layout.rowHeightDp + (layout.rows - 1) * layout.tileGapDp
            assertTrue("grid overflows at ${w}x$h (used $used > ${h - chrome})", used <= h - chrome)
            // ... and it fills within a gap: the leftover is under one inter-row gap.
            assertTrue(
                "grid pools a band at ${w}x$h (used $used of ${h - chrome})",
                used + layout.tileGapDp * 2 >= h - chrome,
            )
        }
    }

    @Test
    fun playlistsSingleTileFillsTheCell() {
        // One playlist on a 4x4 ≈ 234x234dp: one column, one row, a large stacked cover — not
        // a lone half-width tile against three empty quadrants.
        val layout = playlistLayout(234, 234, tileCount = 1)
        assertEquals(1, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(1, layout.tiles)
        assertTrue(layout.stacked)
        assertTrue("single 4x4 cover is large: ${layout.coverDp}", layout.coverDp >= 90)

        val wide = playlistLayout(369, 94, tileCount = 1)
        assertEquals(1, wide.columns)
        assertEquals(1, wide.rows)
        assertTrue(
            "a single wide tile affords \"Night Drive\": ${wide.nameChars}",
            wide.nameChars >= "Night Drive".length,
        )
    }

    @Test
    fun playlistsColumnsNeverExceedTheTileCount() {
        // Two playlists on a 6x2 use two generous columns, not two tiles beside two empty ones.
        val two = playlistLayout(369, 94, tileCount = 2)
        assertEquals(2, two.columns)
        assertEquals(2, two.tiles)
        // Three on the same cell fill the row without a padded gap.
        val three = playlistLayout(369, 94, tileCount = 3)
        assertEquals(3, three.columns)
        assertEquals(3, three.tiles)
    }

    @Test
    fun playlistsZeroTilesPaintsNothing() {
        val empty = playlistLayout(234, 234, tileCount = 0)
        assertEquals(0, empty.tiles)
        assertEquals(0, empty.rows)
    }

    @Test
    fun playlistsDropsTheHeaderOnAShortCell() {
        assertFalse(playlistLayout(234, 60).showHeader)
        assertTrue(playlistLayout(234, PLAYLIST_HEADER_MIN_HEIGHT).showHeader)
    }

    @Test
    fun playlistsOneRowCellsKeepATileInsteadOfClaimingThereAreNone() {
        // 2x1/3x1/4x1 ≈ 44dp tall: the free height (28dp) is under the readable row pitch
        // (38dp), which used to collapse the grid to zero tiles and paint a false
        // "No playlists yet" over two real playlists.
        for (w in listOf(93, 164, 234)) {
            val layout = playlistLayout(w, 44, tileCount = 2)
            assertEquals("a 1-row cell keeps one compact tile", 1, layout.rows)
            // The widest cell fits both side by side; the narrow ones keep one tile.
            assertEquals(minOf(2, layout.columns), layout.tiles)
            assertEquals(0, layout.overflowCount)
        }
        // Truly nothing to show still paints the empty message.
        val none = playlistLayout(234, 44, tileCount = 0)
        assertEquals(0, none.rows)
    }

    @Test
    fun playlistsNarrowHeaderCellKeepsAFullHeaderInsteadOfClippingTheCount() {
        // A 2x2 (~93dp) cannot afford "Playlists +99 more" on one header line: the marker
        // used to clip mid-glyph at the squircle edge. Drop the count there; a 3x2 (~164dp)
        // still marks the hidden playlists on its roomy header line.
        val narrow = playlistLayout(93, 94, tileCount = 3)
        assertTrue(narrow.showHeader)
        assertEquals(0, narrow.overflowCount)
        assertEquals("the grid still keeps its tile", 1, narrow.rows)
        assertTrue(playlistLayout(164, 94, tileCount = 3).overflowCount > 0)
    }

    @Test
    fun playlistsHiddenTilesAreMarkedByTheOverflowCount() {
        // three_4x2: 2 columns × 1 row fit 2 of the 3 playlists; the 3rd must be marked,
        // not hidden silently. The count rides the header, so nothing else changes.
        val layout = playlistLayout(234, 94, tileCount = 3)
        assertEquals(2, layout.tiles)
        assertEquals(1, layout.overflowCount)
        // Two playlists fill the grid exactly: no marker.
        assertEquals(0, playlistLayout(234, 94, tileCount = 2).overflowCount)
        // A headerless cell that can afford the line reserves it under the grid.
        val headerless = playlistLayout(234, 70, tileCount = 3)
        assertEquals(1, headerless.rows)
        assertEquals(1, headerless.overflowCount)
        val used = headerless.rows * headerless.rowHeightDp + PLAYLIST_OVERFLOW_TOTAL_DP
        assertTrue("headerless overflow overflows (used $used)", used <= 70 - 16)
    }

    @Test
    fun playlistsOuterColumnStaysUnderTheChildCap() {
        for (w in listOf(94, 234, 370)) {
            for (h in listOf(60, 94, 164)) {
                val layout = playlistLayout(w, h)
                val rows = if (layout.columns > 0) {
                    (layout.tiles + layout.columns - 1) / layout.columns
                } else {
                    0
                }
                val children = (if (layout.showHeader) 1 else 0) + rows
                assertTrue("playlists children $children > cap at ${w}x$h", children <= WIDGET_COLUMN_MAX_CHILDREN)
            }
        }
    }

    @Test
    fun playlistsTileRowStaysUnderTheChildCap() {
        for (w in listOf(94, 234, 370)) {
            val layout = playlistLayout(w, 94)
            if (layout.columns <= 0) continue
            // Worst case: a full row (n tiles, n-1 gaps) or a partial one (padded with
            // per-missing gap + weight).
            for (tilesInRow in 1..layout.columns) {
                val missing = layout.columns - tilesInRow
                val children = tilesInRow * 2 - 1 + missing * 2
                assertTrue(
                    "tile row children $children > cap (w=$w, columns=${layout.columns})",
                    children <= WIDGET_COLUMN_MAX_CHILDREN,
                )
            }
        }
    }

    @Test
    fun playlistsLayoutIsStableForDegenerateSizes() {
        val layout = playlistLayout(0, 0)
        assertTrue(layout.columns >= 1)
        assertTrue(layout.tiles >= 0)
        assertTrue(layout.nameChars >= 1)
        assertTrue(layout.nameSp > 0)
        assertTrue(layout.countSp > 0)
    }

    // ── Playlists: tile mapping ──────────────────────────────────────────

    @Test
    fun playlistTilesKeepOrderAndMapFieldsWithTheCover() {
        val rows = listOf(
            playlist("a", "Alpha", 3),
            playlist("b", "Beta", 0),
        )
        val tiles = playlistTilesFrom(rows) { bytesFor(it.id) }

        assertEquals(listOf("a", "b"), tiles.map { it.id })
        assertEquals(listOf("Alpha", "Beta"), tiles.map { it.name })
        assertEquals(listOf(3, 0), tiles.map { it.trackCount })
        assertEquals("cover-a", tiles[0].coverBytes?.decodeToString())
    }

    @Test
    fun playlistTilesAreCapped() {
        val rows = (0 until 10).map { playlist("p$it", "Playlist $it", it) }
        assertEquals(PLAYLIST_TILE_CAP, playlistTilesFrom(rows).size)
        assertEquals(0, playlistTilesFrom(rows, cap = 0).size)
        assertEquals(2, playlistTilesFrom(rows, cap = 2).size)
    }

    @Test
    fun playlistTilesDefaultToNoCover() {
        val tiles = playlistTilesFrom(listOf(playlist("a", "Alpha", 1)))
        assertNull(tiles.single().coverBytes)
    }

    private fun bytesFor(id: String): ByteArray = "cover-$id".encodeToByteArray()

    // ── Playlists: bounded cover decoding ────────────────────────────────

    @Test
    fun coverSampleSizeHalvesUntilTheTargetFits() {
        assertEquals(1, coverSampleSize(120, 120, WIDGET_TILE_COVER_MAX_PX))
        assertEquals(2, coverSampleSize(300, 300, WIDGET_TILE_COVER_MAX_PX))
        assertEquals(4, coverSampleSize(600, 600, WIDGET_TILE_COVER_MAX_PX))
        // A non-square image is sampled on its longer side.
        assertEquals(4, coverSampleSize(640, 200, WIDGET_TILE_COVER_MAX_PX))
    }

    @Test
    fun coverSampleSizeIsStableForDegenerateInput() {
        assertEquals(1, coverSampleSize(0, 0, 128))
        assertTrue(coverSampleSize(-5, 10, 0) >= 1)
    }

    // ── Library: stat rows ───────────────────────────────────────────────

    @Test
    fun libraryStatRowsChunksTwoUpByDefault() {
        assertEquals(
            listOf(
                listOf(LibraryStatKind.SONGS, LibraryStatKind.DOWNLOADED),
                listOf(LibraryStatKind.LYRICS, LibraryStatKind.PLAYLISTS),
            ),
            libraryStatRows(itemsPerRow = 2),
        )
    }

    @Test
    fun libraryStatRowsStacksOnePerLineOnANarrowCell() {
        assertEquals(4, libraryStatRows(itemsPerRow = 1).size)
        assertTrue(libraryStatRows(itemsPerRow = 1).all { it.size == 1 })
    }

    @Test
    fun libraryStatRowsRejectsANonPositiveRowWidth() {
        assertTrue(libraryStatRows(itemsPerRow = 0).isEmpty())
        assertTrue(libraryStatRows(itemsPerRow = -3).isEmpty())
    }

    // ── Library: composition ─────────────────────────────────────────────

    @Test
    fun libraryMediumCellKeepsHeaderBadgeAndAllFigures() {
        // 4x2 content box ≈ 234x94dp.
        val layout = statsLayout(234, 94)
        assertTrue(layout.showHeader)
        assertTrue("wide badge spells its state out", layout.showSyncText)
        assertFalse("no dedicated line when the badge carries the words", layout.showSyncLine)
        assertFalse("4x2 is too short for the progress block", layout.showProgress)
        assertEquals(2, layout.itemsPerRow)
        assertEquals(LIBRARY_STAT_ORDER.size, layout.statItems.size)
    }

    @Test
    fun libraryTallCellAddsTheProgressBlock() {
        // 4x4 content box ≈ 234x234dp.
        val layout = statsLayout(234, 234)
        assertTrue(layout.showProgress)
        assertTrue("a tall cell leads with the hero percentage", layout.showHeroPercent)
        assertEquals(2, layout.itemsPerRow)
    }

    @Test
    fun libraryNarrowTallCellGivesTheSyncReadoutItsOwnLine() {
        // 2x4 ≈ 94x250dp: no width for the badge text, plenty of height.
        val layout = statsLayout(94, 250)
        assertFalse(layout.showSyncText)
        assertTrue("the readout moves to its own line", layout.showSyncLine)
        assertEquals("figures stack on a narrow cell", 1, layout.itemsPerRow)
        assertTrue(layout.statItems.size <= LIBRARY_STAT_ORDER.size)
    }

    @Test
    fun libraryShortCellDropsTheHeaderButKeepsTheSyncLine() {
        val layout = statsLayout(234, 60)
        assertFalse(layout.showHeader)
        assertTrue("the sync readout must survive the dropped header", layout.showSyncLine)
    }

    @Test
    fun libraryOneRowCellsFoldEverythingIntoOneCombinedLine() {
        // 2x1/3x1/4x1 ≈ 44dp tall: a headerless cell that can hold neither the sync line
        // AND a figure row used to let the line eat the whole budget (3x1/4x1 dropped every
        // figure; 2x1 dropped the readout). Everything now shares one line, led by the dot.
        for (cell in listOf(93 to 1, 164 to 2, 234 to 2)) {
            val (w, expectedItems) = cell
            val layout = statsLayout(w, 44)
            assertTrue("$w: one combined line", layout.singleLine)
            assertFalse(layout.showSyncLine)
            assertEquals(expectedItems, layout.itemsPerRow)
            assertEquals(
                "figures survive on the combined line",
                expectedItems,
                layout.statItems.size,
            )
            assertTrue(
                "\"12,345 songs\" must fit the combined budget: ${layout.singleLineChars}",
                if (expectedItems == 1) {
                    layout.singleLineChars >= "12,345 songs".length
                } else {
                    layout.singleLineChars >= "12,345 songs · 9,876 dl".length
                },
            )
            assertTrue("...and each figure budget is still legible", layout.statItemChars >= 1)
        }
        // A 4x1 is wide enough to carry the short readout's words too.
        assertTrue(statsLayout(234, 44).syncOnLine)
        val wideFull = "12,345 songs · 9,876 dl · 3 min ago"
        assertTrue(
            "the full combined readout fits: ${statsLayout(234, 44).singleLineChars}",
            statsLayout(234, 44).singleLineChars >= wideFull.length,
        )
        // A short-but-taller headerless cell keeps the dedicated line AND the figures.
        val taller = statsLayout(234, 60)
        assertFalse(taller.singleLine)
        assertTrue(taller.showSyncLine)
    }

    @Test
    fun libraryProgressOnlyTallCellsTopAlignTheirContent() {
        assertFalse("the hero cell keeps its centring", statsLayout(234, 234).topAlign)
        assertTrue("4x3 pins to the top", statsLayout(234, 164).topAlign)
        assertTrue("6x3 pins to the top", statsLayout(369, 164).topAlign)
        assertFalse("a cell without the progress block keeps centring", statsLayout(234, 94).topAlign)
    }

    @Test
    fun libraryTrimsFiguresRatherThanClipping() {
        val short = statsLayout(94, 60)
        val tall = statsLayout(94, 250)
        assertTrue(
            "figures must not grow as the cell shrinks: ${tall.statItems.size}/${short.statItems.size}",
            tall.statItems.size >= short.statItems.size,
        )
        assertTrue(short.statItems.size <= LIBRARY_STAT_ORDER.size)
    }

    @Test
    fun libraryOuterColumnStaysUnderTheChildCap() {
        for (w in listOf(94, 150, 200, 234, 370, 600)) {
            for (h in listOf(40, 60, 66, 94, 120, 150, 164, 234, 320, 500)) {
                val layout = statsLayout(w, h)
                val children = (if (layout.showHeader) 1 else 0) +
                    (if (layout.showSyncLine) 1 else 0) +
                    layout.statRows.size +
                    (if (layout.showProgress) 1 else 0)
                assertTrue(
                    "library children $children > cap at ${w}x$h",
                    children <= WIDGET_COLUMN_MAX_CHILDREN,
                )
            }
        }
    }

    @Test
    fun libraryBudgetsAtLeastOneCharacterEverywhere() {
        for (w in listOf(0, 40, 94, 150, 234, 600)) {
            for (h in listOf(0, 30, 66, 120, 234, 500)) {
                val layout = statsLayout(w, h)
                assertTrue("statItemChars >= 1 at ${w}x$h", layout.statItemChars >= 1)
                assertTrue("badgeChars >= 1 at ${w}x$h", layout.badgeChars >= 1)
                assertTrue("syncChars >= 1 at ${w}x$h", layout.syncChars >= 1)
                assertTrue("percentSp bounded at ${w}x$h", layout.percentSp in 22..60)
            }
        }
    }

    @Test
    fun libraryFourWideCellsKeepTheSmallerStatSizeSoNothingIsCut() {
        // 4x4 ≈ 234x234dp: at 14sp each of the two figures gets ~15 characters, which cut
        // "742 with lyrics". 13sp keeps 15 and leaves room for the short fallback.
        val fourByFour = statsLayout(234, 234)
        assertEquals(13, fourByFour.statSp)
        assertTrue(
            "\"742 with lyrics\" must fit the 4x4 budget: ${fourByFour.statItemChars}",
            fourByFour.statItemChars >= "742 with lyrics".length,
        )
        // The truly wide cells can afford the larger size.
        assertEquals(14, statsLayout(369, 94).statSp)
    }

    @Test
    fun libraryNarrowCellsAskForTheShortSyncReadout() {
        assertTrue("a 2-wide cell uses the short readout", statsLayout(94, 234).shortSync)
        assertFalse("a 4-wide cell keeps the full readout", statsLayout(234, 234).shortSync)
    }

    @Test
    fun libraryNarrowHeaderBadgeUsesTheShortReadoutToo() {
        // 3x3 ≈ 164x164dp: the badge shares its line with "Library", so a 13-character budget
        // cut "Synced 3 min ago" to "Synced 3 min…". The short form must fit.
        val threeByThree = statsLayout(164, 164)
        assertTrue("a 3x3 is compact", threeByThree.shortSync)
        assertTrue(
            "\"3 min ago\" must fit the badge budget: ${threeByThree.badgeChars}",
            threeByThree.badgeChars >= "3 min ago".length,
        )
        assertTrue(
            "the full readout must not fit (else the short form is unnecessary): ${threeByThree.badgeChars}",
            threeByThree.badgeChars < "Synced 3 min ago".length,
        )
        // A wide cell keeps the full readout on its badge.
        assertFalse(statsLayout(234, 234).shortSync)
    }

    @Test
    fun libraryNarrowCellsUseTheSmallerStatSizeSoGroupedCountsReadWhole() {
        // 2x4 ≈ 94x250dp: at 13sp a 94dp figure budget is 11 characters, which cut the grouped
        // "12,345 songs" to "12,345 son…". 12sp keeps 12 and the thousands read whole.
        val narrow = statsLayout(94, 250)
        assertEquals(12, narrow.statSp)
        assertTrue(
            "\"12,345 songs\" must fit the 2-wide budget: ${narrow.statItemChars}",
            narrow.statItemChars >= "12,345 songs".length,
        )
        assertTrue(
            "\"5,432 lyrics\" must fit the 2-wide budget: ${narrow.statItemChars}",
            narrow.statItemChars >= "5,432 lyrics".length,
        )
        // The wider cells keep the larger sizes that already fit their labels.
        assertEquals(13, statsLayout(234, 234).statSp)
        assertEquals(14, statsLayout(369, 164).statSp)
    }

    @Test
    fun libraryProgressCaptionDropsItsLabelOnANarrowCell() {
        // The 2x3 (~93dp) collapsed the caption's weighted spacer and read "Downloaded76%";
        // it drops the word and shows just the figure.
        assertFalse("a 2x3 is too narrow for the caption word", statsLayout(94, 164).progressLabel)
        assertTrue("a 3x3 keeps the caption word", statsLayout(164, 164).progressLabel)
        assertTrue("a 4x4 keeps the caption word", statsLayout(234, 234).progressLabel)
    }

    @Test
    fun libraryHeroPercentIsCappedByTheCellWidth() {
        // 2x4 ≈ 94x250dp: a height-only hero (54sp) would clip "76%" in a 78dp strip, so the
        // width caps it.
        val narrow = statsLayout(94, 250)
        assertTrue("narrow hero ${narrow.percentSp}", narrow.percentSp <= 39)
        // The tall 4x4 grows the hero to fill its slack.
        assertTrue("4x4 hero ${statsLayout(234, 234).percentSp}", statsLayout(234, 234).percentSp >= 50)
    }

    // ── Library: explicit truncation ─────────────────────────────────────

    @Test
    fun ellipsizeLeavesShortTextAloneAndTrimsWhitespace() {
        assertEquals("Night Drive", ellipsize("  Night Drive  ", 20))
        assertEquals("Night Drive", ellipsize("Night Drive", 11))
    }

    @Test
    fun ellipsizeAddsOneEllipsisWhenItMustCut() {
        assertEquals("Night…", ellipsize("Night Drive", 6))
        assertEquals("…", ellipsize("Night Drive", 1))
        assertEquals("", ellipsize("Night Drive", 0))
    }

    // ── Library: the readout ─────────────────────────────────────────────

    @Test
    fun syncLabelKindPrefersSyncingThenNeverThenTheWindow() {
        val now = 1_000_000L
        assertEquals(SyncLabelKind.SYNCING, syncLabelKind(isSyncing = true, lastSyncedAtMs = now, nowMs = now))
        assertEquals(SyncLabelKind.NEVER, syncLabelKind(isSyncing = false, lastSyncedAtMs = null, nowMs = now))
        assertEquals(
            SyncLabelKind.JUST_NOW,
            syncLabelKind(isSyncing = false, lastSyncedAtMs = now - JUST_NOW_WINDOW_MS + 1, nowMs = now),
        )
        assertEquals(
            SyncLabelKind.AGO,
            syncLabelKind(isSyncing = false, lastSyncedAtMs = now - JUST_NOW_WINDOW_MS, nowMs = now),
        )
    }

    @Test
    fun syncAgePartsBucketsIntoMinutesHoursAndDays() {
        assertEquals(SyncAge(1, SyncAgeUnit.MINUTES), syncAgeParts(0L))
        assertEquals(SyncAge(1, SyncAgeUnit.MINUTES), syncAgeParts(90_000L))
        assertEquals(SyncAge(59, SyncAgeUnit.MINUTES), syncAgeParts(59L * 60_000L))
        assertEquals(SyncAge(1, SyncAgeUnit.HOURS), syncAgeParts(60L * 60_000L))
        assertEquals(SyncAge(23, SyncAgeUnit.HOURS), syncAgeParts(23L * 60L * 60_000L))
        assertEquals(SyncAge(1, SyncAgeUnit.DAYS), syncAgeParts(24L * 60L * 60_000L))
        assertEquals(SyncAge(1, SyncAgeUnit.MINUTES), syncAgeParts(-5_000L))
    }

    @Test
    fun formatStatCountGroupsDigits() {
        assertEquals("0", formatStatCount(0))
        assertEquals("9", formatStatCount(9))
        assertEquals("1,000", formatStatCount(1_000))
        assertEquals("12,345", formatStatCount(12_345))
    }

    // ── Library: aggregates ──────────────────────────────────────────────

    @Test
    fun libraryStatsFractionAndPercentAreSafeOnAnEmptyLibrary() {
        assertEquals(0f, LibraryStats.EMPTY.downloadedFraction, 0.0001f)
        assertEquals(0, LibraryStats.EMPTY.downloadedPercent)
    }

    @Test
    fun libraryStatsFractionAndPercentTrackTheDownloadedShare() {
        val stats = LibraryStats(totalSongs = 4, downloadedSongs = 3, songsWithLyrics = 2, playlists = 1)
        assertEquals(0.75f, stats.downloadedFraction, 0.0001f)
        assertEquals(75, stats.downloadedPercent)
    }

    @Test
    fun libraryStatsFractionIsClamped() {
        val stats = LibraryStats(totalSongs = 2, downloadedSongs = 5, songsWithLyrics = 0, playlists = 0)
        assertEquals(1f, stats.downloadedFraction, 0.0001f)
        assertEquals(100, stats.downloadedPercent)
    }

    // ── Character budgets ────────────────────────────────────────────────

    @Test
    fun textCharBudgetShrinksWithTextSizeAndNeverReachesZero() {
        assertTrue(textCharBudget(100, 10) > textCharBudget(100, 20))
        assertEquals(1, textCharBudget(0, 12))
        assertEquals(1, textCharBudget(50, 0))
        assertEquals(1, textCharBudget(-10, 12))
    }

    @Test
    fun textCharBudgetAcceptsATighterRatioForCountLines() {
        // The count line is digits and lowercase: at the same width it holds one more
        // character than the (conservative) general budget.
        assertEquals(9, textCharBudget(47, 10))
        assertEquals(10, textCharBudget(47, 10, WIDGET_COUNT_CHAR_WIDTH_RATIO))
    }
}
