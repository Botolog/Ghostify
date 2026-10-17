package xyz.botolog.ghostify.trackdownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.perf.DownloadLeakTracker

/**
 * Verifies the native downloader-handle lifecycle: the handle is released
 * exactly once per use, on success, on failure and on cancellation, and never
 * used after release.
 *
 * The lease is exercised with a plain [AutoCloseable] fake rather than a mocked
 * Chaquopy `PyObject`: `PyObject` has `native` `toString`/`equals`/`hashCode` and
 * a private constructor, so it cannot be instantiated or verified on the JVM
 * without a loaded interpreter.
 */
class PyDownloaderLeaseTest {

    /** Records how many times it was closed; optionally fails on close. */
    private class FakeHandle(
        private val failOnClose: Boolean = false,
    ) : AutoCloseable {
        var closes: Int = 0
            private set

        override fun close() {
            closes++
            if (failOnClose) throw RuntimeException("interpreter gone")
        }
    }

    private fun handle() = FakeHandle()

    // ── Disposal in withPyDownloaderLease ──────────────────────────────

    @Test
    fun `handle is closed exactly once on success`() {
        val handle = handle()

        val returned = withPyDownloaderLease(factory = { handle }) { it }

        assertSame(handle, returned)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `handle is closed exactly once when the block throws`() {
        val handle = handle()

        assertThrows(IllegalStateException::class.java) {
            withPyDownloaderLease(factory = { handle }) { error("download failed") }
        }

        assertEquals(1, handle.closes)
    }

    @Test
    fun `handle is closed exactly once when the block is cancelled`() {
        val handle = handle()

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            withPyDownloaderLease(factory = { handle }) { throw kotlinx.coroutines.CancellationException("cancel") }
        }

        assertEquals(1, handle.closes)
    }

    @Test
    fun `onAcquired and onReleased wrap the block in order`() {
        val handle = handle()
        val events = mutableListOf<String>()

        withPyDownloaderLease(
            factory = { handle },
            onAcquired = { events += "acquired" },
            onReleased = { events += "released" },
        ) { events += "used"; it }

        assertEquals(listOf("acquired", "used", "released"), events)
        assertEquals(1, handle.closes)
    }

    // ── Idempotence / no use-after-close ───────────────────────────────

    @Test
    fun `close is idempotent and only closes the handle once`() {
        val handle = handle()
        val lease = PyDownloaderLease(handle)

        lease.close()
        lease.close()

        assertEquals(1, handle.closes)
        assertTrue(lease.isClosed)
    }

    @Test
    fun `use after close fails fast`() {
        val handle = handle()
        val lease = PyDownloaderLease(handle)
        lease.close()

        assertThrows(IllegalStateException::class.java) { lease.use() }
        // The failed use must not touch the already-released handle a second time.
        assertEquals(1, handle.closes)
    }

    // ── Cancellation ───────────────────────────────────────────────────

    @Test
    fun `cancel before use releases the handle once`() {
        val handle = handle()
        val lease = PyDownloaderLease(handle)

        lease.cancel()

        assertEquals(1, handle.closes)
        assertTrue(lease.isClosed)
    }

    @Test
    fun `cancel while in use defers, then close releases exactly once`() {
        val handle = handle()
        val lease = PyDownloaderLease(handle)
        lease.use()

        lease.cancel()
        assertEquals(0, handle.closes)

        lease.close()
        assertEquals(1, handle.closes)
    }

    @Test
    fun `cancel and close together never double-close`() {
        val handle = handle()
        val lease = PyDownloaderLease(handle)

        lease.cancel()
        lease.close()

        assertEquals(1, handle.closes)
    }

    // ── Leak-tracker balance (T-166) ───────────────────────────────────

    @Test
    fun `leak tracker is balanced after a successful use`() {
        val handle = handle()
        val tracker = DownloadLeakTracker()

        withPyDownloaderLease(tracker = tracker, ownerTag = "t1", factory = { handle }) { it }

        assertEquals(0, tracker.retainedCount())
        assertEquals(0, tracker.retained("t1").size)
    }

    @Test
    fun `leak tracker is balanced when the block throws`() {
        val handle = handle()
        val tracker = DownloadLeakTracker()

        assertThrows(IllegalStateException::class.java) {
            withPyDownloaderLease(tracker = tracker, ownerTag = "t1", factory = { handle }) {
                error("boom")
            }
        }

        assertEquals(0, tracker.retainedCount())
    }

    @Test
    fun `a failed release does not throw out of the lease`() {
        val handle = FakeHandle(failOnClose = true)
        val lease = PyDownloaderLease(handle)

        // Must not throw even though the underlying handle refuses to close.
        lease.close()
        lease.close()

        assertTrue(lease.isClosed)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `isClosed reflects the released state`() {
        val handle = handle()
        val lease = PyDownloaderLease(handle)

        assertFalse(lease.isClosed)
        lease.close()
        assertTrue(lease.isClosed)
    }
}
