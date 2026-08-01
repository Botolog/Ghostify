package com.ghostify.perf

/**
 * Frame-gap analysis for the "bridge must not block the UI" budget (T-165).
 *
 * While a bridge call is in flight we sample `Choreographer` frame timestamps.
 * If the Python bridge (correctly) runs on a background dispatcher, frames keep
 * arriving every ~16 ms and every gap is small. If anything blocks the main
 * thread (a bridge call dispatched on it, a synchronous interpreter call, a
 * lock), one or more frame intervals blow past the stall threshold. That gap is
 * the stall; [analyze] counts them.
 *
 * The instrumentation test uses a deliberately slow *negative control* on the
 * main thread to prove the probe detects stalls, then asserts the real
 * (async) bridge dispatch produces none.
 *
 * Pure JVM — unit-tested in `BridgeStallDetectorTest`.
 */
data class StallStats(
    val frameCount: Int,
    val maxGapMs: Double,
    val meanGapMs: Double,
    val stallCount: Int,
    val stallThresholdMs: Double,
) {
    /** True if any inter-frame gap exceeded the threshold. */
    val stalled: Boolean get() = stallCount > 0

    override fun toString(): String =
        "frames=$frameCount maxGap=${"%.2f".format(maxGapMs)}ms mean=${"%.2f".format(meanGapMs)}ms " +
            "stalls=$stallCount (threshold ${"%.0f".format(stallThresholdMs)}ms)"
}

object BridgeStallDetector {

    /**
     * Default stall threshold: two full 60 Hz frames. Sub-frame scheduling
     * noise (GC, renderer hiccups) can produce the odd 16-32 ms gap on devices;
     * anything above 2 frames is a genuine main-thread blockage.
     */
    const val DEFAULT_STALL_THRESHOLD_MS = 32.0

    /** Convert a monotonic frame-time series (ns) into consecutive gaps (ms). */
    fun gapsFromFrameTimesNs(frameTimesNs: List<Long>): List<Double> =
        if (frameTimesNs.size < 2) emptyList()
        else frameTimesNs.zipWithNext { a, b -> (b - a) / 1_000_000.0 }

    /** Summarize a series of inter-frame gaps (ms). */
    fun analyze(
        gapMs: List<Double>,
        stallThresholdMs: Double = DEFAULT_STALL_THRESHOLD_MS,
    ): StallStats {
        require(stallThresholdMs > 0) { "stallThresholdMs must be > 0" }
        val gaps = gapMs.filter { it >= 0.0 }
        if (gaps.isEmpty()) return StallStats(0, Double.NaN, Double.NaN, 0, stallThresholdMs)
        val stalls = gaps.count { it > stallThresholdMs }
        return StallStats(
            frameCount = gaps.size,
            maxGapMs = gaps.maxOrNull() ?: Double.NaN,
            meanGapMs = gaps.sum() / gaps.size,
            stallCount = stalls,
            stallThresholdMs = stallThresholdMs,
        )
    }
}
