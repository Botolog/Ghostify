package xyz.botolog.ghostify.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLogicTest {

    // ── empty inputs ──────────────────────────────────────────────────────

    @Test
    fun plan_emptySongsAndPlaylists_returnsEmptyPlan() {
        val plan = KilledProcessRecoveryLogic.plan(emptyList(), emptyList())
        assertTrue(plan.isEmpty)
    }

    // ── song resets ───────────────────────────────────────────────────────

    @Test
    fun plan_queuedSongIsReset() {
        val songs = listOf(SongState("s1", SongStatus.QUEUED))
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        assertEquals(1, plan.songResets.size)
        assertEquals("s1", plan.songResets[0].id)
    }

    @Test
    fun plan_downloadingSongIsReset() {
        val songs = listOf(SongState("s1", SongStatus.DOWNLOADING))
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        assertEquals(1, plan.songResets.size)
        assertEquals("s1", plan.songResets[0].id)
    }

    @Test
    fun plan_pendingSongIsNotReset() {
        val songs = listOf(SongState("s1", SongStatus.PENDING))
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        assertTrue(plan.songResets.isEmpty())
    }

    @Test
    fun plan_downloadedSongIsNotReset() {
        val songs = listOf(SongState("s1", SongStatus.DOWNLOADED))
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        assertTrue(plan.songResets.isEmpty())
    }

    @Test
    fun plan_failedSongIsNotReset() {
        val songs = listOf(SongState("s1", SongStatus.FAILED))
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        assertTrue(plan.songResets.isEmpty())
    }

    @Test
    fun plan_removedSongIsNotReset() {
        val songs = listOf(SongState("s1", SongStatus.REMOVED))
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        assertTrue(plan.songResets.isEmpty())
    }

    @Test
    fun plan_mixedSongStatuses() {
        val songs = listOf(
            SongState("s1", SongStatus.QUEUED),
            SongState("s2", SongStatus.DOWNLOADED),
            SongState("s3", SongStatus.DOWNLOADING),
            SongState("s4", SongStatus.FAILED),
            SongState("s5", SongStatus.PENDING),
            SongState("s6", SongStatus.REMOVED),
        )
        val plan = KilledProcessRecoveryLogic.plan(songs, emptyList())
        val resetIds = plan.songResets.map { it.id }.toSet()
        assertEquals(setOf("s1", "s3"), resetIds)
    }

    // ── playlist resets ───────────────────────────────────────────────────

    @Test
    fun plan_downloadingPlaylistWithDownloads_resetsToReady() {
        val playlists = listOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, downloadedCount = 3))
        val plan = KilledProcessRecoveryLogic.plan(emptyList(), playlists)
        assertEquals(1, plan.playlistResets.size)
        assertEquals("p1", plan.playlistResets[0].id)
        assertEquals(PlaylistStatus.READY, plan.playlistResets[0].toStatus)
    }

    @Test
    fun plan_downloadingPlaylistWithNoDownloads_resetsToNew() {
        val playlists = listOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, downloadedCount = 0))
        val plan = KilledProcessRecoveryLogic.plan(emptyList(), playlists)
        assertEquals(1, plan.playlistResets.size)
        assertEquals("p1", plan.playlistResets[0].id)
        assertEquals(PlaylistStatus.NEW, plan.playlistResets[0].toStatus)
    }

    @Test
    fun plan_newPlaylistIsNotReset() {
        val playlists = listOf(PlaylistState("p1", PlaylistStatus.NEW, downloadedCount = 0))
        val plan = KilledProcessRecoveryLogic.plan(emptyList(), playlists)
        assertTrue(plan.playlistResets.isEmpty())
    }

    @Test
    fun plan_readyPlaylistIsNotReset() {
        val playlists = listOf(PlaylistState("p1", PlaylistStatus.READY, downloadedCount = 5))
        val plan = KilledProcessRecoveryLogic.plan(emptyList(), playlists)
        assertTrue(plan.playlistResets.isEmpty())
    }

    @Test
    fun plan_errorPlaylistIsNotReset() {
        val playlists = listOf(PlaylistState("p1", PlaylistStatus.ERROR, downloadedCount = 0))
        val plan = KilledProcessRecoveryLogic.plan(emptyList(), playlists)
        assertTrue(plan.playlistResets.isEmpty())
    }

    // ── combined songs and playlists ──────────────────────────────────────

    @Test
    fun plan_mixedSongsAndPlaylists() {
        val songs = listOf(
            SongState("s1", SongStatus.QUEUED),
            SongState("s2", SongStatus.DOWNLOADED),
        )
        val playlists = listOf(
            PlaylistState("p1", PlaylistStatus.DOWNLOADING, downloadedCount = 2),
            PlaylistState("p2", PlaylistStatus.NEW, downloadedCount = 0),
        )
        val plan = KilledProcessRecoveryLogic.plan(songs, playlists)
        assertEquals(1, plan.songResets.size)
        assertEquals(1, plan.playlistResets.size)
        assertEquals("s1", plan.songResets[0].id)
        assertEquals("p1", plan.playlistResets[0].id)
        assertEquals(PlaylistStatus.READY, plan.playlistResets[0].toStatus)
    }

    // ── isStable ──────────────────────────────────────────────────────────

    @Test
    fun isStable_emptyStateReturnsTrue() {
        assertTrue(KilledProcessRecoveryLogic.isStable(emptyList(), emptyList()))
    }

    @Test
    fun isStable_allSongsStableReturnsTrue() {
        val songs = listOf(
            SongState("s1", SongStatus.PENDING),
            SongState("s2", SongStatus.DOWNLOADED),
            SongState("s3", SongStatus.FAILED),
        )
        assertTrue(KilledProcessRecoveryLogic.isStable(songs, emptyList()))
    }

    @Test
    fun isStable_queuedSongReturnsFalse() {
        val songs = listOf(SongState("s1", SongStatus.QUEUED))
        assertFalse(KilledProcessRecoveryLogic.isStable(songs, emptyList()))
    }

    @Test
    fun isStable_downloadingSongReturnsFalse() {
        val songs = listOf(SongState("s1", SongStatus.DOWNLOADING))
        assertFalse(KilledProcessRecoveryLogic.isStable(songs, emptyList()))
    }

    @Test
    fun isStable_downloadingPlaylistReturnsFalse() {
        val playlists = listOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, 0))
        assertFalse(KilledProcessRecoveryLogic.isStable(emptyList(), playlists))
    }

    @Test
    fun isStable_stablePlaylistsReturnsTrue() {
        val playlists = listOf(
            PlaylistState("p1", PlaylistStatus.NEW, 0),
            PlaylistState("p2", PlaylistStatus.READY, 5),
            PlaylistState("p3", PlaylistStatus.ERROR, 0),
        )
        assertTrue(KilledProcessRecoveryLogic.isStable(emptyList(), playlists))
    }

    // ── idempotency ───────────────────────────────────────────────────────

    @Test
    fun plan_isIdempotent() {
        val songs = listOf(
            SongState("s1", SongStatus.QUEUED),
            SongState("s2", SongStatus.DOWNLOADING),
        )
        val playlists = listOf(
            PlaylistState("p1", PlaylistStatus.DOWNLOADING, downloadedCount = 1),
        )
        val plan1 = KilledProcessRecoveryLogic.plan(songs, playlists)
        // After applying plan1, songs s1/s2 would be PENDING, p1 would be READY
        // Simulate post-recovery state
        val postSongs = listOf(
            SongState("s1", SongStatus.PENDING),
            SongState("s2", SongStatus.PENDING),
        )
        val postPlaylists = listOf(
            PlaylistState("p1", PlaylistStatus.READY, downloadedCount = 1),
        )
        val plan2 = KilledProcessRecoveryLogic.plan(postSongs, postPlaylists)
        assertTrue(plan2.isEmpty)
    }

    // ── RecoveryPlan.isEmpty ──────────────────────────────────────────────

    @Test
    fun recoveryPlan_isEmpty_trueWhenBothListsEmpty() {
        val plan = RecoveryPlan()
        assertTrue(plan.isEmpty)
    }

    @Test
    fun recoveryPlan_isEmpty_falseWhenSongResetsExist() {
        val plan = RecoveryPlan(songResets = listOf(SongReset("s1")))
        assertFalse(plan.isEmpty)
    }

    @Test
    fun recoveryPlan_isEmpty_falseWhenPlaylistResetsExist() {
        val plan = RecoveryPlan(
            playlistResets = listOf(PlaylistReset("p1", PlaylistStatus.NEW)),
        )
        assertFalse(plan.isEmpty)
    }
}
