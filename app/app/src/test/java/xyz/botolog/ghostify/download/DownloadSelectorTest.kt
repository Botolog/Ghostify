package xyz.botolog.ghostify.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSelectorTest {

    private fun song(
        id: String = "s1",
        position: Int = 0,
        status: DownloadStatus = DownloadStatus.PENDING,
    ) = SongRecord(
        id = id,
        playlistId = "pl1",
        spotifyId = id,
        title = "Song $id",
        artists = "Artist",
        position = position,
        status = status,
    )

    @Test
    fun `returns only PENDING and FAILED songs`() {
        val songs = listOf(
            song("1", 0, DownloadStatus.PENDING),
            song("2", 1, DownloadStatus.DOWNLOADING),
            song("3", 2, DownloadStatus.DOWNLOADED),
            song("4", 3, DownloadStatus.FAILED),
            song("5", 4, DownloadStatus.QUEUED),
            song("6", 5, DownloadStatus.CANCELED),
        )
        val result = DownloadSelector.select(songs)
        assertEquals(2, result.size)
        assertTrue(result.all { it.status.isDownloadable })
    }

    @Test
    fun `empty list returns empty`() {
        assertEquals(emptyList<SongRecord>(), DownloadSelector.select(emptyList()))
    }

    @Test
    fun `all songs downloaded returns empty`() {
        val songs = listOf(
            song("1", 0, DownloadStatus.DOWNLOADED),
            song("2", 1, DownloadStatus.DOWNLOADED),
        )
        assertEquals(emptyList<SongRecord>(), DownloadSelector.select(songs))
    }

    @Test
    fun `preserves playlist order sorted by position`() {
        val songs = listOf(
            song("3", 2, DownloadStatus.PENDING),
            song("1", 0, DownloadStatus.FAILED),
            song("2", 1, DownloadStatus.PENDING),
        )
        val result = DownloadSelector.select(songs)
        assertEquals(3, result.size)
        assertEquals("1", result[0].id)
        assertEquals("2", result[1].id)
        assertEquals("3", result[2].id)
    }

    @Test
    fun `all pending returns all sorted`() {
        val songs = listOf(
            song("b", 1, DownloadStatus.PENDING),
            song("a", 0, DownloadStatus.PENDING),
        )
        val result = DownloadSelector.select(songs)
        assertEquals(2, result.size)
        assertEquals("a", result[0].id)
        assertEquals("b", result[1].id)
    }

    @Test
    fun `all failed returns all sorted`() {
        val songs = listOf(
            song("b", 1, DownloadStatus.FAILED),
            song("a", 0, DownloadStatus.FAILED),
        )
        val result = DownloadSelector.select(songs)
        assertEquals(2, result.size)
        assertEquals("a", result[0].id)
        assertEquals("b", result[1].id)
    }

    @Test
    fun `single downloadable song is returned`() {
        val songs = listOf(song("only", 5, DownloadStatus.PENDING))
        val result = DownloadSelector.select(songs)
        assertEquals(1, result.size)
        assertEquals("only", result[0].id)
    }

    @Test
    fun `single non-downloadable song returns empty`() {
        val songs = listOf(song("only", 0, DownloadStatus.DOWNLOADED))
        assertEquals(emptyList<SongRecord>(), DownloadSelector.select(songs))
    }

    @Test
    fun `mixed statuses sorts by position across the full list`() {
        val songs = listOf(
            song("3", 5, DownloadStatus.DOWNLOADED),
            song("1", 2, DownloadStatus.FAILED),
            song("4", 8, DownloadStatus.CANCELED),
            song("2", 3, DownloadStatus.PENDING),
            song("5", 1, DownloadStatus.DOWNLOADING),
        )
        val result = DownloadSelector.select(songs)
        assertEquals(2, result.size)
        assertEquals("1", result[0].id)
        assertEquals("2", result[1].id)
    }
}
