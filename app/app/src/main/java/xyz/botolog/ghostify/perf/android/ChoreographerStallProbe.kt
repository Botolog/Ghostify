package xyz.botolog.ghostify.perf.android

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import xyz.botolog.ghostify.perf.BridgeStallDetector
import xyz.botolog.ghostify.perf.StallStats
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Choreographer frame-gap probe for the "bridge must not block the UI" budget
 * (T-165). Registers a [Choreographer.FrameCallback] and records the monotonic
 * frame timestamps; consecutive-frame gaps are then classified by
 * [BridgeStallDetector].
 *
 * Frames are driven by the render pipeline on the main thread, so if the UI
 * thread is ever blocked (e.g. a synchronous Python call), frame callbacks stop
 * arriving and the resulting gap is measured directly.
 *
 * Android-only — compiled by the `androidCheck` source set.
 */
class ChoreographerStallProbe : AutoCloseable, Choreographer.FrameCallback {

    private val frameTimesNs = ArrayList<Long>()
    private val choreographer = Choreographer.getInstance()

    @Volatile
    private var running = false

    /** Start recording frames. Must be called on (or before) the UI thread usage. */
    fun start() {
        if (running) return
        running = true
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        frameTimesNs.add(frameTimeNanos)
        choreographer.postFrameCallback(this)
    }

    /** Classify the recorded frame gaps. */
    fun stats(): StallStats =
        BridgeStallDetector.analyze(BridgeStallDetector.gapsFromFrameTimesNs(frameTimesNs))

    override fun close() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    companion object {

        /**
         * Run [block] on the main thread while a frame-gap probe is active and
         * return the resulting stall statistics.
         *
         * This is the measurement harness for T-165: the app wires [block] to a
         * real bridge dispatch (async, off-main) and asserts `!stats.stalled`.
         */
        fun runBlockingOnMain(block: () -> Unit): StallStats {
            val probe = ChoreographerStallProbe()
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                probe.start()
                try {
                    block()
                } finally {
                    probe.close()
                    latch.countDown()
                }
            }
            if (!latch.await(30, TimeUnit.SECONDS)) {
                probe.close()
            }
            return probe.stats()
        }
    }
}
