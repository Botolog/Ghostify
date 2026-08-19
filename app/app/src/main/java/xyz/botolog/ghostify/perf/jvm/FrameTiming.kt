package xyz.botolog.ghostify.perf

/**
 * Frame-duration statistics for the Ghostify jank budget (T-163).
 *
 * The budget is "render a list of 100 playlists without jank", i.e. every
 * frame lands inside a 60 Hz frame interval. [JankBudget] exposes the canonical
 * threshold and a tiny predicate; [FrameTiming] turns a raw list of per-frame
 * durations (ms) into a summary with the percentiles a CI or a device run
 * should assert on.
 *
 * Pure JVM — unit-tested in `FrameTimingTest`.
 */
object JankBudget {

    /** Nominal 60 Hz frame budget, in ms. */
    const val FRAME_BUDGET_MS = 16.0

    /** Nominal 60 Hz frame budget, in ns (direct comparison with [android.view.FrameMetrics]). */
    const val FRAME_BUDGET_NS = 16_666_667L

    /** A frame that takes longer than [budgetMs] is a "jank" frame. */
    fun isJanky(frameDurationMs: Double, budgetMs: Double = FRAME_BUDGET_MS): Boolean =
        frameDurationMs > budgetMs
}

/**
 * Summary of a measured frame run. `passesBudget` is the single assertion the
 * instrumentation tests use for "no jank" (p95 inside budget, and at least one
 * frame actually measured).
 */
data class FrameTimingStats(
    val sampleCount: Int,
    val minMs: Double,
    val maxMs: Double,
    val meanMs: Double,
    val p50Ms: Double,
    val p90Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val jankyFrames: Int,
    val budgetMs: Double,
) {
    /** Percentage of frames that exceeded the budget. */
    val jankPercent: Double
        get() = if (sampleCount == 0) 0.0 else 100.0 * jankyFrames / sampleCount

    /**
     * The assertion used by T-163: a *valid* sample (at least one frame measured)
     * whose p95 stays within budget. An empty sample can't prove smoothness, so it
     * fails rather than silently passing.
     */
    val passesBudget: Boolean
        get() = sampleCount > 0 && p95Ms <= budgetMs

    override fun toString(): String =
        "frames=$sampleCount min=${fmt(minMs)} mean=${fmt(meanMs)} p50=${fmt(p50Ms)} " +
            "p90=${fmt(p90Ms)} p95=${fmt(p95Ms)} p99=${fmt(p99Ms)} max=${fmt(maxMs)} " +
            "jank=$jankyFrames ($jankPercent%)"

    private fun fmt(v: Double): String = "%.2fms".format(v)
}

object FrameTiming {

    /**
     * Linear-interpolated percentile of a sorted list (`q` in 0..100). This is
     * the same definition used by common profiling tools.
     */
    fun percentile(sortedAsc: List<Double>, q: Double): Double {
        require(q in 0.0..100.0) { "percentile must be in 0..100, got $q" }
        if (sortedAsc.isEmpty()) return Double.NaN
        if (sortedAsc.size == 1) return sortedAsc[0]
        val rank = q / 100.0 * (sortedAsc.size - 1)
        val lo = rank.toInt()
        val hi = lo + 1
        val frac = rank - lo
        return if (hi < sortedAsc.size) {
            sortedAsc[lo] * (1 - frac) + sortedAsc[hi] * frac
        } else {
            sortedAsc[lo]
        }
    }

    /** Build a [FrameTimingStats] from raw per-frame durations (ms). */
    fun summarize(
        durationsMs: List<Double>,
        budgetMs: Double = JankBudget.FRAME_BUDGET_MS,
    ): FrameTimingStats {
        if (durationsMs.isEmpty()) {
            return FrameTimingStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, budgetMs)
        }
        val sorted = durationsMs.sorted()
        val n = sorted.size
        val mean = sorted.sum() / n
        val janky = sorted.count { JankBudget.isJanky(it, budgetMs) }
        return FrameTimingStats(
            sampleCount = n,
            minMs = sorted.first(),
            maxMs = sorted.last(),
            meanMs = mean,
            p50Ms = percentile(sorted, 50.0),
            p90Ms = percentile(sorted, 90.0),
            p95Ms = percentile(sorted, 95.0),
            p99Ms = percentile(sorted, 99.0),
            jankyFrames = janky,
            budgetMs = budgetMs,
        )
    }
}
