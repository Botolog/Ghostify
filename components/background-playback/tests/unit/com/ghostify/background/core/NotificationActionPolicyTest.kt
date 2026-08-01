package com.ghostify.background.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationActionPolicyTest {

    @Test
    fun `compact view never includes the stop action`() {
        assertFalse(NotificationActionPolicy.compactActions().contains(PlaybackAction.STOP))
    }

    @Test
    fun `expanded view always includes the stop action`() {
        // T-097 / T-100: the stop affordance must always be present so the user
        // can tear playback down from the notification.
        assertTrue(NotificationActionPolicy.expandedActions().contains(PlaybackAction.STOP))
    }

    @Test
    fun `compact order is previous, play-pause, next`() {
        assertEquals(
            listOf(PlaybackAction.PREVIOUS, PlaybackAction.PLAY_PAUSE, PlaybackAction.NEXT),
            NotificationActionPolicy.compactActions(),
        )
    }

    @Test
    fun `expanded order appends stop after next`() {
        val expanded = NotificationActionPolicy.expandedActions()
        assertEquals(
            listOf(PlaybackAction.PREVIOUS, PlaybackAction.PLAY_PAUSE, PlaybackAction.NEXT, PlaybackAction.STOP),
            expanded,
        )
    }

    @Test
    fun `play-pause icon reflects playing state`() {
        // When playing the glyph is pause; when paused it is play.
        assertEquals(true, NotificationActionPolicy.playPauseShowsPause(isPlaying = true))
        assertEquals(false, NotificationActionPolicy.playPauseShowsPause(isPlaying = false))
    }
}
