package xyz.botolog.ghostify.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.ui.model.FullPlayerLayout

/**
 * The stored layout is a user setting, so it is never rewritten — but landscape has no room for
 * the normal layout, and it must keep the queue button within reach. Both rules are pure functions
 * of the stored value and the orientation, and that is what these tests pin down.
 */
class FullPlayerRenderLayoutTest {

    // ── Landscape render mode ────────────────────────────────────────────────

    @Test
    fun landscapeRendersTheStoredNormalLayoutAsCompact() {
        assertEquals(
            FullPlayerLayout.COMPACT,
            fullPlayerRenderLayout(FullPlayerLayout.NORMAL, isLandscape = true),
        )
    }

    @Test
    fun landscapeLeavesCompactAndSuperCompactAsStored() {
        assertEquals(
            FullPlayerLayout.COMPACT,
            fullPlayerRenderLayout(FullPlayerLayout.COMPACT, isLandscape = true),
        )
        assertEquals(
            FullPlayerLayout.SUPER_COMPACT,
            fullPlayerRenderLayout(FullPlayerLayout.SUPER_COMPACT, isLandscape = true),
        )
    }

    @Test
    fun portraitRendersEveryStoredLayoutExactly() {
        FullPlayerLayout.entries.forEach { layout ->
            assertEquals(layout, fullPlayerRenderLayout(layout, isLandscape = false))
        }
    }

    @Test
    fun everyLandscapeLayoutDrawsItsControlsOnTheCover() {
        val controlsOnCover = FullPlayerLayout.entries.map { layout ->
            fullPlayerRenderLayout(layout, isLandscape = true) == FullPlayerLayout.COMPACT
        }
        assertEquals(listOf(true, true, false), controlsOnCover)
    }

    // ── Landscape panel placement ───────────────────────────────────────────

    @Test
    fun theControlsHalfComesFirstWhenItSitsOnTheLeft() {
        assertEquals(
            listOf(LandscapePanel.CONTROLS, LandscapePanel.LYRICS),
            landscapePanels("left"),
        )
    }

    @Test
    fun theLyricsHalfComesFirstWhenTheControlsSitOnTheRight() {
        assertEquals(
            listOf(LandscapePanel.LYRICS, LandscapePanel.CONTROLS),
            landscapePanels("right"),
        )
    }

    @Test
    fun bothLandscapeVariantsDrawEachHalfExactlyOnce() {
        listOf("left", "right").forEach { side ->
            assertEquals(
                "controls duplicated for $side",
                1,
                landscapePanels(side).count { it == LandscapePanel.CONTROLS },
            )
            assertEquals(
                "lyrics duplicated for $side",
                1,
                landscapePanels(side).count { it == LandscapePanel.LYRICS },
            )
        }
    }

    // ── Top bar ownership ───────────────────────────────────────────────────

    @Test
    fun theTopBarBelongsToTheControlsHalfInBothLandscapeVariants() {
        listOf("left", "right").forEach { side ->
            val owners = landscapePanels(side).filter { panel -> panel.drawsTopBar }
            assertEquals(
                "the landscape top bar is not drawn exactly once for $side",
                listOf(LandscapePanel.CONTROLS),
                owners,
            )
        }
    }

    @Test
    fun theLyricsHalfNeverDrawsTheTopBar() {
        assertFalse(LandscapePanel.LYRICS.drawsTopBar)
    }

    @Test
    fun anUnknownControlsSideStillLeavesTheTopBarOnTheControlsHalf() {
        val owners = landscapePanels("up").filter { panel -> panel.drawsTopBar }
        assertEquals(listOf(LandscapePanel.CONTROLS), owners)
    }

    // ── Landscape top bar ────────────────────────────────────────────────────

    @Test
    fun theLandscapeTopBarOffersTheQueue() {
        assertTrue(
            "the landscape top bar has no queue button",
            landscapeTopBarActions().contains(FullPlayerTopBarAction.QUEUE),
        )
    }

    @Test
    fun theLandscapeTopBarKeepsSongInfo() {
        assertTrue(
            landscapeTopBarActions().contains(FullPlayerTopBarAction.INFO),
        )
    }

    @Test
    fun theQueueButtonSitsBeforeSongInfo() {
        val actions = landscapeTopBarActions()
        assertEquals(
            listOf(FullPlayerTopBarAction.QUEUE, FullPlayerTopBarAction.INFO),
            actions,
        )
        assertTrue(actions.indexOf(FullPlayerTopBarAction.QUEUE) < actions.indexOf(FullPlayerTopBarAction.INFO))
    }

    @Test
    fun everyTopBarActionIsAnnouncedByItsAction() {
        assertEquals("Queue", FullPlayerTopBarAction.QUEUE.description)
        assertEquals("Song info", FullPlayerTopBarAction.INFO.description)
    }

    @Test
    fun everyTopBarActionDrawsAnIcon() {
        landscapeTopBarActions().forEach { action ->
            assertTrue(
                "no icon for ${action.name}",
                action.icon.defaultWidth > 0.dp && action.icon.defaultHeight > 0.dp,
            )
        }
    }
}
