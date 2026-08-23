package xyz.botolog.ghostify.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryStateTest {

    // ── SongStatus enum ───────────────────────────────────────────────────

    @Test
    fun songStatusHasAllValues() {
        val expected = setOf("PENDING", "QUEUED", "DOWNLOADING", "DOWNLOADED", "FAILED", "REMOVED")
        assertEquals(expected, SongStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun songStatusValuesCountIsSix() {
        assertEquals(6, SongStatus.entries.size)
    }

    // ── PlaylistStatus enum ───────────────────────────────────────────────

    @Test
    fun playlistStatusHasAllValues() {
        val expected = setOf("NEW", "READY", "DOWNLOADING", "ERROR")
        assertEquals(expected, PlaylistStatus.entries.map { it.name }.toSet())
    }

    @Test
    fun playlistStatusValuesCountIsFour() {
        assertEquals(4, PlaylistStatus.entries.size)
    }

    // ── SongState ─────────────────────────────────────────────────────────

    @Test
    fun songStateCreation() {
        val state = SongState("s1", SongStatus.PENDING)
        assertEquals("s1", state.id)
        assertEquals(SongStatus.PENDING, state.status)
    }

    @Test
    fun songStateEquality() {
        val a = SongState("s1", SongStatus.DOWNLOADING)
        val b = SongState("s1", SongStatus.DOWNLOADING)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun songStateInequalityOnStatus() {
        val a = SongState("s1", SongStatus.PENDING)
        val b = SongState("s1", SongStatus.DOWNLOADED)
        assertNotEquals(a, b)
    }

    @Test
    fun songStateInequalityOnId() {
        val a = SongState("s1", SongStatus.PENDING)
        val b = SongState("s2", SongStatus.PENDING)
        assertNotEquals(a, b)
    }

    @Test
    fun songStateCopy() {
        val original = SongState("s1", SongStatus.QUEUED)
        val copied = original.copy(status = SongStatus.DOWNLOADED)
        assertEquals(SongStatus.DOWNLOADED, copied.status)
        assertEquals("s1", copied.id)
    }

    // ── PlaylistState ─────────────────────────────────────────────────────

    @Test
    fun playlistStateCreation() {
        val state = PlaylistState("p1", PlaylistStatus.NEW, 0)
        assertEquals("p1", state.id)
        assertEquals(PlaylistStatus.NEW, state.status)
        assertEquals(0, state.downloadedCount)
    }

    @Test
    fun playlistStateEquality() {
        val a = PlaylistState("p1", PlaylistStatus.READY, 5)
        val b = PlaylistState("p1", PlaylistStatus.READY, 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun playlistStateInequalityOnDownloadedCount() {
        val a = PlaylistState("p1", PlaylistStatus.READY, 3)
        val b = PlaylistState("p1", PlaylistStatus.READY, 5)
        assertNotEquals(a, b)
    }

    // ── SongReset ─────────────────────────────────────────────────────────

    @Test
    fun songResetCreation() {
        val reset = SongReset("s1")
        assertEquals("s1", reset.id)
    }

    @Test
    fun songResetEquality() {
        assertEquals(SongReset("s1"), SongReset("s1"))
        assertNotEquals(SongReset("s1"), SongReset("s2"))
    }

    // ── PlaylistReset ─────────────────────────────────────────────────────

    @Test
    fun playlistResetCreation() {
        val reset = PlaylistReset("p1", PlaylistStatus.READY)
        assertEquals("p1", reset.id)
        assertEquals(PlaylistStatus.READY, reset.toStatus)
    }

    @Test
    fun playlistResetEquality() {
        assertEquals(
            PlaylistReset("p1", PlaylistStatus.NEW),
            PlaylistReset("p1", PlaylistStatus.NEW)
        )
    }

    @Test
    fun playlistResetInequalityOnTargetStatus() {
        assertNotEquals(
            PlaylistReset("p1", PlaylistStatus.NEW),
            PlaylistReset("p1", PlaylistStatus.READY)
        )
    }

    // ── RecoveryPlan ──────────────────────────────────────────────────────

    @Test
    fun recoveryPlanDefaultsToEmptyLists() {
        val plan = RecoveryPlan()
        assertTrue(plan.songResets.isEmpty())
        assertTrue(plan.playlistResets.isEmpty())
    }

    @Test
    fun recoveryPlanIsEmptyWhenBothListsEmpty() {
        val plan = RecoveryPlan(emptyList(), emptyList())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun recoveryPlanIsNotEmptyWhenSongResetsExist() {
        val plan = RecoveryPlan(
            songResets = listOf(SongReset("s1")),
            playlistResets = emptyList()
        )
        assertFalse(plan.isEmpty)
    }

    @Test
    fun recoveryPlanIsNotEmptyWhenPlaylistResetsExist() {
        val plan = RecoveryPlan(
            songResets = emptyList(),
            playlistResets = listOf(PlaylistReset("p1", PlaylistStatus.NEW))
        )
        assertFalse(plan.isEmpty)
    }

    @Test
    fun recoveryPlanEquality() {
        val a = RecoveryPlan(
            songResets = listOf(SongReset("s1")),
            playlistResets = listOf(PlaylistReset("p1", PlaylistStatus.READY))
        )
        val b = RecoveryPlan(
            songResets = listOf(SongReset("s1")),
            playlistResets = listOf(PlaylistReset("p1", PlaylistStatus.READY))
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── RecoveryReport ────────────────────────────────────────────────────

    @Test
    fun recoveryReportCreation() {
        val plan = RecoveryPlan(
            songResets = listOf(SongReset("s1"), SongReset("s2")),
            playlistResets = listOf(PlaylistReset("p1", PlaylistStatus.NEW))
        )
        val report = RecoveryReport(plan, 12345L)
        assertEquals(plan, report.plan)
        assertEquals(12345L, report.timestampMs)
        assertEquals(2, report.resetSongCount)
        assertEquals(1, report.resetPlaylistCount)
    }

    @Test
    fun recoveryReportDefaultsTimestampToCurrentTime() {
        val before = System.currentTimeMillis()
        val report = RecoveryReport(RecoveryPlan())
        val after = System.currentTimeMillis()
        assertTrue(report.timestampMs in before..after)
    }

    @Test
    fun recoveryReportResetCountsAreZeroForEmptyPlan() {
        val report = RecoveryReport(RecoveryPlan())
        assertEquals(0, report.resetSongCount)
        assertEquals(0, report.resetPlaylistCount)
    }

    @Test
    fun recoveryReportCountsMatchPlanLists() {
        val plan = RecoveryPlan(
            songResets = listOf(SongReset("a"), SongReset("b"), SongReset("c")),
            playlistResets = listOf(
                PlaylistReset("p1", PlaylistStatus.NEW),
                PlaylistReset("p2", PlaylistStatus.READY)
            )
        )
        val report = RecoveryReport(plan, 0L)
        assertEquals(3, report.resetSongCount)
        assertEquals(2, report.resetPlaylistCount)
    }

    @Test
    fun recoveryReportEquality() {
        val plan = RecoveryPlan(listOf(SongReset("s1")), emptyList())
        val a = RecoveryReport(plan, 100L)
        val b = RecoveryReport(plan, 100L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
