package xyz.botolog.ghostify.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongStateMachineTest {

    // ── canTransition ──────────────────────────────────────────────

    @Test
    fun `PENDING can transition to QUEUED`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.QUEUED))
    }

    @Test
    fun `PENDING cannot transition to DOWNLOADING`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING))
    }

    @Test
    fun `PENDING cannot transition to DOWNLOADED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.DOWNLOADED))
    }

    @Test
    fun `PENDING cannot transition to FAILED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.FAILED))
    }

    @Test
    fun `PENDING cannot transition to CANCELED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.CANCELED))
    }

    @Test
    fun `PENDING cannot transition to PENDING`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.PENDING, DownloadStatus.PENDING))
    }

    @Test
    fun `QUEUED can transition to DOWNLOADING`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING))
    }

    @Test
    fun `QUEUED can transition to PENDING`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.PENDING))
    }

    @Test
    fun `QUEUED cannot transition to DOWNLOADED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADED))
    }

    @Test
    fun `QUEUED cannot transition to FAILED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.FAILED))
    }

    @Test
    fun `QUEUED cannot transition to CANCELED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.CANCELED))
    }

    @Test
    fun `QUEUED cannot transition to QUEUED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.QUEUED, DownloadStatus.QUEUED))
    }

    @Test
    fun `DOWNLOADING can transition to DOWNLOADED`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.DOWNLOADED))
    }

    @Test
    fun `DOWNLOADING can transition to FAILED`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.FAILED))
    }

    @Test
    fun `DOWNLOADING can transition to CANCELED`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.CANCELED))
    }

    @Test
    fun `DOWNLOADING can transition to PENDING`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING))
    }

    @Test
    fun `DOWNLOADING cannot transition to QUEUED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED))
    }

    @Test
    fun `DOWNLOADING cannot transition to DOWNLOADING`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.DOWNLOADING, DownloadStatus.DOWNLOADING))
    }

    @Test
    fun `CANCELED can transition to PENDING`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.PENDING))
    }

    @Test
    fun `CANCELED cannot transition to QUEUED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.QUEUED))
    }

    @Test
    fun `CANCELED cannot transition to DOWNLOADING`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.DOWNLOADING))
    }

    @Test
    fun `CANCELED cannot transition to DOWNLOADED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.DOWNLOADED))
    }

    @Test
    fun `CANCELED cannot transition to FAILED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.FAILED))
    }

    @Test
    fun `CANCELED cannot transition to CANCELED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.CANCELED, DownloadStatus.CANCELED))
    }

    @Test
    fun `FAILED can transition to QUEUED`() {
        assertTrue(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.QUEUED))
    }

    @Test
    fun `FAILED cannot transition to PENDING`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.PENDING))
    }

    @Test
    fun `FAILED cannot transition to DOWNLOADING`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.DOWNLOADING))
    }

    @Test
    fun `FAILED cannot transition to DOWNLOADED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.DOWNLOADED))
    }

    @Test
    fun `FAILED cannot transition to FAILED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.FAILED))
    }

    @Test
    fun `FAILED cannot transition to CANCELED`() {
        assertFalse(SongStateMachine.canTransition(DownloadStatus.FAILED, DownloadStatus.CANCELED))
    }

    @Test
    fun `DOWNLOADED cannot transition to any status`() {
        for (target in DownloadStatus.entries) {
            assertFalse(
                "DOWNLOADED -> $target should be illegal",
                SongStateMachine.canTransition(DownloadStatus.DOWNLOADED, target),
            )
        }
    }

    // ── requireTransition ──────────────────────────────────────────

    @Test
    fun `requireTransition returns target on legal transition`() {
        val result = SongStateMachine.requireTransition(DownloadStatus.PENDING, DownloadStatus.QUEUED)
        assertEquals(DownloadStatus.QUEUED, result)
    }

    @Test(expected = IllegalStateTransition::class)
    fun `requireTransition throws IllegalStateTransition on illegal transition`() {
        SongStateMachine.requireTransition(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING)
    }

    @Test(expected = IllegalStateTransition::class)
    fun `requireTransition throws on DOWNLOADED to PENDING`() {
        SongStateMachine.requireTransition(DownloadStatus.DOWNLOADED, DownloadStatus.PENDING)
    }

    @Test
    fun `IllegalStateTransition has descriptive message`() {
        try {
            SongStateMachine.requireTransition(DownloadStatus.PENDING, DownloadStatus.DOWNLOADING)
        } catch (e: IllegalStateTransition) {
            assertEquals("Illegal download status transition: PENDING -> DOWNLOADING", e.message)
        }
    }

    // ── legalTargets ───────────────────────────────────────────────

    @Test
    fun `legalTargets for PENDING returns set containing QUEUED`() {
        assertEquals(setOf(DownloadStatus.QUEUED), SongStateMachine.legalTargets(DownloadStatus.PENDING))
    }

    @Test
    fun `legalTargets for QUEUED returns DOWNLOADING and PENDING`() {
        assertEquals(
            setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING),
            SongStateMachine.legalTargets(DownloadStatus.QUEUED),
        )
    }

    @Test
    fun `legalTargets for DOWNLOADING returns four targets`() {
        assertEquals(
            setOf(
                DownloadStatus.DOWNLOADED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELED,
                DownloadStatus.PENDING,
            ),
            SongStateMachine.legalTargets(DownloadStatus.DOWNLOADING),
        )
    }

    @Test
    fun `legalTargets for CANCELED returns PENDING`() {
        assertEquals(setOf(DownloadStatus.PENDING), SongStateMachine.legalTargets(DownloadStatus.CANCELED))
    }

    @Test
    fun `legalTargets for FAILED returns QUEUED`() {
        assertEquals(setOf(DownloadStatus.QUEUED), SongStateMachine.legalTargets(DownloadStatus.FAILED))
    }

    @Test
    fun `legalTargets for DOWNLOADED returns empty set`() {
        assertEquals(emptySet<DownloadStatus>(), SongStateMachine.legalTargets(DownloadStatus.DOWNLOADED))
    }

    // ── round-trip / chain transitions ────────────────────────────

    @Test
    fun `happy path PENDING to QUEUED to DOWNLOADING to DOWNLOADED`() {
        var status = DownloadStatus.PENDING
        status = SongStateMachine.requireTransition(status, DownloadStatus.QUEUED)
        assertEquals(DownloadStatus.QUEUED, status)
        status = SongStateMachine.requireTransition(status, DownloadStatus.DOWNLOADING)
        assertEquals(DownloadStatus.DOWNLOADING, status)
        status = SongStateMachine.requireTransition(status, DownloadStatus.DOWNLOADED)
        assertEquals(DownloadStatus.DOWNLOADED, status)
    }

    @Test
    fun `retry path PENDING to QUEUED to DOWNLOADING to FAILED then retry to DOWNLOADED`() {
        var status = DownloadStatus.PENDING
        status = SongStateMachine.requireTransition(status, DownloadStatus.QUEUED)
        status = SongStateMachine.requireTransition(status, DownloadStatus.DOWNLOADING)
        status = SongStateMachine.requireTransition(status, DownloadStatus.FAILED)
        assertEquals(DownloadStatus.FAILED, status)
        status = SongStateMachine.requireTransition(status, DownloadStatus.QUEUED)
        status = SongStateMachine.requireTransition(status, DownloadStatus.DOWNLOADING)
        status = SongStateMachine.requireTransition(status, DownloadStatus.DOWNLOADED)
        assertEquals(DownloadStatus.DOWNLOADED, status)
    }

    @Test
    fun `cancel path PENDING to QUEUED to DOWNLOADING to CANCELED to PENDING`() {
        var status = DownloadStatus.PENDING
        status = SongStateMachine.requireTransition(status, DownloadStatus.QUEUED)
        status = SongStateMachine.requireTransition(status, DownloadStatus.DOWNLOADING)
        status = SongStateMachine.requireTransition(status, DownloadStatus.CANCELED)
        assertEquals(DownloadStatus.CANCELED, status)
        status = SongStateMachine.requireTransition(status, DownloadStatus.PENDING)
        assertEquals(DownloadStatus.PENDING, status)
    }

    @Test
    fun `crash recovery path QUEUED to PENDING`() {
        var status = DownloadStatus.QUEUED
        status = SongStateMachine.requireTransition(status, DownloadStatus.PENDING)
        assertEquals(DownloadStatus.PENDING, status)
    }

    @Test
    fun `crash recovery path DOWNLOADING to PENDING`() {
        var status = DownloadStatus.DOWNLOADING
        status = SongStateMachine.requireTransition(status, DownloadStatus.PENDING)
        assertEquals(DownloadStatus.PENDING, status)
    }
}
