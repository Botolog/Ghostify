package com.ghostify.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BridgeStallDetectorTest {

    @Test
    fun gaps_from_frame_times_ns() {
        val frames = listOf(0L, 16_666_667L, 33_333_334L, 50_000_001L)
        val gaps = BridgeStallDetector.gapsFromFrameTimesNs(frames)
        assertEquals(3, gaps.size)
        assertEquals(16.666667, gaps[0], 1e-6)
        assertEquals(16.666667, gaps[1], 1e-6)
    }

    @Test
    fun gaps_too_few_frames() {
        assertTrue(BridgeStallDetector.gapsFromFrameTimesNs(emptyList()).isEmpty())
        assertTrue(BridgeStallDetector.gapsFromFrameTimesNs(listOf(1L)).isEmpty())
    }

    @Test
    fun smooth_frames_have_no_stalls() {
        // 10 frames at ~16.67ms each → gaps ~16.67ms, none > 32ms threshold.
        val frames = (0..10).map { it * 16_666_667L }
        val gaps = BridgeStallDetector.gapsFromFrameTimesNs(frames)
        val s = BridgeStallDetector.analyze(gaps)
        assertFalse(s.stalled)
        assertEquals(0, s.stallCount)
    }

    @Test
    fun single_blocking_call_creates_stall() {
        // 5 normal frames then a 100ms gap (e.g. synchronous bridge call).
        val framesNs = mutableListOf<Long>()
        var t = 0L
        repeat(5) {
            framesNs.add(t)
            t += 16_666_667L
        }
        t += 100_000_000L // 100ms gap
        framesNs.add(t)
        val gaps = BridgeStallDetector.gapsFromFrameTimesNs(framesNs)
        val s = BridgeStallDetector.analyze(gaps)
        assertTrue(s.stalled)
        assertEquals(1, s.stallCount)
        assertTrue(s.maxGapMs > 32.0)
    }

    @Test
    fun custom_threshold() {
        val gaps = listOf(20.0, 20.0, 50.0, 20.0)
        val s = BridgeStallDetector.analyze(gaps, stallThresholdMs = 40.0)
        assertEquals(1, s.stallCount)
    }

    @Test
    fun rejects_non_positive_threshold() {
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            BridgeStallDetector.analyze(listOf(1.0), stallThresholdMs = 0.0)
        }
        assertTrue(ex.message!!.contains("stallThresholdMs"))
    }

    @Test
    fun empty_gaps() {
        val s = BridgeStallDetector.analyze(emptyList())
        assertEquals(0, s.frameCount)
        assertFalse(s.stalled)
    }
}
