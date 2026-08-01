package com.ghostify.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryMetricsTest {

    @Test
    fun empty_samples() {
        val r = HeapGrowthAnalyzer.analyze(emptyList())
        assertEquals(0, r.samples)
        assertFalse(r.unbounded)
    }

    @Test
    fun flat_heap_is_not_unbounded() {
        // Bytes constant over time → slope 0, correlation undefined (syy=0) → not unbounded.
        val samples = listOf(
            HeapSample(0L, 50_000_000),
            HeapSample(1_000L, 50_000_000),
            HeapSample(2_000L, 50_000_000),
            HeapSample(3_000L, 50_000_000),
        )
        val r = HeapGrowthAnalyzer.analyze(samples)
        assertEquals(0.0, r.slopeBytesPerMinute, 1e-6)
        assertFalse(r.unbounded)
    }

    @Test
    fun steady_leak_detected_as_unbounded() {
        // +5 MiB per minute, perfectly linear → slope > threshold, corr = 1.0.
        val samples = (0..10).map { i ->
            HeapSample(i * 1_000L, 50_000_000L + i * (5 * 1024 * 1024 / 60))
        }
        val r = HeapGrowthAnalyzer.analyze(
            samples, slopeThresholdBytesPerMinute = HeapGrowthAnalyzer.DEFAULT_SLOPE_THRESHOLD_BYTES_PER_MINUTE,
        )
        assertTrue(r.slopeBytesPerMinute > 0)
        assertTrue(r.correlation > HeapGrowthReport.MIN_CORRELATION)
        assertTrue(r.unbounded) { "steady linear leak should be flagged unbounded: $r" }
    }

    @Test
    fun noisy_gc_does_not_trigger_leak() {
        // Jagged: up 4MiB, down 3MiB, up 5MiB — slope tiny, correlation low.
        val base = 50_000_000L
        val samples = listOf(
            HeapSample(0L, base),
            HeapSample(1_000L, base + 4_000_000),
            HeapSample(2_000L, base + 1_000_000),
            HeapSample(3_000L, base + 6_000_000),
            HeapSample(4_000L, base + 3_000_000),
            HeapSample(5_000L, base + 8_000_000),
        )
        val r = HeapGrowthAnalyzer.analyze(
            samples, slopeThresholdBytesPerMinute = HeapGrowthAnalyzer.DEFAULT_SLOPE_THRESHOLD_BYTES_PER_MINUTE,
        )
        assertFalse(r.unbounded)
    }

    @Test
    fun two_point_sample_is_not_unbounded() {
        // < 3 samples → cannot assert unbounded (too short to establish a trend).
        val r = HeapGrowthAnalyzer.analyze(
            listOf(HeapSample(0L, 50_000_000), HeapSample(1_000L, 90_000_000)),
        )
        assertFalse(r.unbounded)
    }

    @Test
    fun requires_at_least_three_correlated_samples() {
        // Steady linear, but only 2 points → still not unbounded.
        val r = HeapGrowthAnalyzer.analyze(
            listOf(HeapSample(0L, 50_000_000), HeapSample(1_000L, 70_000_000)),
        )
        assertFalse(r.unbounded)
    }

    @Test
    fun delta_and_duration_reported() {
        val samples = listOf(
            HeapSample(0L, 50_000_000),
            HeapSample(30_000L, 50_200_000),
            HeapSample(60_000L, 50_400_000),
        )
        val r = HeapGrowthAnalyzer.analyze(samples)
        assertEquals(60_000L, r.durationMillis)
        assertEquals(400_000L, r.deltaBytes)
        assertEquals(50_000_000L, r.startBytes)
        assertEquals(50_400_000L, r.endBytes)
    }
}
