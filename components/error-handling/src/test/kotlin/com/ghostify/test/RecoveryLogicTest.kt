package com.ghostify.test

import com.ghostify.recovery.KilledProcessRecovery
import com.ghostify.recovery.KilledProcessRecoveryLogic
import com.ghostify.recovery.PlaylistStatus
import com.ghostify.recovery.PlaylistState
import com.ghostify.recovery.RecoveryDao
import com.ghostify.recovery.RecoveryPlan
import com.ghostify.recovery.SongStatus
import com.ghostify.recovery.SongState

/**
 * Core of T-159: after a forced kill the DB must be consistent, downloads recoverable,
 * and no status stuck at DOWNLOADING. The device part of T-159 runs this same logic
 * against a real Room database (see src/androidTest).
 */
object RecoveryLogicTest {

    private class FakeRecoveryDao(
        var songs: MutableList<SongState> = mutableListOf(),
        var playlists: MutableList<PlaylistState> = mutableListOf(),
    ) : RecoveryDao {
        var appliedPlans = 0
        override fun songsSnapshot(): List<SongState> = songs.toList()
        override fun playlistsSnapshot(): List<PlaylistState> = playlists.toList()
        override fun resetSongsToPending(ids: List<String>) {
            songs = songs.map { if (it.id in ids) it.copy(status = SongStatus.PENDING) else it }.toMutableList()
        }
        override fun resetPlaylistStatus(id: String, toStatus: PlaylistStatus) {
            playlists = playlists.map {
                if (it.id == id) it.copy(status = toStatus) else it
            }.toMutableList()
        }
        override fun applyPlan(plan: RecoveryPlan) {
            super.applyPlan(plan)
            appliedPlans++
        }
    }

