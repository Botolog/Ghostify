package com.ghostify.perf.memory

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.ghostify.perf.DownloadLeakTracker
import com.ghostify.perf.HeapGrowthAnalyzer
import com.ghostify.perf.HeapSample
import com.ghostify.perf.android.RetainedHeapProbe
import leakcanary.LeakAssertions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T-166 (instrumentation): repeated downloads must not grow the heap without
 * bound and must not leak a Python-bridge wrapper (T-166).
 *
 * The real download loop opens a spotdl/Chaquopy session for a playlist,
 * downloads, then closes it. This test drives that lifecycle N times against a
 * stub "download" and asserts two independent leak signals:
 *
 *  1. lifecycle   — [DownloadLeakTracker] records every open/close; after the
 *    loop nothing is retained (every wrapper released exactly once).
 *  2. heap trend  — [RetainedHeapProbe] samples the Java heap (post-GC) after
 *    each iteration; [HeapGrowthAnalyzer] fits a trend line and flags only a
 *    steady, time-correlated growth as unbounded (not GC noise).
 *  3. LeakCanary  — [LeakAssertions.assertNoLeaks] as a supplementary
 *    whole-heap sweep for native / reference-graph leaks.
 *
 * The heap-growth and tracker MATH is unit-tested in `MemoryMetricsTest` and
 * `DownloadLeakTrackerTest`; this test exercises the real heap + GC probes.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DownloadLeakTest {

    @Test
    fun repeatedDownloadsDoNotLeakWrapperOrGrowHeap() {
        runOnBg {
            val clock = { SystemClock.elapsedRealtime() }
            val tracker = DownloadLeakTracker(clock = clock)
            val samples = ArrayList<HeapSample>()
            val iterations = 12

            for (i in 0 until iterations) {
                val id = tracker.open("downloadAll:p1")   // bridge wrapper acquired
                simulateDownloadIteration(i)              // heavy work, off-main in production
                assertTrue("wrapper $id never released", tracker.close(id))
                RetainedHeapProbe.gcAndWait()
                samples += RetainedHeapProbe.sample("download")
            }

            // 1) lifecycle: every wrapper was closed exactly once.
            assertEquals("leaked wrappers", 0, tracker.retainedCount())

            // 2) heap trend: no steady, correlated growth across iterations.
            val report = HeapGrowthAnalyzer.analyze(samples)
            assertFalse("repeated downloads grow heap unboundedly: $report", report.unbounded)

            // 3) LeakCanary whole-heap sweep (supplementary).
            LeakAssertions.assertNoLeaks(LeakAssertions.NO_TAG)
        }
    }

    private fun simulateDownloadIteration(index: Int) {
        // Placeholder for the real Chaquopy / spotdl download dispatched to IO.
        // `index` varies the work so the loop can't be hoisted/eliminated.
        Thread.sleep(20L + (index % 3))
    }

    private fun runOnBg(block: () -> Unit) {
        val exec = Executors.newSingleThreadExecutor()
        try {
            val future = exec.submit(Runnable { block() })
            try {
                future.get(60, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw (e.cause ?: e)
            }
        } finally {
            exec.shutdown()
        }
    }
}
