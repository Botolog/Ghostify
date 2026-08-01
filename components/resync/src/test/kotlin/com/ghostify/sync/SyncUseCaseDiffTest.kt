package com.ghostify.sync

import com.ghostify.data.model.SongStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-056..T-065 — the sync diff applied through the real use case against fake
 * stores and a real temp-dir file store.
 */
class SyncUseCaseDiffTest {

    @TempDir
    lateinit var temp: File

    private fun harness() = TestHarness(temp)

    // --- T-056 ---------------------------------------------------------------

    @Test
    fun `T-056 new tracks are inserted with PENDING`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        h.fetcher.setTracks(
            FakeFetcher.track(0, "sp-a", "Song A", "Artist A"),
            FakeFetcher.track(1, "sp-b", "Song B", "Artist B"),
        )

        val result = h.useCase.syncPlaylist("pl1")

        val songs = h.songDao.getSongsForPlaylist("pl1")
        assertEquals(2, songs.size)
        assertEquals(listOf("sp-a", "sp-b"), songs.map { it.spotifyId })
        assertEquals(listOf("Song A", "Song B"), songs.map { it.title })
        assertEquals(listOf("Artist A", "Artist B"), songs.map { it.artists })
        assertEquals(listOf(0, 1), songs.map { it.position })
        assertTrue(songs.all { it.status == SongStatus.PENDING }, "new rows must be PENDING")
        assertTrue(songs.all { it.filePath == null }, "new rows must have no file yet")
        assertEquals(2, result.added)
        assertEquals(1, h.enqueuer.calls.size, "sync must kick off the new downloads")
    }

    // --- T-057 ---------------------------------------------------------------

    @Test
    fun `T-057 removed track deletes file and row`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val filePath = h.files.writeFile("Artist - Gone.mp3")
        h.seedSong(id = "s1", spotifyId = "sp-gone", title = "Gone", filePath = filePath)
        h.fetcher.setTracks() // playlist now empty of this track

        val result = h.useCase.syncPlaylist("pl1")

        assertEquals(0, h.songDao.getSongsForPlaylist("pl1").size, "row must be deleted")
        assertTrue(!h.files.exists(filePath), "local file must be deleted from disk")
        assertEquals(1, result.removed)
        assertEquals(1, result.filesDeleted)
    }

    // --- T-058 ---------------------------------------------------------------

    @Test
    fun `T-058 unchanged tracks are left untouched, no re-download`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val filePath = h.files.writeFile("Artist - Stable.mp3")
        val before = h.seedSong(id = "s1", spotifyId = "sp-1", title = "Stable", filePath = filePath)
        val mtimeBefore = File(filePath).lastModified()
        h.fetcher.setTracks(FakeFetcher.track(0, "sp-1", "Stable"))

        h.useCase.syncPlaylist("pl1")

        val after = h.songDao.getById("s1")
        assertSame(before, after, "row must not be re-written when nothing changed")
        assertEquals(SongStatus.DOWNLOADED, after!!.status)
        assertEquals(filePath, after.filePath)
        assertEquals(mtimeBefore, File(filePath).lastModified(), "file must not be touched (no re-download)")
        assertEquals(0, h.enqueuer.calls.size, "nothing to download")
    }

    // --- T-059 ---------------------------------------------------------------

    @Test
    fun `T-059 track whose file is missing is re-queued to PENDING`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        h.seedSong(
            id = "s1", spotifyId = "sp-1", title = "Missing",
            filePath = File(temp, "Artist - Missing.mp3").absolutePath, // never created on disk
        )
        h.fetcher.setTracks(FakeFetcher.track(0, "sp-1", "Missing"))

        val result = h.useCase.syncPlaylist("pl1")

        val song = h.songDao.getById("s1")
        assertEquals(SongStatus.PENDING, song!!.status, "must be reset to PENDING")
        assertNull(song.filePath, "stale path must be dropped")
        assertEquals(1, result.requeued)
        assertEquals(1, h.enqueuer.calls.size, "missing file must be re-downloaded")
    }

    // --- T-060 ---------------------------------------------------------------

    @Test
    fun `T-060 reordering updates positions, not add-remove`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val fa = h.files.writeFile("Artist - A.mp3")
        val fb = h.files.writeFile("Artist - B.mp3")
        h.seedSong(id = "a", spotifyId = "sp-a", title = "A", position = 0, filePath = fa)
        h.seedSong(id = "b", spotifyId = "sp-b", title = "B", position = 1, filePath = fb)
        h.fetcher.setTracks(
            FakeFetcher.track(0, "sp-b", "B"),
            FakeFetcher.track(1, "sp-a", "A"),
        )

        val result = h.useCase.syncPlaylist("pl1")

        val songs = h.songDao.getSongsForPlaylist("pl1")
        assertEquals(2, songs.size, "no add/remove")
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
        assertEquals(listOf("sp-b", "sp-a"), songs.map { it.spotifyId })
        assertEquals(listOf(0, 1), songs.map { it.position })
        assertTrue(songs.all { it.status == SongStatus.DOWNLOADED })
        assertTrue(h.files.exists(fa) && h.files.exists(fb), "files kept")
    }

    // --- T-061 ---------------------------------------------------------------

    @Test
    fun `T-061 renamed track updates metadata but is not re-downloaded`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val filePath = h.files.writeFile("Old Artist - Old Title.mp3")
        h.seedSong(id = "s1", spotifyId = "sp-1", title = "Old Title", artists = "Old Artist", filePath = filePath)
        val mtimeBefore = File(filePath).lastModified()
        h.fetcher.setTracks(FakeFetcher.track(0, "sp-1", "New Title", "New Artist"))

        val result = h.useCase.syncPlaylist("pl1")

        val song = h.songDao.getById("s1")
        assertEquals("New Title", song!!.title)
        assertEquals("New Artist", song.artists)
        assertEquals(SongStatus.DOWNLOADED, song.status, "same spotify_id, file present -> still DOWNLOADED")
        assertEquals(filePath, song.filePath, "file path kept")
        assertEquals(mtimeBefore, File(filePath).lastModified(), "no re-download")
        assertEquals(1, result.metadataUpdated)
        assertEquals(0, h.enqueuer.calls.size)
    }

    // --- T-062 ---------------------------------------------------------------

    @Test
    fun `T-062 renamed playlist updates name cover and count`() = runBlocking {
        val h = harness()
        h.seedPlaylist(name = "Old Name", coverUrl = "http://old")
        h.fetcher.name = "New Name"
        h.fetcher.coverUrl = "http://new"
        h.fetcher.setTracks(
            FakeFetcher.track(0, "sp-a", "A"),
            FakeFetcher.track(1, "sp-b", "B"),
        )

        h.useCase.syncPlaylist("pl1")

        val playlist = h.playlistDao.getById("pl1")
        assertEquals("New Name", playlist!!.name)
        assertEquals("http://new", playlist.coverUrl)
        assertEquals(2, playlist.trackCount)
        assertEquals(1_000L, playlist.lastSyncedAt, "last_synced_at must be stamped")
    }

    // --- T-063 ---------------------------------------------------------------

    @Test
    fun `T-063 empty playlist deletes all files and shows 0 tracks`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val fa = h.files.writeFile("Artist - A.mp3")
        val fb = h.files.writeFile("Artist - B.mp3")
        h.seedSong(id = "a", spotifyId = "sp-a", title = "A", position = 0, filePath = fa)
        h.seedSong(id = "b", spotifyId = "sp-b", title = "B", position = 1, filePath = fb)
        h.fetcher.setTracks() // emptied on Spotify

        h.useCase.syncPlaylist("pl1")

        assertEquals(0, h.songDao.getSongsForPlaylist("pl1").size)
        assertEquals(0, h.playlistDao.getById("pl1")!!.trackCount)
        assertTrue(!h.files.exists(fa) && !h.files.exists(fb), "all local files deleted")
    }

    // --- T-064 ---------------------------------------------------------------

    @Test
    fun `T-064 re-sync never creates duplicate rows`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        h.fetcher.setTracks(
            FakeFetcher.track(0, "sp-a", "A"),
            FakeFetcher.track(1, "sp-b", "B"),
        )

        // First sync inserts 2 rows.
        h.useCase.syncPlaylist("pl1")
        assertEquals(2, h.songDao.getSongsForPlaylist("pl1").size)

        // Simulate both downloading to completion.
        for (song in h.songDao.getSongsForPlaylist("pl1")) {
            val path = h.files.writeFile("Artist - ${song.title}.mp3")
            h.songDao.update(song.copy(status = SongStatus.DOWNLOADED, filePath = path))
        }

        // Second sync: same Spotify list -> same 2 rows, no duplicates.
        val result = h.useCase.syncPlaylist("pl1")
        val songs = h.songDao.getSongsForPlaylist("pl1")
        assertEquals(2, songs.size, "UNIQUE(playlist_id, spotify_id) must hold")
        assertEquals(setOf("sp-a", "sp-b"), songs.map { it.spotifyId }.toSet())
        assertTrue(songs.all { it.status == SongStatus.DOWNLOADED }, "downloaded rows preserved")
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
    }

    // --- T-065 ---------------------------------------------------------------

    @Test
    fun `T-065 deleting an already-gone file does not crash, row still deleted`() = runBlocking {
        val h = harness()
        h.seedPlaylist()
        val ghostPath = File(temp, "Artist - Ghost.mp3").absolutePath // never existed
        h.seedSong(id = "s1", spotifyId = "sp-ghost", title = "Ghost", filePath = ghostPath)
        h.fetcher.setTracks()

        val result = h.useCase.syncPlaylist("pl1") // must not throw

        assertEquals(0, h.songDao.getSongsForPlaylist("pl1").size, "row still deleted")
        assertEquals(1, result.removed)
    }
}
