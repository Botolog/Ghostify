package xyz.botolog.ghostify.data.repo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.entity.PlaylistEntity
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.file.FileNames

/**
 * Deleting a playlist must take its songs, its media and its player state with it — and must
 * leave every other playlist completely untouched, including one that happens to hold the very
 * same track (and therefore the very same file).
 */
class DeletePlaylistUseCaseTest {

    private lateinit var playlistDao: PlaylistDao
    private lateinit var songDao: SongDao
    private lateinit var files: RecordingMediaFiles
    private lateinit var playback: RecordingPlaybackReset
    private lateinit var useCase: DeletePlaylistUseCase

    private val survivingSongs = mutableListOf<SongEntity>()
    private var transactionCount = 0
    private var playlistDeleted = false
    private var songsOfDeletedPlaylist: List<SongEntity> = emptyList()

    @Before
    fun setUp() {
        survivingSongs.clear()
        transactionCount = 0
        playlistDao = mockk(relaxed = true)
        songDao = mockk(relaxed = true)
        coEvery { playlistDao.getById(PLAYLIST_ID) } answers {
            if (playlistDeleted) null else playlist()
        }
        coEvery { songDao.getSongsForPlaylist(PLAYLIST_ID) } answers {
            if (playlistDeleted) emptyList() else songsOfDeletedPlaylist
        }
        coEvery { songDao.allFilePaths() } answers {
            survivingSongs.mapNotNull { it.filePath }
        }
        files = RecordingMediaFiles()
        playback = RecordingPlaybackReset()
        useCase = DeletePlaylistUseCase(
            playlistDao = playlistDao,
            songDao = songDao,
            transactions = countingTransactions(),
            files = files,
            playback = playback,
        )
    }

    @Test
    fun deletesSongsAndPlaylistInOneTransaction() = runTest {
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha"), song("s2", "Bravo"))

        val result = useCase.delete(PLAYLIST_ID)

        assertTrue(result.playlistExisted)
        assertEquals(2, result.songsDeleted)
        assertEquals(1, transactionCount)
        coVerify(exactly = 1) { songDao.deleteSongsForPlaylist(PLAYLIST_ID) }
        coVerify(exactly = 1) { playlistDao.deleteById(PLAYLIST_ID) }
        coVerify(exactly = 0) { songDao.deleteBySpotifyIds(any(), any()) }
    }

    @Test
    fun deletesMediaRecordedPathAndCurrentRootPath() = runTest {
        val currentRoot = "/storage/emulated/0/Android/data/xyz.botolog.ghostify/files/Music"
        files.root = currentRoot
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha", filePath = "/old/location/Alpha.mp3"))

        val result = useCase.delete(PLAYLIST_ID)

        assertEquals(
            listOf("/old/location/Alpha.mp3", "$currentRoot/Artist - Alpha.mp3"),
            files.requestedPaths,
        )
        assertEquals(2, result.filesRemoved)
        assertEquals(0, result.filesRetained)
    }

    @Test
    fun keepsMediaStillOwnedByAnotherPlaylist() = runTest {
        val shared = "/music/Artist - Alpha.mp3"
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha", filePath = shared))
        survivingSongs += song("other", "Alpha", filePath = shared, playlistId = "other-playlist")

        val result = useCase.delete(PLAYLIST_ID)

        assertEquals(emptyList<String>(), files.requestedPaths)
        assertEquals(0, result.filesRemoved)
        assertEquals(1, result.filesRetained)
    }

    @Test
    fun deletesEachSharedPathAtMostOnceAndOnlyOnce() = runTest {
        files.root = "/music"
        songsOfDeletedPlaylist = listOf(
            song("s1", "Alpha", filePath = "/music/Artist - Alpha.mp3"),
            song("s2", "Alpha", filePath = "/music/Artist - Alpha.mp3"),
        )

        val result = useCase.delete(PLAYLIST_ID)

        assertEquals(listOf("/music/Artist - Alpha.mp3"), files.requestedPaths)
        assertEquals(1, result.filesRemoved)
    }

