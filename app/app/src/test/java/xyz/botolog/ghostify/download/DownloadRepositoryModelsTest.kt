package xyz.botolog.ghostify.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRepositoryModelsTest {

    // ── SongRecord defaults ────────────────────────────────────────

    @Test
    fun `SongRecord default filePath is null`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "sp1",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
        )
        assertNull(record.filePath)
    }

    @Test
    fun `SongRecord default error is null`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "sp1",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
        )
        assertNull(record.error)
    }

    @Test
    fun `SongRecord default ytId is null`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "sp1",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
        )
        assertNull(record.ytId)
    }

    // ── SongRecord.isYouTubeOrigin ─────────────────────────────────

    @Test
    fun `isYouTubeOrigin true when ytId equals spotifyId`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "dQw4w9WgXcQ",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
            ytId = "dQw4w9WgXcQ",
        )
        assertTrue(record.isYouTubeOrigin)
    }

    @Test
    fun `isYouTubeOrigin false when ytId is null`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "6rqhFgbbKwnb9MLmUQDhG6",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
            ytId = null,
        )
        assertFalse(record.isYouTubeOrigin)
    }

    @Test
    fun `isYouTubeOrigin false when ytId is blank`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "6rqhFgbbKwnb9MLmUQDhG6",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
            ytId = "",
        )
        assertFalse(record.isYouTubeOrigin)
    }

    @Test
    fun `isYouTubeOrigin false when ytId differs from spotifyId`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "6rqhFgbbKwnb9MLmUQDhG6",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
            ytId = "dQw4w9WgXcQ",
        )
        assertFalse(record.isYouTubeOrigin)
    }

    // ── SongRecord.sourceUrl ───────────────────────────────────────

    @Test
    fun `sourceUrl returns YouTube URL for YouTube origin`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "dQw4w9WgXcQ",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
            ytId = "dQw4w9WgXcQ",
        )
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", record.sourceUrl)
    }

    @Test
    fun `sourceUrl returns Spotify URI for Spotify origin`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "6rqhFgbbKwnb9MLmUQDhG6",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
        )
        assertEquals("spotify:track:6rqhFgbbKwnb9MLmUQDhG6", record.sourceUrl)
    }

    @Test
    fun `sourceUrl returns Spotify URI when ytId differs from spotifyId`() {
        val record = SongRecord(
            id = "s1",
            playlistId = "pl1",
            spotifyId = "6rqhFgbbKwnb9MLmUQDhG6",
            title = "Title",
            artists = "Artist",
            position = 0,
            status = DownloadStatus.PENDING,
            ytId = "dQw4w9WgXcQ",
        )
        assertEquals("spotify:track:6rqhFgbbKwnb9MLmUQDhG6", record.sourceUrl)
    }

    // ── SongRecord equality ────────────────────────────────────────

    @Test
    fun `two SongRecords with same fields are equal`() {
        val a = SongRecord(
            id = "s1", playlistId = "pl1", spotifyId = "sp1",
            title = "T", artists = "A", position = 0,
            status = DownloadStatus.PENDING,
        )
        val b = SongRecord(
            id = "s1", playlistId = "pl1", spotifyId = "sp1",
            title = "T", artists = "A", position = 0,
            status = DownloadStatus.PENDING,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two SongRecords with different id are not equal`() {
        val a = SongRecord(
            id = "s1", playlistId = "pl1", spotifyId = "sp1",
            title = "T", artists = "A", position = 0,
            status = DownloadStatus.PENDING,
        )
        val b = SongRecord(
            id = "s2", playlistId = "pl1", spotifyId = "sp1",
            title = "T", artists = "A", position = 0,
            status = DownloadStatus.PENDING,
        )
        assertFalse(a == b)
    }

    @Test
    fun `SongRecord copy with different status`() {
        val original = SongRecord(
            id = "s1", playlistId = "pl1", spotifyId = "sp1",
            title = "T", artists = "A", position = 0,
            status = DownloadStatus.PENDING,
        )
        val copy = original.copy(status = DownloadStatus.DOWNLOADED)
        assertEquals(DownloadStatus.DOWNLOADED, copy.status)
        assertEquals(DownloadStatus.PENDING, original.status)
    }

    // ── PlaylistRecord ─────────────────────────────────────────────

    @Test
    fun `PlaylistRecord stores id and status`() {
        val record = PlaylistRecord(id = "pl1", status = PlaylistStatus.READY)
        assertEquals("pl1", record.id)
        assertEquals(PlaylistStatus.READY, record.status)
    }

    @Test
    fun `PlaylistRecord equality`() {
        val a = PlaylistRecord(id = "pl1", status = PlaylistStatus.READY)
        val b = PlaylistRecord(id = "pl1", status = PlaylistStatus.READY)
        assertEquals(a, b)
    }

    @Test
    fun `PlaylistRecord copy changes status`() {
        val original = PlaylistRecord(id = "pl1", status = PlaylistStatus.NEW)
        val copy = original.copy(status = PlaylistStatus.DOWNLOADING)
        assertEquals(PlaylistStatus.DOWNLOADING, copy.status)
        assertEquals(PlaylistStatus.NEW, original.status)
    }
}
