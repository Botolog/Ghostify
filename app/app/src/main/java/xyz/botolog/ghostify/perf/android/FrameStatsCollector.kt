package xyz.botolog.ghostify.perf.android

import android.app.Activity
import androidx.core.app.FrameMetricsAggregator
import xyz.botolog.ghostify.perf.FrameTiming
import xyz.botolog.ghostify.perf.FrameTimingStats

/**
 * Thin, defensive wrapper over [FrameMetricsAggregator] (androidx.core) that
 * records every frame's TOTAL_DURATION while an activity is on screen.
 *
 * [androidx.core.app.FrameMetricsAggregator] keeps per-frame durations in a
 * `SparseIntArray` (frame index -> duration ms) exposed through [getMetrics].
 * When constructed with a single metric type ([FrameMetricsAggregator.TOTAL_DURATION])
 * the returned array has one entry (`TOTAL_INDEX == 0`) holding every frame.
 *
 * Used by T-163 / T-164:
 * ```
 * FrameStatsCollector(activity).use { c ->
 *     c.start()
 *     ... drive the list ...
 *     check(c.stats().passesBudget)
 * }
 * ```
 *
 * Android-only — compiled by the `androidCheck` source set.
 */
class FrameStatsCollector(private val activity: Activity) : AutoCloseable {

    private val aggregator = FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)

    /** Begin tracking frames for [activity]. Safe to call once per collector. */
    fun start() {
        aggregator.add(activity)
    }

    /**
     * All recorded TOTAL_DURATION frames in ms (chronological per frame index).
     *
     * @return list of frame durations, or empty if no metrics are available.
     */
    fun snapshotFrameDurationsMs(): List<Double> {
        val metrics = aggregator.getMetrics() ?: return emptyList()
        if (metrics.isEmpty()) return emptyList()
        val frames = metrics[0] ?: return emptyList()
        val out = ArrayList<Double>(frames.size())
        for (i in 0 until frames.size()) {
            out.add(frames.valueAt(i).toDouble())
        }
        return out
    }

    /** Frame stats summary over everything recorded so far. */
    fun stats(): FrameTimingStats = FrameTiming.summarize(snapshotFrameDurationsMs())

    /** Stop tracking and drop the aggregator's listener. */
    override fun close() {
        aggregator.stop()
    }
}
