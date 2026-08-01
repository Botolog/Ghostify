package com.ghostify.perf

import kotlin.math.sqrt

/**
 * Heap-growth analysis for the memory budget (T-166, T-164).
 *
 * Repeated downloads must not grow the heap without bound. [HeapGrowthAnalyzer]
 * fits a least-squares line through a series of heap samples and classifies the
 * trend: a positive slope that is *strongly correlated* with time (steady leak)
 * is "unbounded"; a flat or noisy profile is not. Two-point snapshots are too
 * weak to call a leak, which is why the instrumentation test samples across
 * many download iterations.
 *
 * Pure JVM — unit-tested in `MemoryMetricsTest`.
 */
data class HeapSample(val atMillis: Long, val usedBytes: Long, val tag: String = "")

data class HeapGrowthReport(
    val samples: Int,
    val durationMillis: Long,
    val startBytes: Long,
    val endBytes: Long,
    val deltaBytes: Long,
    val slopeBytesPerMinute: Double,
    val correlation: Double,
    val slopeThresholdBytesPerMinute: Long,
) {
    val deltaMiB: Double get() = deltaBytes / (1024.0 * 1024.0)

    /**
     * True when the heap is growing at a steady, time-correlated rate that
     * exceeds the threshold — i.e. a repeatable leak, not GC noise.
     */
    val unbounded: Boolean
        get() = samples >= 3 &&
            slopeBytesPerMinute > slopeThresholdBytesPerMinute &&
            correlation >= MIN_CORRELATION

    override fun toString(): String =
        "samples=$samples duration=${durationMillis}ms delta=${"%.1f".format(deltaMiB)}MiB " +
            "slope=${"%.1f".format(slopeBytesPerMinute / (1024.0 * 1024.0))}MiB/min " +
            "corr=${"%.3f".format(correlation)} unbounded=$unbounded"

    companion object {
        /** A trend line must be this correlated with time before we call it a leak. */
        const val MIN_CORRELATION = 0.85
    }
}

object HeapGrowthAnalyzer {

    /**
     * Default leak threshold: ~2 MiB / minute of steady, correlated growth.
     * T-166's loop of ~20 downloads completes in well under a minute, so a real
     * per-download leak of a few hundred KiB easily clears this; legitimate
     * caches that are bounded by GC do not.
     */
    const val DEFAULT_SLOPE_THRESHOLD_BYTES_PER_MINUTE = 2L * 1024 * 1024

    /** Fit a least-squares line `used ~ time` over the samples. */
    fun analyze(
        samples: List<HeapSample>,
        slopeThresholdBytesPerMinute: Long = DEFAULT_SLOPE_THRESHOLD_BYTES_PER_MINUTE,
    ): HeapGrowthReport {
        val sorted = samples.sortedBy { it.atMillis }
        if (sorted.isEmpty()) {
            return HeapGrowthReport(0, 0, 0, 0, 0, Double.NaN, Double.NaN, slopeThresholdBytesPerMinute)
        }

        val n = sorted.size
        val start = sorted.first()
        val end = sorted.last()
        val duration = end.atMillis - start.atMillis

        if (n < 2) {
            return HeapGrowthReport(n, duration, start.usedBytes, end.usedBytes, 0, Double.NaN, Double.NaN, slopeThresholdBytesPerMinute)
        }

        val meanX = sorted.sumOf { it.atMillis.toDouble() } / n
        val meanY = sorted.sumOf { it.usedBytes.toDouble() } / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (s in sorted) {
            val dx = s.atMillis - meanX
            val dy = s.usedBytes - meanY
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        val slopePerMs = if (sxx == 0.0) Double.NaN else sxy / sxx
        val slopePerMinute = if (slopePerMs.isNaN()) Double.NaN else slopePerMs * 60_000.0
        val correlation = if (sxx == 0.0 || syy == 0.0) Double.NaN else sxy / sqrt(sxx * syy)

        return HeapGrowthReport(
            samples = n,
            durationMillis = duration,
            startBytes = start.usedBytes,
            endBytes = end.usedBytes,
            deltaBytes = end.usedBytes - start.usedBytes,
            slopeBytesPerMinute = slopePerMinute,
            correlation = correlation,
            slopeThresholdBytesPerMinute = slopeThresholdBytesPerMinute,
        )
    }
}
