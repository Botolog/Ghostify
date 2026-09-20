package xyz.botolog.ghostify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.CacheStats
import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.model.PlaylistPreview
import xyz.botolog.ghostify.ui.model.PlaylistStatus
import xyz.botolog.ghostify.ui.model.PlaylistUi
import xyz.botolog.ghostify.ui.model.QueueItem
import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi

class UiModelsTest {

    // ── CacheStats.sizeText ─────────────────────────────────────────────

    @Test
    fun sizeTextZeroBytesReturnsZeroKb() {
        val stats = CacheStats(fileCount = 0, sizeBytes = 0L)
        assertEquals("0 KB", stats.sizeText)
    }

    @Test
    fun sizeTextOneKbReturns1Kb() {
        val stats = CacheStats(fileCount = 1, sizeBytes = 1024L)
        assertEquals("1 KB", stats.sizeText)
    }

    @Test
    fun sizeTextHalfMbReturnsKb() {
        val stats = CacheStats(fileCount = 1, sizeBytes = 512 * 1024L)
        assertEquals("512 KB", stats.sizeText)
    }

    @Test
    fun sizeTextOneMbReturns1Point0Mb() {
        val stats = CacheStats(fileCount = 1, sizeBytes = 1024L * 1024L)
        assertEquals("1.0 MB", stats.sizeText)
    }

    @Test
    fun sizeText12Point3Mb() {
        val bytes = (12.3 * 1024.0 * 1024.0).toLong()
        val stats = CacheStats(fileCount = 5, sizeBytes = bytes)
        assertEquals("12.3 MB", stats.sizeText)
    }

    @Test
    fun sizeTextOneByteReturnsZeroKb() {
        val stats = CacheStats(fileCount = 1, sizeBytes = 1L)
        assertEquals("0 KB", stats.sizeText)
    }

    @Test
    fun sizeText1023BytesReturnsZeroKb() {
        val stats = CacheStats(fileCount = 1, sizeBytes = 1023L)
        assertEquals("0 KB", stats.sizeText)
    }

    @Test
    fun sizeTextLargeValue() {
        val bytes = 1024L * 1024L * 1024L // 1 GB
        val stats = CacheStats(fileCount = 100, sizeBytes = bytes)
        assertEquals("1024.0 MB", stats.sizeText)
    }

    @Test
    fun sizeTextJustUnder1Mb() {
        val bytes = 1024L * 1024L - 1 // 1048575 bytes
        val stats = CacheStats(fileCount = 1, sizeBytes = bytes)
        assertEquals("1023 KB", stats.sizeText)
    }

    // ── CacheStats data class ───────────────────────────────────────────

