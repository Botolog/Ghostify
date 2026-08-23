package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStatusTest {

    @Test
    fun fromPlayerStateReturnsIdleForStateIdle() {
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(1))
    }

    @Test
    fun fromPlayerStateReturnsBufferingForStateBuffering() {
        assertEquals(PlaybackStatus.BUFFERING, PlaybackStatus.fromPlayerState(2))
    }

    @Test
    fun fromPlayerStateReturnsReadyForStateReady() {
        assertEquals(PlaybackStatus.READY, PlaybackStatus.fromPlayerState(3))
    }

    @Test
    fun fromPlayerStateReturnsEndedForStateEnded() {
        assertEquals(PlaybackStatus.ENDED, PlaybackStatus.fromPlayerState(4))
    }

    @Test
    fun fromPlayerStateReturnsIdleForUnknownNegativeValues() {
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(-1))
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(0))
    }

    @Test
    fun fromPlayerStateReturnsIdleForUnknownLargeValues() {
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(99))
        assertEquals(PlaybackStatus.IDLE, PlaybackStatus.fromPlayerState(Int.MAX_VALUE))
    }

    @Test
    fun playerValueConstantsMatchExpectedMedia3Values() {
        assertEquals(1, PlaybackStatus.IDLE.playerValue)
        assertEquals(2, PlaybackStatus.BUFFERING.playerValue)
        assertEquals(3, PlaybackStatus.READY.playerValue)
        assertEquals(4, PlaybackStatus.ENDED.playerValue)
    }

    @Test
    fun entriesCountIsFour() {
        assertEquals(4, PlaybackStatus.entries.size)
    }
}
