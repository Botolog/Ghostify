package com.ghostify.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FrameTimingTest {

    @Test
    fun empty_summary_has_no_samples() {
        val s = FrameTiming.summarize(emptyList())
        assertEquals(0, s.sampleCount)
        assertFalse(s.passesBudget)
    }

    @Test
    fun percentile_linear_interpolation() {
        // sorted ascending: [0, 10, 20, 30, 40] → rank(90) = 4.0
        val data = listOf(0.0, 10.0, 20.0, 30.0, 40.0)
        // Linear interpolation (numpy "linear", q in 0..1 via (n-1)):
        //   p90: rank=0.9*4=3.6 -> 30*0.4 + 40*0.6 = 36.0
        //   p95: rank=0.95*4=3.8 -> 30*0.2 + 40*0.8 = 38.0
        assertEquals(36.0, FrameTiming.percentile(data, 90.0))
        assertEquals(20.0, FrameTiming.percentile(data, 50.0))
        assertEquals(38.0, FrameTiming.percentile(data, 95.0), 1e-9)
    }

    @Test
    fun percentile_single_element() {
        assertEquals(7.0, FrameTiming.percentile(listOf(7.0), 50.0))
    }

    @Test
    fun percentile_rejects_out_of_range() {
        val thrown = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            FrameTiming.percentile(listOf(1.0), 150.0)
        }
        assertTrue(thrown.message!!.contains("percentile"))
    }

    @Test
    fun janky_frames_counted() {
        // Budget 16 ms. 4 frames at 8ms (ok), 1 frame at 33ms (jank).
        val s = FrameTiming.summarize(listOf(8.0, 8.0, 8.0, 8.0, 33.0), budgetMs = 16.0)
        assertEquals(5, s.sampleCount)
        assertEquals(1, s.jankyFrames)
        assertEquals(33.0, s.maxMs, 1e-9)
        // p95 of [8,8,8,8,33] = 33 → not within budget.
        assertFalse(s.passesBudget)
    }

    @Test
    fun smooth_run_passes_budget() {
        // All frames within 16ms at 60 Hz: mean and p95 well under budget.
        val s = FrameTiming.summarize(
            listOf(7.0, 9.0, 8.0, 12.0, 6.0, 10.0, 8.0, 11.0, 9.0, 13.0),
            budgetMs = JankBudget.FRAME_BUDGET_MS,
        )
        assertTrue(s.passesBudget)
        assertEquals(0, s.jankyFrames)
    }

    @Test
    fun jankThreshold_default_budget_is_16ms() {
        assertTrue(JankBudget.isJanky(20.0))
        assertFalse(JankBudget.isJanky(16.0))
        assertFalse(JankBudget.isJanky(15.0))
    }

    @Test
    fun frameBudgetNs_matches_frame_budget_ms() {
        assertEquals(16_666_667L, JankBudget.FRAME_BUDGET_NS)
    }
}
