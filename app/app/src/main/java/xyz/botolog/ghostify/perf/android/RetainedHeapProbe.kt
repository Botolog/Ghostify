package xyz.botolog.ghostify.perf.android

import android.os.Debug
import xyz.botolog.ghostify.perf.HeapSample

/**
 * Heap sampling probe for the memory budget (T-164, T-166).
 *
 * [sample] captures a [HeapSample] (java heap in use + timestamp). Callers
 * drive the workload, force a GC between samples, and hand the series to
 * [xyz.botolog.ghostify.perf.HeapGrowthAnalyzer] to classify growth as a leak or not.
 *
 * Android-only — compiled by the `androidCheck` source set.
 */
object RetainedHeapProbe {

    /** Java heap currently in use (total - free). */
    fun currentUsedBytes(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Native heap currently allocated (Chaquopy interpreter + spotdl buffers). */
    fun nativeHeapBytes(): Long = Debug.getNativeHeapAllocatedSize()

    /** Timestamped sample of the retained java heap, labelled by [tag]. */
    fun sample(tag: String = "heap"): HeapSample = HeapSample(System.currentTimeMillis(), currentUsedBytes(), tag)

    /**
     * Best-effort GC before sampling: a few explicit GC passes so short-lived
     * wrapper objects are collected before we measure retained growth.
     */
    fun gcAndWait() {
        repeat(GC_PASS_COUNT) {
            System.gc()
            Thread.sleep(GC_SLEEP_MILLIS)
        }
    }

    private const val GC_PASS_COUNT = 3
    private const val GC_SLEEP_MILLIS = 50L
}
