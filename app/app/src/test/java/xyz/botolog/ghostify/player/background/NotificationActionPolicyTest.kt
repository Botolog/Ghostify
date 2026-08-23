package xyz.botolog.ghostify.player.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.background.core.NotificationActionPolicy
import xyz.botolog.ghostify.background.core.PlaybackAction

class NotificationActionPolicyTest {

    @Test
    fun `compactActions returns PREVIOUS PLAY_PAUSE NEXT`() {
        val expected = listOf(PlaybackAction.PREVIOUS, PlaybackAction.PLAY_PAUSE, PlaybackAction.NEXT)
        assertEquals(expected, NotificationActionPolicy.compactActions())
    }

    @Test
    fun `compactActions has exactly 3 items`() {
        assertEquals(3, NotificationActionPolicy.compactActions().size)
    }

    @Test
    fun `compactActions does not contain STOP`() {
        assertTrue(NotificationActionPolicy.compactActions().none { it == PlaybackAction.STOP })
    }

    @Test
    fun `expandedActions returns PREVIOUS PLAY_PAUSE NEXT STOP`() {
        val expected = listOf(PlaybackAction.PREVIOUS, PlaybackAction.PLAY_PAUSE, PlaybackAction.NEXT, PlaybackAction.STOP)
        assertEquals(expected, NotificationActionPolicy.expandedActions())
    }

    @Test
    fun `expandedActions has exactly 4 items`() {
        assertEquals(4, NotificationActionPolicy.expandedActions().size)
    }

    @Test
    fun `expandedActions contains STOP`() {
        assertTrue(NotificationActionPolicy.expandedActions().contains(PlaybackAction.STOP))
    }

    @Test
    fun `playPauseShowsPause true when isPlaying`() {
        assertEquals(true, NotificationActionPolicy.playPauseShowsPause(true))
    }

    @Test
    fun `playPauseShowsPause false when not playing`() {
        assertEquals(false, NotificationActionPolicy.playPauseShowsPause(false))
    }
}
