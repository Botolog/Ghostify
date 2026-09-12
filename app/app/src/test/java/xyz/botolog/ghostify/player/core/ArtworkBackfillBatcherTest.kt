package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtworkBackfillBatcherTest {

    private class FakeClock {
        var nowMs: Long = 0L

        fun tick(ms: Long) {
            nowMs += ms
        }
    }

    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        clock = FakeClock()
    }

    private fun batcher(
        batchSize: Int = ArtworkBackfillBatcher.DEFAULT_BATCH_SIZE,
        intervalMs: Long = ArtworkBackfillBatcher.DEFAULT_INTERVAL_MS,
    ) = ArtworkBackfillBatcher(batchSize, intervalMs) { clock.nowMs }

    // ── Immediate publish for the current item ───────────────────────────

    @Test
    fun artworkForCurrentSongPublishesImmediately() {
        val b = batcher()
        assertTrue(b.shouldPublish("song-1", currentMediaId = "song-1"))
    }

    @Test
    fun immediatePublishDoesNotDependOnBatchOrInterval() {
        val b = batcher(batchSize = 100, intervalMs = 1_000)
        assertFalse(b.shouldPublish("a", currentMediaId = "b"))
        assertTrue(b.shouldPublish("c", currentMediaId = "c"))
    }

    @Test
    fun nullCurrentMediaIdNeverPublishesImmediately() {
        val b = batcher()
        assertFalse(b.shouldPublish("song-1", currentMediaId = null))
    }

    // ── Batch-size batching ──────────────────────────────────────────────

    @Test
    fun buffersBelowBatchSizeWithoutIntervalElapsing() {
        val b = batcher(batchSize = 5, intervalMs = 200)
        repeat(4) { i ->
            clock.tick(10)
            assertFalse("update ${i + 1} should buffer", b.shouldPublish("s$i", "current"))
        }
    }

    @Test
    fun nthExtractionReachingBatchSizeTriggersPublish() {
        val b = batcher(batchSize = 5, intervalMs = 200)
        repeat(4) { i ->
            clock.tick(10)
            b.shouldPublish("s$i", "current")
        }
        clock.tick(10)
        assertTrue(b.shouldPublish("s5", "current"))
    }

    @Test
    fun counterResetsAfterBatchPublish() {
        val b = batcher(batchSize = 3, intervalMs = 200)
        repeat(3) { i ->
            clock.tick(10)
            b.shouldPublish("s$i", "current")
        } // publishes on the 3rd
        clock.tick(10)
        assertFalse("buffer restarts after a flush", b.shouldPublish("x", "current"))
        clock.tick(10)
        assertFalse(b.shouldPublish("y", "current"))
        clock.tick(10)
        assertTrue(b.shouldPublish("z", "current"))
    }

    @Test
    fun counterResetsAfterImmediateCurrentItemPublish() {
        val b = batcher(batchSize = 2, intervalMs = 200)
        assertFalse(b.shouldPublish("s1", "current"))
        assertTrue(b.shouldPublish("s2", "s2")) // immediate; must reset the buffer
        clock.tick(10)
        assertFalse("buffer restarted by immediate flush", b.shouldPublish("s3", "current"))
        clock.tick(10)
        assertTrue(b.shouldPublish("s4", "current"))
    }

    // ── Interval-based flushing ──────────────────────────────────────────

    @Test
    fun intervalElapsedTriggersPublishEvenBelowBatchSize() {
        val b = batcher(batchSize = 10, intervalMs = 200)
        assertFalse(b.shouldPublish("s1", "current"))
        clock.tick(199)
        assertFalse(b.shouldPublish("s2", "current"))
        clock.tick(1) // exactly intervalMs since last publish
        assertTrue(b.shouldPublish("s3", "current"))
    }

    @Test
    fun intervalWindowStartsAtLastPublishNotAtFirstBufferedUpdate() {
        val b = batcher(batchSize = 10, intervalMs = 100)
        clock.tick(99)
        assertFalse(b.shouldPublish("s1", "current")) // 99ms since last publish < interval
        clock.tick(1)
        assertTrue(b.shouldPublish("s2", "current")) // 100ms since construction flushes
    }

    @Test
    fun intervalWindowRestartsAfterEachPublish() {
        val b = batcher(batchSize = 10, intervalMs = 100)
        clock.tick(100)
        assertTrue(b.shouldPublish("s1", "current")) // flush at t=100
        clock.tick(99)
        assertFalse(b.shouldPublish("s2", "current")) // window restarted at t=100
        clock.tick(1)
        assertTrue(b.shouldPublish("s3", "current")) // 100ms since previous flush
    }
}
