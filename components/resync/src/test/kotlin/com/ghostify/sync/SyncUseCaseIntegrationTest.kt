package com.ghostify.sync

import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-066 (full cycle) and T-067 (transactional rollback / network failure).
 *
 * The full download/playback cycle requires a device for the real spotdl run;
 * here the download step is simulated by the in-memory + temp-dir harness so
 * the diff assertions are exact.
 */
class SyncUseCaseIntegrationTest {

    @TempDir
    lateinit var temp: File

    // --- T-066 ---------------------------------------------------------------

    @Test
    fun `T-066 full cycle sync download edit resync matches expectations`() = runBlocking {
        val h = TestHarness(temp)
        h.seedPlaylist()

        // 1) Initial sync: A + B added.
        h.fetcher.setTracks(FakeFetcher.track(0, "sp-a", "A"), FakeFetcher.track(1, "sp-b", "B"))
        h.useCase.syncPlaylist("pl1")
        assertEquals(listOf("sp-a", "sp-b"), h.songDao.getSongsForPlaylist("pl1").map { it.spotifyId })

        // 2) Download A + B to completion (simulated spotdl run).
        for (song in h.songDao.getSongsForPlaylist("pl1")) {
            val path = h.files.writeFile("Artist - ${song.title}.mp3")
            h.songDao.update(song.copy(status = SongStatus.DOWNLOADED, filePath = path))
        }
        assertTrue(h.songDao.getSongsForPlaylist("pl1").all { it.status == SongStatus.DOWNLOADED })

        // 3) Edit on Spotify: A removed, C added, B kept.
        h.fetcher.setTracks(FakeFetcher.track(0, "sp-b", "B"), FakeFetcher.track(1, "sp-c", "C"))
        val result = h.useCase.syncPlaylist("pl1")

        val songs = h.songDao.getSongsForPlaylist("pl1")
        assertEquals(listOf("sp-b", "sp-c"), songs.map { it.spotifyId }, "diff must add C and remove A")

        val b = songs.first { it.spotifyId == "sp-b" }
        assertEquals(SongStatus.DOWNLOADED, b.status, "existing downloaded track kept")
        assertTrue(h.files.exists(b.filePath!!), "B's file kept on disk")

        val c = songs.first { it.spotifyId == "sp-c" }
        assertEquals(SongStatus.PENDING, c.status, "newly added track pending")
        assertTrue(!h.files.file("Artist - A.mp3").exists(), "A's file deleted")

        // 4) Download C, then re-sync is a no-op diff.
        val cPath = h.files.writeFile("Artist - C.mp3")
        h.songDao.update(c.copy(status = SongStatus.DOWNLOADED, filePath = cPath))
        val final = h.useCase.syncPlaylist("pl1")
        assertEquals(listOf("sp-b", "sp-c"), h.songDao.getSongsForPlaylist("pl1").map { it.spotifyId })
        assertEquals(0, final.added)
        assertEquals(0, final.removed)
        assertEquals(0, final.requeued)
        assertTrue(h.songDao.getSongsForPlaylist("pl1").all { it.status == SongStatus.DOWNLOADED })
        assertEquals(2, h.playlistDao.getById("pl1")!!.trackCount)
    }

    // --- T-067 -----------------------------------------------------------------

    @Test
    fun `T-067 network failure mid-fetch leaves database unchanged`() = runBlocking {
        val h = TestHarness(temp)
        h.seedPlaylist()
        val filePath = h.files.writeFile("Artist - Stable.mp3")
        h.seedSong(id = "s1", spotifyId = "sp-1", title = "Stable", filePath = filePath)
        h.seedSong(id = "s2", spotifyId = "sp-2", title = "Pending", status = SongStatus.PENDING)
        h.fetcher.throwOnFetch = RuntimeException("network down")

        val error = assertFailsWith<SyncException.Network> {
            h.useCase.syncPlaylist("pl1")
        }
        assertEquals("spot-pl", error.spotifyId)

        // Nothing written anywhere: rows, statuses, playlist header, disk, queue.
        assertEquals(listOf("s1", "s2"), h.songDao.getSongsForPlaylist("pl1").map { it.id })
        assertEquals(SongStatus.DOWNLOADED, h.songDao.getById("s1")!!.status)
        assertEquals(SongStatus.PENDING, h.songDao.getById("s2")!!.status)
        assertEquals(null, h.playlistDao.getById("pl1")!!.lastSyncedAt, "playlist meta must not change")
        assertTrue(h.files.exists(filePath), "files must not be touched")
        assertEquals(0, h.enqueuer.calls.size)

        // Lock is released: a subsequent successful sync works.
        h.fetcher.throwOnFetch = null
        h.useCase.syncPlaylist("pl1")
        assertEquals(1_000L, h.playlistDao.getById("pl1")!!.lastSyncedAt)
    }

    @Test
    fun `T-067 mid-write failure rolls the whole transaction back`() = runBlocking {
        val h = TestHarness(temp)
        h.seedPlaylist()
        h.seedSong(id = "s1", spotifyId = "sp-1", title = "Keep", filePath = h.files.writeFile("Artist - Keep.mp3"))
        h.fetcher.setTracks(
            FakeFetcher.track(0, "sp-1", "Keep"),
            FakeFetcher.track(1, "sp-new", "New"),
        )
        h.songDao.failOnInsertAll = true // insertAll throws mid-batch

        assertFailsWith<IllegalStateException> { h.useCase.syncPlaylist("pl1") }

        // The transaction must roll back: no partial insert, meta unchanged.
        assertEquals(listOf("sp-1"), h.songDao.getSongsForPlaylist("pl1").map { it.spotifyId })
        assertEquals(SongStatus.DOWNLOADED, h.songDao.getById("s1")!!.status)
        assertEquals(null, h.playlistDao.getById("pl1")!!.lastSyncedAt)
        assertEquals(0, h.enqueuer.calls.size, "no enqueue after a rolled-back sync")

        // Recovered sync works once the failure clears.
        h.songDao.failOnInsertAll = false
        h.useCase.syncPlaylist("pl1")
        assertEquals(listOf("sp-1", "sp-new"), h.songDao.getSongsForPlaylist("pl1").map { it.spotifyId })
    }
}