    @Test
    fun cacheStatsEquality() {
        val a = CacheStats(5, 1024L)
        val b = CacheStats(5, 1024L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun cacheStatsCopy() {
        val original = CacheStats(1, 2048L)
        val copied = original.copy(fileCount = 10)
        assertEquals(10, copied.fileCount)
        assertEquals(2048L, copied.sizeBytes)
    }

    // ── Bitrate enum ────────────────────────────────────────────────────

    @Test
    fun bitrateLowHasCorrectKbps() {
        assertEquals(128, Bitrate.LOW.kbps)
    }

    @Test
    fun bitrateMediumHasCorrectKbps() {
        assertEquals(192, Bitrate.MEDIUM.kbps)
    }

    @Test
    fun bitrateHighHasCorrectKbps() {
        assertEquals(320, Bitrate.HIGH.kbps)
    }

    @Test
    fun bitrateLowHasCorrectLabel() {
        assertEquals("128 kbps", Bitrate.LOW.label)
    }

    @Test
    fun bitrateMediumHasCorrectLabel() {
        assertEquals("192 kbps", Bitrate.MEDIUM.label)
    }

    @Test
    fun bitrateHighHasCorrectLabel() {
        assertEquals("320 kbps", Bitrate.HIGH.label)
    }

    // ── PlaylistUi ──────────────────────────────────────────────────────

    @Test
    fun playlistUiCreationWithDefaults() {
        val playlist = PlaylistUi(
            id = "1",
            name = "Test",
            owner = "Owner",
            coverUrl = null,
            trackCount = 10,
            downloadedCount = 5,
            status = PlaylistStatus.NEW,
        )
        assertEquals(PlaylistOrigin.SPOTIFY, playlist.origin)
        assertNull(playlist.progressPercent)
        assertNull(playlist.lastSyncedAt)
    }

    @Test
    fun playlistUiCreationWithAllFields() {
        val playlist = PlaylistUi(
            id = "1",
            name = "Test",
            owner = "Owner",
            coverUrl = "https://example.com/cover.jpg",
            trackCount = 100,
            downloadedCount = 50,
            status = PlaylistStatus.DOWNLOADING,
            origin = PlaylistOrigin.YOUTUBE,
            progressPercent = 75,
            lastSyncedAt = 1700000000000L,
        )
        assertEquals(PlaylistOrigin.YOUTUBE, playlist.origin)
        assertEquals(75, playlist.progressPercent)
        assertEquals(1700000000000L, playlist.lastSyncedAt)
    }

    @Test
    fun playlistUiEquality() {
        val a = PlaylistUi("1", "A", "O", null, null, 10, 0, PlaylistStatus.NEW)
        val b = PlaylistUi("1", "A", "O", null, null, 10, 0, PlaylistStatus.NEW)
        assertEquals(a, b)
    }

    @Test
    fun playlistUiCopyChangesStatus() {
        val original = PlaylistUi("1", "A", "O", null, null, 10, 0, PlaylistStatus.NEW)
        val copied = original.copy(status = PlaylistStatus.READY)
        assertEquals(PlaylistStatus.READY, copied.status)
        assertEquals(original.id, copied.id)
        assertEquals(original.name, copied.name)
    }

    // ── TrackUi ─────────────────────────────────────────────────────────

    @Test
    fun trackUiCreation() {
        val track = TrackUi(
            id = "t1",
            spotifyId = "sp1",
            title = "Title",
            artists = "Artist",
            album = "Album",
            durationMs = 180000L,
            status = SongStatus.DOWNLOADED,
            position = 0,
        )
        assertEquals("t1", track.id)
        assertEquals("sp1", track.spotifyId)
        assertEquals("Title", track.title)
        assertEquals("Artist", track.artists)
        assertEquals("Album", track.album)
        assertEquals(180000L, track.durationMs)
        assertEquals(SongStatus.DOWNLOADED, track.status)
        assertEquals(0, track.position)
    }

    @Test
    fun trackUiEquality() {
        val a = TrackUi("1", "s1", "T", "A", "B", 100L, SongStatus.PENDING, 0)
        val b = TrackUi("1", "s1", "T", "A", "B", 100L, SongStatus.PENDING, 0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun trackUiCopyChangesStatus() {
        val original = TrackUi("1", "s1", "T", "A", "B", 100L, SongStatus.PENDING, 0)
        val copied = original.copy(status = SongStatus.DOWNLOADED)
        assertEquals(SongStatus.DOWNLOADED, copied.status)
        assertEquals(original.id, copied.id)
    }

    @Test
    fun trackUiEmptyAlbum() {
        val track = TrackUi("1", "s1", "T", "A", "", 100L, SongStatus.PENDING, 0)
        assertEquals("", track.album)
    }

    @Test
    fun trackUiLargeDuration() {
        val track = TrackUi("1", "s1", "T", "A", "B", 3_600_000L, SongStatus.DOWNLOADED, 0)
        assertEquals(3_600_000L, track.durationMs)
    }

    // ── PlaylistPreview ─────────────────────────────────────────────────

    @Test
    fun playlistPreviewCreation() {
        val preview = PlaylistPreview(
            name = "My Playlist",
            owner = "Owner",
            coverUrl = "https://example.com/cover.jpg",
            trackCount = 25,
        )
        assertEquals("My Playlist", preview.name)
        assertEquals("Owner", preview.owner)
        assertEquals("https://example.com/cover.jpg", preview.coverUrl)
        assertEquals(25, preview.trackCount)
    }

    @Test
    fun playlistPreviewNullCoverUrl() {
        val preview = PlaylistPreview("P", "O", null, 0)
        assertNull(preview.coverUrl)
    }

    @Test
    fun playlistPreviewEquality() {
        val a = PlaylistPreview("P", "O", "url", 10)
        val b = PlaylistPreview("P", "O", "url", 10)
        assertEquals(a, b)
    }

    // ── QueueItem ───────────────────────────────────────────────────────

    @Test
    fun queueItemCreationWithDefaults() {
        val item = QueueItem(title = "Title", artist = "Artist", durationMs = 200000L)
        assertEquals(false, item.isCurrent)
    }

    @Test
    fun queueItemCreationWithIsCurrent() {
        val item = QueueItem(title = "T", artist = "A", durationMs = 100L, isCurrent = true)
        assertEquals(true, item.isCurrent)
    }

    @Test
    fun queueItemEquality() {
        val a = QueueItem("T", "A", 100L, false)
        val b = QueueItem("T", "A", 100L, false)
        assertEquals(a, b)
    }

    @Test
    fun queueItemCopyChangesIsCurrent() {
        val original = QueueItem("T", "A", 100L, false)
        val copied = original.copy(isCurrent = true)
        assertTrue(copied.isCurrent)
        assertEquals(original.title, copied.title)
    }

    // ── NowPlaying ──────────────────────────────────────────────────────

    @Test
    fun nowPlayingCreation() {
        val now = NowPlaying(
            title = "Title",
            artist = "Artist",
            album = "Album",
            coverUrl = "https://example.com/cover.jpg",
        )
        assertEquals("Title", now.title)
        assertEquals("Artist", now.artist)
        assertEquals("Album", now.album)
        assertEquals("https://example.com/cover.jpg", now.coverUrl)
    }

    @Test
    fun nowPlayingCoverUrlCanBeNull() {
        val now = NowPlaying("T", "A", "B", null)
        assertNull(now.coverUrl)
    }

    @Test
    fun nowPlayingCoverUrlCanBeByteArray() {
        val bytes = byteArrayOf(1, 2, 3)
        val now = NowPlaying("T", "A", "B", bytes)
        assertEquals(bytes, now.coverUrl)
    }

    @Test
    fun nowPlayingEquality() {
        val a = NowPlaying("T", "A", "B", "url")
        val b = NowPlaying("T", "A", "B", "url")
        assertEquals(a, b)
    }

    @Test
    fun nowPlayingEqualityWithNullCover() {
        val a = NowPlaying("T", "A", "B", null)
        val b = NowPlaying("T", "A", "B", null)
        assertEquals(a, b)
    }

    // ── SongStatus enum ─────────────────────────────────────────────────

    @Test
    fun songStatusHasFiveValues() {
        assertEquals(5, SongStatus.entries.size)
    }

    @Test
    fun playlistStatusHasFourValues() {
        assertEquals(4, PlaylistStatus.entries.size)
    }
}
