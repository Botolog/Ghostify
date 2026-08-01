package com.ghostify.perf.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.ghostify.perf.StallStats
import com.ghostify.perf.android.ChoreographerStallProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T-165 (instrumentation): Python-bridge calls never block the UI thread.
 *
 * The real Ghostify bridge (Chaquopy + spotdl) is dispatched on Dispatchers.IO
 * ([PROJECT.md] §7); these tests pin that invariant in two ways:
 *
 *  * async path  — a heavy bridge call runs off-main → Choreographer frame gaps
 *    stay under the stall threshold (no jank);
 *  * sync path   — the negative control (a synchronous call on the UI thread)
 *    IS detected as a stall, proving the probe isn't a no-op.
 *
 * The stall MATH itself (inter-frame gaps → stall count) is unit-tested in
 * `BridgeStallDetectorTest`; this test wires the real Choreographer probe to it.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BridgeThreadingTest {

    /** Stand-in for the real Chaquopy / spotdl bridge: work on Dispatchers.IO. */
    private suspend fun fetchPlaylistMetadata(spotifyId: String): String =
        withContext(Dispatchers.IO) {
            Thread.sleep(120) // simulate metadata fetch + YouTube resolution
            "metadata:$spotifyId"
        }

    @Test
    fun asyncBridgeCallDoesNotStallUi() {
        // Run the measurement on a worker thread so the test's default (main)
        // thread is never blocked: it keeps pumping Choreographer frames while
        // the bridge does its work, which is exactly the property under test.
        val stats = runOnBgResult {
            val probe = ChoreographerStallProbe()
            probe.start()
            runBlocking {
                val meta = async(Dispatchers.IO) { fetchPlaylistMetadata("abc") }.await()
                // A real bridge posts its result back to the main thread.
                withContext(Dispatchers.Main) { meta }
            }
            Thread.sleep(60) // drain any in-flight frame callback after the callback
            probe.close()
            probe.stats()
        }
        assertFalse("async bridge stalled the UI thread: $stats", stats.stalled)
    }

    /**
     * Negative control: a *synchronous* bridge call on the UI thread is the
     * anti-pattern the async test guards against. The probe must flag it.
     */
    @Test
    fun synchronousBridgeCallOnUi_stalls_negativeControl() {
        val stats = ChoreographerStallProbe.runBlockingOnMain {
            Thread.sleep(90) // naive synchronous bridge call on the UI thread
        }
        assertTrue("negative control should detect the stall: $stats", stats.stalled)
    }

    private fun <T> runOnBgResult(block: () -> T): T =
        try {
            val exec = Executors.newSingleThreadExecutor()
            try {
                exec.submit(Callable<T> { block() }).get(30, TimeUnit.SECONDS)
            } finally {
                exec.shutdown()
            }
        } catch (e: ExecutionException) {
            throw (e.cause ?: e)
        }
}
