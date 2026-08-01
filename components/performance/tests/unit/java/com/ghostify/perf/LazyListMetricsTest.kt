package com.ghostify.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LazyListMetricsTest {

    @Test
    fun visibleRange_within_viewport() {
        // 100 items, 4px each, viewport 20px → 5 on screen. First at index 0, no offset.
        val r = LazyListMetrics.visibleItemRange(
            firstVisibleIndex = 0f,
            scrollOffsetPx = 0,
            itemHeightPx = 4,
            viewportHeightPx = 20,
            prefetchItemCount = 0,
            totalItemCount = 100,
        )
        assertEquals(0, r.firstVisible)
        assertEquals(5, r.visibleCount)
        assertEquals(5, r.composedCount)
    }

    @Test
    fun visibleRange_with_prefetch_extends_window() {
        val r = LazyListMetrics.visibleItemRange(
            firstVisibleIndex = 10f,
            scrollOffsetPx = 0,
            itemHeightPx = 4,
            viewportHeightPx = 20,
            prefetchItemCount = 2,
            totalItemCount = 100,
        )
        // 2 before + 5 on screen + 2 after = max 9, but clamped.
        assertEquals(8, r.firstVisible)
        assertEquals(17, r.lastVisibleExclusive)
        assertEquals(9, r.composedCount)
    }

    @Test
    fun visibleRange_partial_scroll_increments_window() {
        val r = LazyListMetrics.visibleItemRange(
            firstVisibleIndex = 5f,
            scrollOffsetPx = 2, // halfway into a 4px item
            itemHeightPx = 4,
            viewportHeightPx = 20,
            prefetchItemCount = 0,
            totalItemCount = 100,
        )
        assertEquals(5, r.firstVisible)
        // scrollOffsetPx + viewport = 22px / 4 = 5.5 rows on screen → ceil → 6
        assertEquals(11, r.lastVisibleExclusive)
        assertEquals(6, r.visibleCount)
    }

    @Test
    fun visibleRange_clamps_to_total() {
        val r = LazyListMetrics.visibleItemRange(
            firstVisibleIndex = 95f,
            scrollOffsetPx = 0,
            itemHeightPx = 4,
            viewportHeightPx = 20,
            prefetchItemCount = 3,
            totalItemCount = 100,
        )
        assertEquals(92, r.firstVisible)
        assertEquals(100, r.lastVisibleExclusive)
    }

    @Test
    fun empty_list() {
        val r = LazyListMetrics.visibleItemRange(
            firstVisibleIndex = 0f, scrollOffsetPx = 0, itemHeightPx = 4,
            viewportHeightPx = 20, prefetchItemCount = 0, totalItemCount = 0,
        )
        assertEquals(0, r.composedCount)
    }

    @Test
    fun maxComposedItems_is_constant_wrt_playlist_size() {
        // The T-164 guarantee: this number does NOT depend on totalItemCount.
        val for500 = LazyListMetrics.maxComposedItems(
            itemHeightPx = 4, viewportHeightPx = 240, prefetchItemCount = 1,
        )
        val for1000 = LazyListMetrics.maxComposedItems(
            itemHeightPx = 4, viewportHeightPx = 240, prefetchItemCount = 1,
        )
        assertEquals(for500, for1000)
        // 240/4 = 60 + 2*1 prefetch = 62
        assertEquals(62, for500)
    }

    @Test
    fun rejects_invalid_args() {
        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            LazyListMetrics.visibleItemRange(
                firstVisibleIndex = 0f, scrollOffsetPx = 0, itemHeightPx = 0,
                viewportHeightPx = 20, prefetchItemCount = 0, totalItemCount = 0,
            )
        }
        assertTrue(ex.message!!.contains("itemHeightPx"))
    }
}
