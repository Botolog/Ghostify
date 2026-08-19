package xyz.botolog.ghostify.perf

import kotlin.math.ceil

/**
 * Bounds mathematics for Compose lazy lists (T-164).
 *
 * The core claim that makes a 1000-track playlist safe is: a `LazyColumn`
 * **composes only the visible window plus a small prefetch**, never the whole
 * list. These pure functions quantify that window so the guidance is concrete:
 * an instrumentation run can assert that a 1000-row scroll composes at most
 * `maxComposedItems(...)` rows, and the visible range derivation is unit-tested
 * here instead of being eyeballed.
 *
 * Pure JVM — unit-tested in `LazyListMetricsTest`.
 */
data class VisibleItemRange(
    val firstVisible: Int,
    val lastVisibleExclusive: Int,
    val visibleCount: Int,
) {
    val composedCount: Int get() = (lastVisibleExclusive - firstVisible).coerceAtLeast(0)

    override fun toString(): String =
        "VisibleItemRange(first=$firstVisible, lastExclusive=$lastVisibleExclusive, composed=$composedCount)"
}

object LazyListMetrics {

    /**
     * The item index window a lazy list is *allowed* to have materialized for a
     * given scroll position — i.e. on-screen items ± [prefetchItemCount].
     *
     * @param firstVisibleIndex the lazy list's firstVisibleItemIndex (Float; may
     *   be fractional when partially scrolled).
     * @param scrollOffsetPx the current scroll offset in px (0 when first item
     *   is exactly at the top).
     * @param itemHeightPx the height of one row (assumed uniform — the perf
     *   harness rows are fixed-height).
     * @param viewportHeightPx visible viewport height.
     * @param prefetchItemCount the list's `beyondBoundsItemCount` (default 0).
     * @param totalItemCount total number of rows in the list.
     */
    fun visibleItemRange(
        firstVisibleIndex: Float,
        scrollOffsetPx: Int,
        itemHeightPx: Int,
        viewportHeightPx: Int,
        prefetchItemCount: Int,
        totalItemCount: Int,
    ): VisibleItemRange {
        require(itemHeightPx > 0) { "itemHeightPx must be positive, got $itemHeightPx" }
        require(viewportHeightPx >= 0) { "viewportHeightPx must be >= 0" }
        require(prefetchItemCount >= 0) { "prefetchItemCount must be >= 0" }
        require(totalItemCount >= 0) { "totalItemCount must be >= 0" }

        if (totalItemCount == 0) return VisibleItemRange(0, 0, 0)

        val base = firstVisibleIndex.toDouble()
        val first = (base - prefetchItemCount).toInt().coerceAtLeast(0)
        val onScreenRows = (scrollOffsetPx + viewportHeightPx).toDouble() / itemHeightPx
        val last = ceil(base + onScreenRows + prefetchItemCount).toInt().coerceAtMost(totalItemCount)

        val firstOnScreen = base.toInt().coerceIn(0, totalItemCount)
        // The bottom of the viewport may land mid-item; a partially visible item
        // still gets composed, so the last touched index is the ceiling.
        val lastOnScreen = ceil(base + onScreenRows).toInt().coerceIn(firstOnScreen, totalItemCount)
        return VisibleItemRange(
            firstVisible = first,
            lastVisibleExclusive = last,
            visibleCount = (lastOnScreen - firstOnScreen).coerceAtLeast(0),
        )
    }

    /**
     * The absolute upper bound on how many rows can ever be composed at once for
     * a fixed-height list with the given viewport and prefetch. This is the
     * "no OOM by construction" number for T-164: it is a small constant that
     * does not scale with playlist size.
     */
    fun maxComposedItems(
        itemHeightPx: Int,
        viewportHeightPx: Int,
        prefetchItemCount: Int,
    ): Int = ceil(viewportHeightPx.toDouble() / itemHeightPx).toInt() + 2 * prefetchItemCount
}
