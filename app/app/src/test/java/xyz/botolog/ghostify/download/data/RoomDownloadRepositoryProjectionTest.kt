package xyz.botolog.ghostify.download.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.db.dao.SongDownloadRow
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.download.DownloadStatus

/**
 * Verifies the lyrics-free `SongDownloadRow` projection maps to exactly the same
 * [xyz.botolog.ghostify.download.SongRecord] the full `SongEntity` did (the
 * `SongRecord` shape has no lyrics field, so only the payload columns matter).
 */
class RoomDownloadRepositoryProjectionTest {

    private val songDao = mockk<SongDao>(relaxed = true)
    private val playlistDao = mockk<PlaylistDao>(relaxed = true)
    private val repository = RoomDownloadRepository(songDao, playlistDao)

    @Test
    fun `projection produces the same SongRecord as the full entity`() = runTest {
        coEvery { songDao.getById("s1") } returns entity(lyrics = "a very large lyrics blob")
        coEvery { songDao.getDownloadRowsForPlaylist(PLAYLIST) } returns listOf(row())

        val fromEntity = repository.getSong("s1")
        val fromProjection = repository.songsFor(PLAYLIST).single()

        assertEquals(fromEntity, fromProjection)
    }

    @Test
    fun `projection maps every download-path field`() = runTest {
        coEvery { songDao.getDownloadRowsForPlaylist(PLAYLIST) } returns listOf(row())

        val record = repository.songsFor(PLAYLIST).single()

        assertEquals("s1", record.id)
        assertEquals(PLAYLIST, record.playlistId)
        assertEquals("sp1", record.spotifyId)
        assertEquals("Title", record.title)
        assertEquals("Artist", record.artists)
        assertEquals(7, record.position)
        assertEquals(DownloadStatus.DOWNLOADED, record.status)
        assertEquals("/music/f.mp3", record.filePath)
        assertNull(record.error)
        assertEquals("yt1", record.ytId)
        assertEquals("Album", record.album)
        assertEquals(12345L, record.durationMs)
        assertEquals("https://cover", record.coverUrl)
        assertEquals("/covers/c.jpg", record.coverArtLocalPath)
    }

    @Test
    fun `projection type carries no lyrics column`() {
        assertFalse(
            "the projection must not select lyrics",
            SongDownloadRow::class.java.declaredFields.any { it.name == "lyrics" },
        )
    }

    @Test
    fun `songsFor reads the lyrics-free projection query`() = runTest {
        coEvery { songDao.getDownloadRowsForPlaylist(PLAYLIST) } returns listOf(row())

        repository.songsFor(PLAYLIST)

        coVerify(exactly = 1) { songDao.getDownloadRowsForPlaylist(PLAYLIST) }
        coVerify(exactly = 0) { songDao.getSongsForPlaylist(any()) }
    }

    @Test
    fun `observeSongs maps the lyrics-free projection flow`() = runTest {
        every { songDao.observeDownloadRowsForPlaylist(PLAYLIST) } returns flowOf(listOf(row()))

        val records = repository.observeSongs(PLAYLIST).first()

        assertEquals(listOf("s1"), records.map { it.id })
        verify(exactly = 0) { songDao.observeSongsForPlaylist(any()) }
    }

    private fun entity(lyrics: String? = null) = SongEntity(
        id = "s1",
        playlistId = PLAYLIST,
        spotifyId = "sp1",
        title = "Title",
        artists = "Artist",
        album = "Album",
        durationMs = 12345,
        coverUrl = "https://cover",
        coverArtLocalPath = "/covers/c.jpg",
        ytId = "yt1",
        filePath = "/music/f.mp3",
        lyrics = lyrics,
        error = null,
        status = SongStatus.DOWNLOADED,
        position = 7,
    )

    private fun row() = SongDownloadRow(
        id = "s1",
        playlistId = PLAYLIST,
        spotifyId = "sp1",
        title = "Title",
        artists = "Artist",
        album = "Album",
        durationMs = 12345,
        coverUrl = "https://cover",
        coverArtLocalPath = "/covers/c.jpg",
        ytId = "yt1",
        filePath = "/music/f.mp3",
        error = null,
        status = SongStatus.DOWNLOADED,
        position = 7,
    )

    private companion object {
        const val PLAYLIST = "pl1"
    }
}
