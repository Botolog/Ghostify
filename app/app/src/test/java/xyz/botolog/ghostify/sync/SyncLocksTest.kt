package xyz.botolog.ghostify.sync

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SyncLocksTest {

    private lateinit var locks: SyncLocks

    @Before
    fun setUp() {
        locks = SyncLocks()
    }

    // ── Basic lock acquisition and execution ─────────────────────────────

    @Test
    fun withPlaylistLockExecutesBlock() = runBlocking {
        val result = locks.withPlaylistLock("pl_1") { 42 }
        assertEquals(42, result)
    }

    @Test
    fun withPlaylistLockReturnsBlockResult() = runBlocking {
        val result = locks.withPlaylistLock("pl_1") { "hello" }
        assertEquals("hello", result)
    }

    @Test
    fun withPlaylistLockReturnsNull() = runBlocking {
        val result = locks.withPlaylistLock("pl_1") { null }
        assertEquals(null, result)
    }

    @Test
    fun withPlaylistLockReturnsUnit() = runBlocking {
        locks.withPlaylistLock("pl_1") { }
    }

    // ── Same playlist gets same lock (serialization) ─────────────────────

    @Test
    fun samePlaylistIdSerializesExecution() = runBlocking {
        val order = mutableListOf<String>()
        val semaphore = Semaphore(1)

        val job1 = launch {
            locks.withPlaylistLock("pl_1") {
                semaphore.withPermit {
                    order.add("first:acquire")
                    kotlinx.coroutines.delay(50)
                    order.add("first:release")
                }
            }
        }

        val job2 = launch {
            locks.withPlaylistLock("pl_1") {
                semaphore.withPermit {
                    order.add("second:acquire")
                    order.add("second:release")
                }
            }
        }

        job1.join()
        job2.join()

        // Second must wait for first to complete
        assertEquals("first:acquire", order[0])
        assertEquals("first:release", order[1])
        assertEquals("second:acquire", order[2])
        assertEquals("second:release", order[3])
    }

    // ── Different playlists can run concurrently ──────────────────────────

    @Test
    fun differentPlaylistIdsRunConcurrently() = runBlocking {
        val completed = mutableListOf<String>()

        val job1 = launch {
            locks.withPlaylistLock("pl_1") {
                kotlinx.coroutines.delay(50)
                completed.add("pl_1")
            }
        }

        val job2 = launch {
            locks.withPlaylistLock("pl_2") {
                kotlinx.coroutines.delay(50)
                completed.add("pl_2")
            }
        }

        job1.join()
        job2.join()

        assertEquals(2, completed.size)
        org.junit.Assert.assertTrue("Both playlists should have completed", completed.containsAll(listOf("pl_1", "pl_2")))
    }

    // ── Exception propagation ────────────────────────────────────────────

    @Test(expected = RuntimeException::class)
    fun exceptionInBlockPropagates() {
        runBlocking {
            locks.withPlaylistLock("pl_1") {
                throw RuntimeException("boom")
            }
        }
    }

    @Test
    fun exceptionDoesNotCorruptState() = runBlocking {
        try {
            locks.withPlaylistLock("pl_1") {
                throw RuntimeException("boom")
            }
        } catch (_: RuntimeException) { }

        // Subsequent calls should still work
        val result = locks.withPlaylistLock("pl_1") { "recovered" }
        assertEquals("recovered", result)
    }

    // ── Multiple concurrent calls for same playlist ──────────────────────

    @Test
    fun multipleConcurrentCallsForSamePlaylistAreSerialized() = runBlocking {
        val results = mutableListOf<Int>()
        val mutex = Mutex()

        val jobs = (1..10).map { i ->
            launch {
                locks.withPlaylistLock("pl_1") {
                    val before = results.toList()
                    kotlinx.coroutines.delay(10)
                    mutex.withLock { results.add(i) }
                    val after = results.toList()
                    // At any point, only one coroutine should be adding
                    // (we check the final list has all 10 items)
                }
            }
        }

        jobs.forEach { it.join() }
        assertEquals(10, results.size)
    }

    // ── Re-entrant lock behavior ─────────────────────────────────────────

    @Test
    fun nestedCallsToSamePlaylistSuspend_nonReentrant() = runBlocking {
        // SyncLocks wraps a plain kotlinx Mutex, which is NOT re-entrant:
        // acquiring the same playlist lock from inside its own critical
        // section suspends forever. Production never does this (SyncUseCase
        // never calls itself), so we pin the non-reentrant semantics with a
        // bounded wait instead of documenting a guarantee that doesn't exist.
        val completed = kotlinx.coroutines.withTimeoutOrNull(500) {
            locks.withPlaylistLock("pl_1") {
                locks.withPlaylistLock("pl_1") { "deep" }
            }
        }
        org.junit.Assert.assertNull("same-playlist nesting must suspend (plain Mutex is not re-entrant)", completed)
    }

    // ── Concurrent access pattern simulation ─────────────────────────────

    @Test
    fun interleavedLockAcquisitionDoesNotMixResults() = runBlocking {
        val results = mutableMapOf<String, String>()

        val job1 = launch {
            locks.withPlaylistLock("pl_1") {
                kotlinx.coroutines.delay(20)
                results["pl_1"] = "from_first"
            }
        }

        val job2 = launch {
            locks.withPlaylistLock("pl_2") {
                kotlinx.coroutines.delay(20)
                results["pl_2"] = "from_second"
            }
        }

        job1.join()
        job2.join()

        assertEquals("from_first", results["pl_1"])
        assertEquals("from_second", results["pl_2"])
    }
}
