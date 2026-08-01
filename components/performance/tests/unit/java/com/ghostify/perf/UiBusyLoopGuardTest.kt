package com.ghostify.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UiBusyLoopGuardTest {

    @Test
    fun emits_first_value_immediately() {
        val t = CoalescingThrottle(minIntervalMillis = 500, clock = { 0L })
        val first = t.offer(10.0)
        assertEquals(10.0, first)
        assertEquals(1, t.emissionCount)
    }

    @Test
    fun coalesces_within_interval() {
        var now = 0L
        val t = CoalescingThrottle(minIntervalMillis = 500, clock = { now })
        assertEquals(10.0, t.offer(10.0)) // emitted at t=0
        now = 200L
        assertNull(t.offer(20.0)) // coalesced, not yet 500ms
        now = 400L
        assertNull(t.offer(30.0)) // coalesced, latest wins (30)
        assertEquals(1, t.emissionCount)
    }

    @Test
    fun emits_latest_after_interval() {
        var now = 0L
        val t = CoalescingThrottle(minIntervalMillis = 500, clock = { now })
        assertEquals(10.0, t.offer(10.0))   // leading emission
        now = 200L
        t.offer(20.0)                       // coalesced
        t.offer(30.0)                       // coalesced, latest-is-30 so far
        now = 600L                          // 600ms since last emission → emit the latest (40, just offered)
        assertEquals(40.0, t.offer(40.0))
        assertEquals(2, t.emissionCount)
    }

    @Test
    fun flush_releases_last_pending() {
        var now = 0L
        val t = CoalescingThrottle(minIntervalMillis = 500, clock = { now })
        t.offer(5.0)
        now = 200L
        t.offer(8.0)                        // coalesced → pending=8
        assertEquals(8.0, t.flush())        // flush releases the pending value
        now = 300L
        t.offer(9.0)                        // coalesced → pending=9
        assertEquals(9.0, t.flush())
    }

    @Test
    fun flush_is_empty_after_flush() {
        var now = 0L
        val t = CoalescingThrottle(minIntervalMillis = 500, clock = { now })
        t.offer(1.0)
        now = 200L
        t.offer(2.0)
        assertEquals(2.0, t.flush())
        assertNull(t.flush())
    }
}
