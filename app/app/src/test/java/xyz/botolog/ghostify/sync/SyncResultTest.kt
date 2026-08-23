package xyz.botolog.ghostify.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResultTest {

    // ── Construction ─────────────────────────────────────────────────────

    @Test
    fun createsWithAllFields() {
        val result = SyncResult(
            added = 5,
            removed = 2,
            requeued = 3,
            metadataUpdated = 4,
            filesDeleted = 1,
        )

        assertEquals(5, result.added)
        assertEquals(2, result.removed)
        assertEquals(3, result.requeued)
        assertEquals(4, result.metadataUpdated)
        assertEquals(1, result.filesDeleted)
    }

    @Test
    fun createsWithZeros() {
        val result = SyncResult(
            added = 0,
            removed = 0,
            requeued = 0,
            metadataUpdated = 0,
            filesDeleted = 0,
        )

        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertEquals(0, result.requeued)
        assertEquals(0, result.metadataUpdated)
        assertEquals(0, result.filesDeleted)
    }

    // ── hasChanges ───────────────────────────────────────────────────────

    @Test
    fun hasChangesTrueWhenAdded() {
        val result = SyncResult(added = 1, removed = 0, requeued = 0, metadataUpdated = 0, filesDeleted = 0)
        assertTrue(result.hasChanges)
    }

    @Test
    fun hasChangesTrueWhenRemoved() {
        val result = SyncResult(added = 0, removed = 1, requeued = 0, metadataUpdated = 0, filesDeleted = 0)
        assertTrue(result.hasChanges)
    }

    @Test
    fun hasChangesTrueWhenRequeued() {
        val result = SyncResult(added = 0, removed = 0, requeued = 1, metadataUpdated = 0, filesDeleted = 0)
        assertTrue(result.hasChanges)
    }

    @Test
    fun hasChangesTrueWhenMetadataUpdated() {
        val result = SyncResult(added = 0, removed = 0, requeued = 0, metadataUpdated = 1, filesDeleted = 0)
        assertTrue(result.hasChanges)
    }

    @Test
    fun hasChangesFalseWhenOnlyFilesDeleted() {
        val result = SyncResult(added = 0, removed = 0, requeued = 0, metadataUpdated = 0, filesDeleted = 5)
        assertFalse(result.hasChanges)
    }

    @Test
    fun hasChangesFalseWhenAllZero() {
        val result = SyncResult(added = 0, removed = 0, requeued = 0, metadataUpdated = 0, filesDeleted = 0)
        assertFalse(result.hasChanges)
    }

    @Test
    fun hasChangesTrueWhenMultipleFieldsNonZero() {
        val result = SyncResult(added = 1, removed = 1, requeued = 0, metadataUpdated = 0, filesDeleted = 0)
        assertTrue(result.hasChanges)
    }

    // ── Equality (data class) ────────────────────────────────────────────

    @Test
    fun equalInstancesAreEqual() {
        val a = SyncResult(1, 2, 3, 4, 5)
        val b = SyncResult(1, 2, 3, 4, 5)
        assertEquals(a, b)
    }

    @Test
    fun differentInstancesAreNotEqual() {
        val a = SyncResult(1, 2, 3, 4, 5)
        val b = SyncResult(1, 2, 3, 4, 6)
        assertFalse(a == b)
    }

    // ── copy ─────────────────────────────────────────────────────────────

    @Test
    fun copyModifiesOnlySpecifiedFields() {
        val original = SyncResult(1, 2, 3, 4, 5)
        val copy = original.copy(added = 10)

        assertEquals(10, copy.added)
        assertEquals(2, copy.removed)
        assertEquals(3, copy.requeued)
        assertEquals(4, copy.metadataUpdated)
        assertEquals(5, copy.filesDeleted)
    }

    // ── SyncException ────────────────────────────────────────────────────

    @Test
    fun playlistNotFoundContainsId() {
        val ex = SyncException.PlaylistNotFound("pl_123")
        assertEquals("pl_123", ex.playlistId)
        assertTrue(ex.message!!.contains("pl_123"))
    }

    @Test
    fun networkExceptionContainsSpotifyIdAndCause() {
        val cause = RuntimeException("timeout")
        val ex = SyncException.Network("spotify_abc", cause)
        assertEquals("spotify_abc", ex.spotifyId)
        assertEquals(cause, ex.cause)
        assertTrue(ex.message!!.contains("spotify_abc"))
    }

    @Test
    fun playlistNotFoundIsSyncException() {
        val ex = SyncException.PlaylistNotFound("x")
        assertTrue(ex is SyncException)
    }

    @Test
    fun networkExceptionIsSyncException() {
        val ex = SyncException.Network("x", RuntimeException())
        assertTrue(ex is SyncException)
    }
}