    @Test
    fun resetsPlaybackForTheDeletedPlaylistOnly() = runTest {
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha"))
        playback.wasLoaded = true

        val result = useCase.delete(PLAYLIST_ID)

        assertEquals(listOf(PLAYLIST_ID), playback.resetIds)
        assertTrue(result.playbackStopped)
    }

    @Test
    fun reportsPlaybackUntouchedWhenAnotherPlaylistIsLoaded() = runTest {
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha"))
        playback.wasLoaded = false

        val result = useCase.delete(PLAYLIST_ID)

        assertEquals(listOf(PLAYLIST_ID), playback.resetIds)
        assertFalse(result.playbackStopped)
        assertTrue(result.songsDeleted == 1)
    }

    @Test
    fun redeletingAnAlreadyDeletedPlaylistIsANoOp() = runTest {
        playlistDeleted = true
        songsOfDeletedPlaylist = emptyList()

        val result = useCase.delete(PLAYLIST_ID)

        assertFalse(result.playlistExisted)
        assertEquals(0, result.songsDeleted)
        assertEquals(emptyList<String>(), files.requestedPaths)
        // The stale playlist cover is still swept; with no songs there are no song covers to clear.
        assertEquals(listOf(PLAYLIST_ID to emptyList<String>()), files.artworkCalls)
        assertEquals(listOf(PLAYLIST_ID), playback.resetIds)
    }

    @Test
    fun clearsArtworkForThePlaylistAndItsSongs() = runTest {
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha"), song("s2", "Bravo"))

        useCase.delete(PLAYLIST_ID)

        assertEquals(listOf(PLAYLIST_ID to listOf("s1", "s2")), files.artworkCalls)
    }

    @Test
    fun survivesUnresolvableAndFailingFilePaths() = runTest {
        files.root = "/music"
        files.throwsOnDelete = true
        songsOfDeletedPlaylist = listOf(song("s1", "Alpha", filePath = null))

        val result = useCase.delete(PLAYLIST_ID)

        assertEquals(0, result.filesRemoved)
        assertEquals(1, result.songsDeleted)
        coVerify(exactly = 1) { playlistDao.deleteById(PLAYLIST_ID) }
    }

    // ── Fakes ───────────────────────────────────────────────────────────────────────

    private fun countingTransactions() = object : TransactionRunner {
        override suspend fun <R> withinTransaction(block: suspend () -> R): R {
            transactionCount++
            return block()
        }
    }

    private fun playlist() = PlaylistEntity(
        id = PLAYLIST_ID,
        spotifyId = "spotify-playlist",
        name = "Playlist",
    )

    private fun song(
        id: String,
        title: String,
        filePath: String? = null,
        playlistId: String = PLAYLIST_ID,
    ) = SongEntity(
        id = id,
        playlistId = playlistId,
        spotifyId = "spotify-$id",
        title = title,
        artists = "Artist",
        filePath = filePath,
        status = if (filePath == null) SongStatus.PENDING else SongStatus.DOWNLOADED,
    )

    private companion object {
        const val PLAYLIST_ID = "playlist-1"
    }

    private class RecordingMediaFiles : PlaylistMediaFiles {
        var root: String = "/music"
        var throwsOnDelete: Boolean = false
        val requestedPaths = mutableListOf<String>()
        val artworkCalls = mutableListOf<Pair<String, List<String>>>()

        override fun candidatePaths(artists: String, title: String, recordedPath: String?): List<String> {
            val resolved = FileNames.trackFileName(artists, title)
            return listOfNotNull(recordedPath, "$root/$resolved")
        }

        override fun delete(path: String): Boolean {
            requestedPaths += path
            if (throwsOnDelete) throw IllegalStateException("disk gone")
            return true
        }

        override fun deleteArtwork(playlistId: String, songIds: List<String>) {
            artworkCalls += playlistId to songIds
        }
    }

    private class RecordingPlaybackReset : PlaylistPlaybackReset {
        var wasLoaded: Boolean = true
        val resetIds = mutableListOf<String>()

        override fun reset(playlistId: String): Boolean {
            resetIds += playlistId
            return wasLoaded
        }
    }
}
