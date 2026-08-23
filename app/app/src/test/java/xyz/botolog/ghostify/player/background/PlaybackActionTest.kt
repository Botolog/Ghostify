package xyz.botolog.ghostify.player.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.background.core.PlaybackAction

class PlaybackActionTest {

    @Test
    fun `enum has 4 values`() {
        assertEquals(4, PlaybackAction.values().size)
    }

    @Test
    fun `PREVIOUS exists`() {
        assertTrue(PlaybackAction.values().contains(PlaybackAction.PREVIOUS))
    }

    @Test
    fun `PLAY_PAUSE exists`() {
        assertTrue(PlaybackAction.values().contains(PlaybackAction.PLAY_PAUSE))
    }

    @Test
    fun `NEXT exists`() {
        assertTrue(PlaybackAction.values().contains(PlaybackAction.NEXT))
    }

    @Test
    fun `STOP exists`() {
        assertTrue(PlaybackAction.values().contains(PlaybackAction.STOP))
    }

    @Test
    fun `COMPACT list has 3 items`() {
        assertEquals(3, PlaybackAction.COMPACT.size)
    }

    @Test
    fun `COMPACT list contains PREVIOUS PLAY_PAUSE NEXT`() {
        assertEquals(listOf(PlaybackAction.PREVIOUS, PlaybackAction.PLAY_PAUSE, PlaybackAction.NEXT), PlaybackAction.COMPACT)
    }

    @Test
    fun `EXPANDED list has 4 items`() {
        assertEquals(4, PlaybackAction.EXPANDED.size)
    }

    @Test
    fun `EXPANDED list contains PREVIOUS PLAY_PAUSE NEXT STOP`() {
        assertEquals(listOf(PlaybackAction.PREVIOUS, PlaybackAction.PLAY_PAUSE, PlaybackAction.NEXT, PlaybackAction.STOP), PlaybackAction.EXPANDED)
    }

    @Test
    fun `EXPANDED is COMPACT plus STOP`() {
        assertEquals(PlaybackAction.COMPACT + PlaybackAction.STOP, PlaybackAction.EXPANDED)
    }
}