    fun run() {
        Harness.test("T-159: DOWNLOADING song resets to PENDING (recoverable)") {
            val dao = FakeRecoveryDao(
                songs = mutableListOf(SongState("s1", SongStatus.DOWNLOADING)),
            )
            val report = KilledProcessRecovery(dao).recover()
            Harness.expectEq(1, report.resetSongCount)
            Harness.expectEq(SongStatus.PENDING, dao.songs.first().status)
        }

        Harness.test("T-159: QUEUED song resets to PENDING") {
            val dao = FakeRecoveryDao(songs = mutableListOf(SongState("s1", SongStatus.QUEUED)))
            KilledProcessRecovery(dao).recover()
            Harness.expectEq(SongStatus.PENDING, dao.songs.first().status)
        }

        Harness.test("T-159: DOWNLOADED/FAILED/PENDING/REMOVED songs are untouched") {
            val dao = FakeRecoveryDao(
                songs = mutableListOf(
                    SongState("a", SongStatus.DOWNLOADED),
                    SongState("b", SongStatus.FAILED),
                    SongState("c", SongStatus.PENDING),
                    SongState("d", SongStatus.REMOVED),
                ),
            )
            KilledProcessRecovery(dao).recover()
            Harness.expectEq(SongStatus.DOWNLOADED, dao.songs[0].status)
            Harness.expectEq(SongStatus.FAILED, dao.songs[1].status)
            Harness.expectEq(SongStatus.PENDING, dao.songs[2].status)
            Harness.expectEq(SongStatus.REMOVED, dao.songs[3].status)
        }

        Harness.test("T-159: playlist stuck DOWNLOADING becomes READY when it has downloads") {
            val dao = FakeRecoveryDao(
                songs = mutableListOf(SongState("s1", SongStatus.DOWNLOADED)),
                playlists = mutableListOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, downloadedCount = 1)),
            )
            KilledProcessRecovery(dao).recover()
            Harness.expectEq(PlaylistStatus.READY, dao.playlists.first().status)
        }

        Harness.test("T-159: playlist stuck DOWNLOADING becomes NEW when nothing was downloaded") {
            val dao = FakeRecoveryDao(
                playlists = mutableListOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, downloadedCount = 0)),
            )
            KilledProcessRecovery(dao).recover()
            Harness.expectEq(PlaylistStatus.NEW, dao.playlists.first().status)
        }

        Harness.test("T-159: NEW/READY/ERROR playlists are untouched") {
            val dao = FakeRecoveryDao(
                playlists = mutableListOf(
                    PlaylistState("a", PlaylistStatus.NEW, 0),
                    PlaylistState("b", PlaylistStatus.READY, 5),
                    PlaylistState("c", PlaylistStatus.ERROR, 2),
                ),
            )
            KilledProcessRecovery(dao).recover()
            Harness.expectEq(PlaylistStatus.NEW, dao.playlists[0].status)
            Harness.expectEq(PlaylistStatus.READY, dao.playlists[1].status)
            Harness.expectEq(PlaylistStatus.ERROR, dao.playlists[2].status)
        }

        Harness.test("T-159: mixed kill-state snapshot fully repaired in one pass") {
            val dao = FakeRecoveryDao(
                songs = mutableListOf(
                    SongState("s1", SongStatus.DOWNLOADING),
                    SongState("s2", SongStatus.QUEUED),
                    SongState("s3", SongStatus.DOWNLOADED),
                    SongState("s4", SongStatus.FAILED),
                ),
                playlists = mutableListOf(
                    PlaylistState("p1", PlaylistStatus.DOWNLOADING, 1),
                    PlaylistState("p2", PlaylistStatus.READY, 9),
                ),
            )
            val report = KilledProcessRecovery(dao).recover()
            Harness.expectEq(2, report.resetSongCount)
            Harness.expectEq(1, report.resetPlaylistCount)
            Harness.expectEq(SongStatus.PENDING, dao.songs[0].status)
            Harness.expectEq(SongStatus.PENDING, dao.songs[1].status)
            Harness.expectEq(SongStatus.DOWNLOADED, dao.songs[2].status)
            Harness.expectEq(SongStatus.FAILED, dao.songs[3].status)
            Harness.expectEq(PlaylistStatus.READY, dao.playlists[0].status)
            Harness.expectEq(PlaylistStatus.READY, dao.playlists[1].status)
        }

        Harness.test("T-159: recovery is idempotent — second run is a no-op") {
            val dao = FakeRecoveryDao(
                songs = mutableListOf(SongState("s1", SongStatus.DOWNLOADING)),
                playlists = mutableListOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, 0)),
            )
            KilledProcessRecovery(dao).recover()
            val second = KilledProcessRecovery(dao).recover()
            Harness.expectTrue(second.plan.isEmpty, "second recovery run should change nothing")
            Harness.expectEq(1, dao.appliedPlans, "second run should not re-apply the plan")
        }

        Harness.test("T-159: empty snapshot produces an empty plan") {
            val dao = FakeRecoveryDao()
            val report = KilledProcessRecovery(dao).recover()
            Harness.expectTrue(report.plan.isEmpty)
            Harness.expectEq(0, report.resetSongCount)
            Harness.expectEq(0, dao.appliedPlans)
        }

        Harness.test("T-159: pure logic never deletes or invents rows") {
            val songs = listOf(SongState("s1", SongStatus.DOWNLOADING))
            val playlists = listOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, 2))
            val plan = KilledProcessRecoveryLogic.plan(songs, playlists)
            Harness.expectEq(listOf("s1"), plan.songResets.map { it.id })
            Harness.expectEq(listOf("p1"), plan.playlistResets.map { it.id })
            Harness.expectEq(2, songs.size + playlists.size)
        }

        Harness.test("T-159: stability check passes after repair") {
            val dao = FakeRecoveryDao(
                songs = mutableListOf(SongState("s1", SongStatus.DOWNLOADING)),
                playlists = mutableListOf(PlaylistState("p1", PlaylistStatus.DOWNLOADING, 0)),
            )
            KilledProcessRecovery(dao).recover()
            Harness.expectTrue(
                KilledProcessRecoveryLogic.isStable(dao.songsSnapshot(), dao.playlistsSnapshot()),
                "repaired state should be stable",
            )
        }
    }
}
