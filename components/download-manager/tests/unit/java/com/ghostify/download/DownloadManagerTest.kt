package com.ghostify.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the queue driven through [DownloadManager] + fakes
 * (T-042..T-055, except the device-only T-050).
 */
class DownloadManagerTest {

    private fun managerOf(
        repo: DownloadRepository,
        downloader: TrackDownloader,
        scope: CoroutineScope,
    ): Pair<DownloadManager, InlineDownloadExecutor> {
        val runner = DownloadQueueRunner(repo, downloader)
        val manager = DownloadManager(repo, runner, scope)
        val executor = InlineDownloadExecutor(manager, scope)
        manager.bindExecutor(executor)
        return manager to executor
    }

    // ---- T-042 / T-043 / T-045 through the manager -------------------------

    @Test
    fun `downloadAll picks only PENDING + FAILED, in order, never DOWNLOADED`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 5)
        val statuses = listOf(
            DownloadStatus.DOWNLOADED, // must never be re-enqueued
            DownloadStatus.PENDING,
            DownloadStatus.FAILED,
            DownloadStatus.PENDING,
            DownloadStatus.FAILED,
        )
        songs.forEachIndexed { i, s -> repo.seedSong(s.copy(status = statuses[i])) }

        val dl = ScriptedDownloader()
        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        // Only PENDING + FAILED were processed, in playlist order.
        assertEquals(listOf(songs[1].id, songs[2].id, songs[3].id, songs[4].id), dl.downloads)
        val byId = repo.songs("p1").associateBy { it.id }
        assertEquals(DownloadStatus.DOWNLOADED, byId[songs[0].id]?.status, "DOWNLOADED never re-enqueued")
        assertEquals(DownloadStatus.DOWNLOADED, byId[songs[1].id]?.status)
        assertEquals(DownloadStatus.DOWNLOADED, byId[songs[2].id]?.status)
        assertEquals(DownloadStatus.DOWNLOADED, byId[songs[3].id]?.status)
        assertEquals(DownloadStatus.DOWNLOADED, byId[songs[4].id]?.status)
    }

    // ---- T-047 ---------------------------------------------------------------

    @Test
    fun `partial failure leaves 9 downloaded, 1 failed and playlist READY`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 10)
        val dl = ScriptedDownloader()
        dl.behaviorFor(
            songs[3].id,
            ScriptedDownloader.Behavior(returning = TrackDownloadResult(songs[3].id, error = DownloadError.Generic("region blocked"))),
        )

        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        val statuses = repo.songs("p1").map { it.status }
        assertEquals(9, statuses.count { it == DownloadStatus.DOWNLOADED })
        assertEquals(1, statuses.count { it == DownloadStatus.FAILED })
        assertEquals(DownloadStatus.FAILED, repo.songs("p1")[3].status)
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
        assertEquals(songs.map { it.id }, dl.downloads, "every song processed exactly once, in order")
    }

    // ---- T-048 ---------------------------------------------------------------

    @Test
    fun `retry re-queues only FAILED tracks`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 3)
        val dl = ScriptedDownloader()
        // First attempt fails (transient); the retry attempt succeeds.
        dl.behaviorFor(
            songs[1].id,
            ScriptedDownloader.Behavior(
                failError = DownloadError.Generic("temporary glitch"),
                failuresBeforeSuccess = 1,
            ),
        )

        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()
        assertEquals(3, dl.downloads.size)
        assertEquals(listOf(DownloadStatus.DOWNLOADED, DownloadStatus.FAILED, DownloadStatus.DOWNLOADED),
            repo.songs("p1").map { it.status })

        assertTrue(manager.retry("p1"))
        advanceUntilIdle()

        assertEquals(4, dl.downloads.size, "exactly one additional attempt")
        assertEquals(songs[1].id, dl.downloads.last(), "only the FAILED track was re-downloaded")
        assertEquals(1, dl.downloads.count { it == songs[0].id }, "successful track never re-downloaded")
        assertEquals(1, dl.downloads.count { it == songs[2].id }, "successful track never re-downloaded")
        assertEquals(DownloadStatus.DOWNLOADED, repo.songs("p1")[1].status)
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }

    // ---- T-049 ---------------------------------------------------------------

    @Test
    fun `cancel marks current track CANCELED, keeps rest PENDING, no zombie work`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 5)
        val dl = BlockingDownloader()
        val (manager, executor) = managerOf(repo, dl, this)

        assertTrue(manager.downloadAll("p1"))
        runCurrent() // first track is now DOWNLOADING and blocked
        assertEquals(listOf(songs[0].id), dl.downloads)

        manager.cancel("p1")
        advanceUntilIdle()

        val byId = repo.songs("p1").associateBy { it.id }
        assertEquals(DownloadStatus.CANCELED, byId[songs[0].id]?.status, "in-flight track canceled")
        assertEquals(
            List(4) { DownloadStatus.PENDING },
            repo.songs("p1").drop(1).map { it.status },
            "not-yet-started tracks return to PENDING",
        )
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
        assertEquals(listOf(songs[0].id), dl.downloads, "no zombie downloads after cancel")
        assertFalse(manager.isRunning("p1"))
        assertFalse(executor.isRunning("p1"))
    }

    // ---- T-052 ---------------------------------------------------------------

    @Test
    fun `storage full fails with a clear message and does not crash the run`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 3)
        val dl = ScriptedDownloader()
        dl.behaviorFor(
            songs[0].id,
            ScriptedDownloader.Behavior(returning = TrackDownloadResult(songs[0].id, error = DownloadError.StorageFull())),
        )

        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        val failed = repo.songs("p1")[0]
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertNotNull(failed.error)
        assertTrue(failed.error!!.contains("storage", ignoreCase = true), "clear storage message, got: ${failed.error}")
        assertNull(failed.filePath)
        // The other tracks still downloaded; the playlist is READY, not ERROR.
        assertEquals(listOf(DownloadStatus.DOWNLOADED, DownloadStatus.DOWNLOADED), repo.songs("p1").drop(1).map { it.status })
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }

    @Test
    fun `a raw crash in the downloader is mapped to a FAILED track, not a crash`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 2)
        val dl = ScriptedDownloader()
        dl.behaviorFor(songs[0].id, ScriptedDownloader.Behavior(throwing = RuntimeException("boom")))

        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        assertEquals(DownloadStatus.FAILED, repo.songs("p1")[0].status)
        assertEquals(DownloadStatus.DOWNLOADED, repo.songs("p1")[1].status)
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }

    // ---- T-053 ---------------------------------------------------------------

    @Test
    fun `network switch fails cleanly then retry resumes the track`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 3)
        val dl = ScriptedDownloader()
        // First attempt dies mid-download (Network), the next attempt succeeds.
        dl.behaviorFor(
            songs[1].id,
            ScriptedDownloader.Behavior(
                failError = DownloadError.Network("Network connection lost"),
                failuresBeforeSuccess = 1,
            ),
        )

        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        // Failed cleanly: FAILED with network message, no partial file.
        val interrupted = repo.songs("p1")[1]
        assertEquals(DownloadStatus.FAILED, interrupted.status)
        assertTrue(interrupted.error?.contains("network", ignoreCase = true) == true)
        assertNull(interrupted.filePath, "no partial/corrupt file is recorded")

        // Retry resumes it.
        manager.retry("p1")
        advanceUntilIdle()
        assertEquals(DownloadStatus.DOWNLOADED, repo.songs("p1")[1].status)
        assertEquals("/store/${songs[1].id}.mp3", repo.songs("p1")[1].filePath)
        assertEquals(DownloadStatus.DOWNLOADED, repo.songs("p1")[0].status)
        assertEquals(DownloadStatus.DOWNLOADED, repo.songs("p1")[2].status)
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }

    // ---- T-054 ---------------------------------------------------------------

    @Test
    fun `duplicate downloadAll presses run a single run, second is a no-op`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 3)
        val dl = BlockingDownloader()
        val (manager, _) = managerOf(repo, dl, this)

        assertTrue(manager.downloadAll("p1"))
        runCurrent()
        assertEquals(listOf(songs[0].id), dl.downloads, "first run started")

        assertFalse(manager.downloadAll("p1"), "duplicate press is a no-op")
        assertFalse(manager.downloadAll("p1"))
        assertEquals(listOf(songs[0].id), dl.downloads, "no double-download while running")

        repeat(3) {
            dl.releaseAll()
            advanceUntilIdle()
        }
        advanceUntilIdle()
        assertEquals(3, dl.downloads.size, "exactly one full run happened")
        assertTrue(repo.songs("p1").all { it.status == DownloadStatus.DOWNLOADED })
    }

    // ---- T-055 ---------------------------------------------------------------

    @Test
    fun `two playlists download simultaneously without shared-state corruption`() = runTest {
        val repo = FakeDownloadRepository()
        val a = repo.seedPlaylist("pA", 3)
        val b = repo.seedPlaylist("pB", 3)
        val dl = BlockingDownloader()
        val (manager, _) = managerOf(repo, dl, this)

        assertTrue(manager.downloadAll("pA"))
        assertTrue(manager.downloadAll("pB"))

        repeat(3) {
            dl.releaseAll()
            advanceUntilIdle()
        }
        advanceUntilIdle()

        assertTrue(repo.songs("pA").all { it.status == DownloadStatus.DOWNLOADED })
        assertTrue(repo.songs("pB").all { it.status == DownloadStatus.DOWNLOADED })
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("pA"))
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("pB"))

        // Per-playlist order preserved and each song's file belongs to itself only.
        assertEquals(listOf("pA:s0", "pA:s1", "pA:s2"), dl.downloads.filter { it.startsWith("pA:") })
        assertEquals(listOf("pB:s0", "pB:s1", "pB:s2"), dl.downloads.filter { it.startsWith("pB:") })
        (a + b).forEach { s ->
            assertEquals("/store/${s.id}.mp3", repo.getSong(s.id)?.filePath, "no cross-playlist file corruption")
        }
    }

    // ---- T-051 (JVM-level recovery) ------------------------------------------

    @Test
    fun `crash leftovers DOWNLOADING and QUEUED are recovered to PENDING then downloaded`() = runTest {
        val repo = FakeDownloadRepository()
        val songs = repo.seedPlaylist("p1", 5)
        repo.seedSong(songs[1].copy(status = DownloadStatus.DOWNLOADING))
        repo.seedSong(songs[2].copy(status = DownloadStatus.QUEUED))

        val dl = ScriptedDownloader()
        val (manager, _) = managerOf(repo, dl, this)
        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        assertEquals(5, dl.downloads.size)
        assertTrue(repo.songs("p1").all { it.status == DownloadStatus.DOWNLOADED })
    }

    @Test
    fun `DownloadRecovery resets in-flight leftovers across all playlists`() = runTest {
        val repo = FakeDownloadRepository()
        val a = repo.seedPlaylist("pA", 2)
        val b = repo.seedPlaylist("pB", 2)
        repo.seedSong(a[0].copy(status = DownloadStatus.DOWNLOADING))
        repo.seedSong(a[1].copy(status = DownloadStatus.QUEUED))
        repo.seedSong(b[0].copy(status = DownloadStatus.CANCELED))
        repo.seedSong(b[1].copy(status = DownloadStatus.DOWNLOADED))

        DownloadRecovery(repo).recoverAll()

        assertEquals(DownloadStatus.PENDING, repo.getSong(a[0].id)?.status)
        assertEquals(DownloadStatus.PENDING, repo.getSong(a[1].id)?.status)
        assertEquals(DownloadStatus.PENDING, repo.getSong(b[0].id)?.status)
        assertEquals(DownloadStatus.DOWNLOADED, repo.getSong(b[1].id)?.status, "terminal states untouched")
    }

    @Test
    fun `downloadAll on a fully downloaded playlist is a no-op`() = runTest {
        val repo = FakeDownloadRepository()
        repo.seedPlaylist("p1", 3).forEach { s -> repo.seedSong(s.copy(status = DownloadStatus.DOWNLOADED)) }
        val dl = ScriptedDownloader()
        val (manager, _) = managerOf(repo, dl, this)

        assertTrue(manager.downloadAll("p1"))
        advanceUntilIdle()

        assertEquals(0, dl.downloads.size, "nothing to download")
        assertEquals(PlaylistStatus.READY, repo.getPlaylistStatus("p1"))
    }
}
