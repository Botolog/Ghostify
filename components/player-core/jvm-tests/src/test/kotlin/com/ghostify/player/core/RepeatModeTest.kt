package com.ghostify.player.core

import kotlin.test.*

/**
 * JVM mirror of the shuffle/repeat-mode mapping part of T-083/T-084: the domain enum values
 * must line up with Media3's public `Player.REPEAT_MODE_*` constants (OFF=0, ONE=1, ALL=2).
 */
class RepeatModeTest {

    @Test
    fun `media3Value matches Player repeat mode constants`() {
        assertEquals(0, RepeatMode.OFF.media3Value, "REPEAT_MODE_OFF")
        assertEquals(1, RepeatMode.ONE.media3Value, "REPEAT_MODE_ONE")
        assertEquals(2, RepeatMode.ALL.media3Value, "REPEAT_MODE_ALL")
    }

    @Test
    fun `fromMedia3 round-trips every value`() {
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(0))
        assertEquals(RepeatMode.ONE, RepeatMode.fromMedia3(1))
        assertEquals(RepeatMode.ALL, RepeatMode.fromMedia3(2))
        assertEquals(RepeatMode.OFF, RepeatMode.fromMedia3(99), "unknown values are safe")
    }

    @Test
    fun `next cycles OFF to ALL to ONE to OFF`() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }
}
