package com.ghostify.python

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM verification of TEST_PLAN T-029 (concurrent bridge calls are serialized):
 * the client-side [SerialExecutor] must run every task exactly once, one at a
 * time, on a single worker thread, in FIFO order — even under concurrent
 * submission. This is the guarantee that no interpreter call can interleave
 * with another and corrupt shared state.
 */
class SerialExecutorTest {

    @Test
    fun `executes every task exactly once, serially, FIFO, on one thread`() {
        val executor = SerialExecutor("test-serial")
        val n = 1_000
        val ran = IntArray(n)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val executed = AtomicInteger(0)
        val done = CountDownLatch(n)
        val producer = Executors.newFixedThreadPool(8)

        for (i in 0 until n) {
            val order = i
            producer.execute {
                executor.execute {
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    ran[order] = 1
                    executed.incrementAndGet()
                    inFlight.decrementAndGet()
                    done.countDown()
                }
            }
        }

        assertTrue("all tasks must complete", done.await(30, TimeUnit.SECONDS))
        producer.shutdown()
        executor.close()

        assertEquals(n, executed.get())
        assertEquals("no two tasks may run concurrently", 1, maxInFlight.get())
        for (i in 0 until n) {
            assertEquals("task $i must run exactly once", 1, ran[i])
        }
    }

    @Test
    fun `rejects work after close`() {
        val executor = SerialExecutor("test-closed")
        executor.close()
        try {
            executor.execute(Runnable { })
            fail("expected RejectedExecutionException after close")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // expected
        }
    }

    @Test
    fun `drains already queued tasks before close returns`() {
        val executor = SerialExecutor("test-drain")
        val ran = AtomicInteger(0)
        val done = CountDownLatch(50)
        for (i in 0 until 50) {
            executor.execute {
                ran.incrementAndGet()
                done.countDown()
            }
        }
        executor.close()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(50, ran.get())
    }
}
