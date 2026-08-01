package com.ghostify.player.core

import kotlin.test.*

class PlaybackStatusTest {

    @Test
    fun `player state values map to enum`() {
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(1))
        assertEquals(PlaybackStatus.BUFFERING, PlaybackStatus.fromPlayerState(2))
        assertEquals(PlaybackStatus.READY, PlaybackStatus.fromPlayerState(3))
        assertEquals(PlaybackStatus.ENDED, PlaybackStatus.fromPlayerState(4))
    }

    @Test
    fun `unknown state is safe`() {
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(-1))
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(0))
    }

    @Test
    fun `enum values mirror Player STATE constants`() {
        assertEquals(1, PlaybackStatus.IDLE.playerValue)
        assertEquals(2, PlaybackStatus.BUFFERING.playerValue)
        assertEquals(3, PlaybackStatus.READY.playerValue)
        assertEquals(4, PlaybackStatus.ENDED.playerValue)
    }
}
