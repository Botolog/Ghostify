package xyz.botolog.ghostify.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the process-wide sync holder that the "Library" widget reads.
 *
 * The holder is a global, so every test resets it on the way in and out. The behaviours
 * worth pinning: concurrent syncs are counted (not a boolean), a successful sync stamps the
 * finish time and clears the flag, and the cold-start seed only fills in a timestamp.
 */
class SyncStatusHolderTest {

    @Before
    fun setUp() {
        SyncStatusHolder.reset()
    }

    @After
    fun tearDown() {
        SyncStatusHolder.reset()
    }

    @Test
    fun startsIdleWithNoTimestamp() {
        assertEquals(SyncStatus(), SyncStatusHolder.state.value)
        assertFalse(SyncStatusHolder.current.isSyncing)
        assertNull(SyncStatusHolder.current.lastSyncedAtMs)
    }

    @Test
    fun concurrentSyncsAreCountedNotBoolean() {
        SyncStatusHolder.beginSync()
        SyncStatusHolder.beginSync()
        assertTrue(SyncStatusHolder.current.isSyncing)

        SyncStatusHolder.endSync()
        assertTrue("the second sync is still in flight", SyncStatusHolder.current.isSyncing)

        SyncStatusHolder.endSync()
        assertFalse(SyncStatusHolder.current.isSyncing)
    }

    @Test
    fun extraEndSyncIsSafe() {
        SyncStatusHolder.endSync()
        SyncStatusHolder.endSync()
        assertFalse(SyncStatusHolder.current.isSyncing)
    }

    @Test
    fun markSyncedStampsTheTimeAndClearsTheFlag() {
        SyncStatusHolder.beginSync()

        SyncStatusHolder.markSynced(1_234L)

        assertEquals(SyncStatus(false, 1_234L), SyncStatusHolder.state.value)
    }

    @Test
    fun restoreSeedsTheTimestampWithoutClobberingTheFlag() {
        SyncStatusHolder.beginSync()

        SyncStatusHolder.restore(999L)

        assertTrue(SyncStatusHolder.current.isSyncing)
        assertEquals(999L, SyncStatusHolder.current.lastSyncedAtMs)
    }

    @Test
    fun restoreWithNoTimestampKeepsTheExistingOne() {
        SyncStatusHolder.markSynced(500L)

        SyncStatusHolder.restore(null)

        assertEquals(500L, SyncStatusHolder.current.lastSyncedAtMs)
    }

    @Test
    fun resetDropsEverything() {
        SyncStatusHolder.beginSync()
        SyncStatusHolder.markSynced(42L)

        SyncStatusHolder.reset()

        assertEquals(SyncStatus(), SyncStatusHolder.state.value)
    }
}
