package xyz.botolog.ghostify.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.PlaylistStatus as CanonicalPlaylistStatus
import xyz.botolog.ghostify.data.model.SongStatus as CanonicalSongStatus
import xyz.botolog.ghostify.player.core.RepeatMode as PlayerRepeatMode
import xyz.botolog.ghostify.player.core.Song as PlayerSong
import xyz.botolog.ghostify.player.core.SongStatus as PlayerSongStatus
import xyz.botolog.ghostify.python.PlaylistMetadata
import xyz.botolog.ghostify.python.PlaylistTrack
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.PlaylistPreview
import xyz.botolog.ghostify.ui.model.SongStatus as UiSongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.util.RepeatMode as UiRepeatMode
import xyz.botolog.ghostify.ui.viewmodel.bitrateFromKbps
import xyz.botolog.ghostify.ui.viewmodel.toKbpsString
import xyz.botolog.ghostify.ui.viewmodel.toPlayerSong
import xyz.botolog.ghostify.ui.viewmodel.toPreview
import xyz.botolog.ghostify.ui.viewmodel.toTrackUi
import xyz.botolog.ghostify.ui.viewmodel.toUi

class MappersTest {

    // ── CanonicalSongStatus.toUi ────────────────────────────────────────

    @Test
    fun songStatusToUiPendingMapsToPending() {
        assertEquals(UiSongStatus.PENDING, CanonicalSongStatus.PENDING.toUi())
    }

    @Test
    fun songStatusToUiQueuedMapsToQueued() {
        assertEquals(UiSongStatus.QUEUED, CanonicalSongStatus.QUEUED.toUi())
    }

    @Test
    fun songStatusToUiDownloadingMapsToDownloading() {
        assertEquals(UiSongStatus.DOWNLOADING, CanonicalSongStatus.DOWNLOADING.toUi())
    }

    @Test
    fun songStatusToUiDownloadedMapsToDownloaded() {
        assertEquals(UiSongStatus.DOWNLOADED, CanonicalSongStatus.DOWNLOADED.toUi())
    }

    @Test
    fun songStatusToUiFailedMapsToFailed() {
        assertEquals(UiSongStatus.FAILED, CanonicalSongStatus.FAILED.toUi())
    }

    @Test
    fun songStatusToUiCanceledMapsToPending() {
        assertEquals(UiSongStatus.PENDING, CanonicalSongStatus.CANCELED.toUi())
    }

    @Test
    fun songStatusToUiRemovedMapsToPending() {
        assertEquals(UiSongStatus.PENDING, CanonicalSongStatus.REMOVED.toUi())
    }

    // ── CanonicalPlaylistStatus.toUi ────────────────────────────────────

    @Test
    fun playlistStatusToUiNewMapsToNew() {
        assertEquals(
            xyz.botolog.ghostify.ui.model.PlaylistStatus.NEW,
            CanonicalPlaylistStatus.NEW.toUi(),
        )
    }

    @Test
    fun playlistStatusToUiReadyMapsToReady() {
        assertEquals(
            xyz.botolog.ghostify.ui.model.PlaylistStatus.READY,
            CanonicalPlaylistStatus.READY.toUi(),
        )
    }

    @Test
    fun playlistStatusToUiDownloadingMapsToDownloading() {
        assertEquals(
            xyz.botolog.ghostify.ui.model.PlaylistStatus.DOWNLOADING,
            CanonicalPlaylistStatus.DOWNLOADING.toUi(),
        )
    }

    @Test
    fun playlistStatusToUiErrorMapsToError() {
        assertEquals(
            xyz.botolog.ghostify.ui.model.PlaylistStatus.ERROR,
            CanonicalPlaylistStatus.ERROR.toUi(),
        )
    }

    // ── SongEntity.toTrackUi ────────────────────────────────────────────

    private fun songEntity(
        album: String? = "Test Album",
        status: CanonicalSongStatus = CanonicalSongStatus.PENDING,
    ) = SongEntity(
        id = "entity-1",
        playlistId = "pl-1",
        spotifyId = "spotify-1",
        title = "Test Title",
        artists = "Artist A, Artist B",
        album = album,
        durationMs = 210000,
        coverUrl = "https://example.com/cover.jpg",
        ytId = null,
        filePath = null,
        error = null,
        status = status,
        position = 0,
        addedAt = 1000L,
    )

    @Test
    fun songEntityToTrackUiMapsAllFields() {
        val entity = songEntity()
        val track = entity.toTrackUi()

        assertEquals("entity-1", track.id)
        assertEquals("spotify-1", track.spotifyId)
        assertEquals("Test Title", track.title)
        assertEquals("Artist A, Artist B", track.artists)
        assertEquals("Test Album", track.album)
        assertEquals(210000L, track.durationMs)
        assertEquals(UiSongStatus.PENDING, track.status)
        assertEquals(0, track.position)
        assertEquals(1000L, track.addedAt)
    }

    @Test
    fun songEntityToTrackUiNullAlbumBecomesEmpty() {
        val entity = songEntity(album = null)
        val track = entity.toTrackUi()
        assertEquals("", track.album)
    }

    @Test
    fun songEntityToTrackUiConvertsDurationToIntToLong() {
        val entity = songEntity().copy(durationMs = Int.MAX_VALUE)
        val track = entity.toTrackUi()
        assertEquals(Int.MAX_VALUE.toLong(), track.durationMs)
    }

    @Test
    fun songEntityToTrackUiZeroDuration() {
        val entity = songEntity().copy(durationMs = 0)
        val track = entity.toTrackUi()
        assertEquals(0L, track.durationMs)
    }

    @Test
    fun songEntityToTrackUiMapsDownloadedStatus() {
        val entity = songEntity(status = CanonicalSongStatus.DOWNLOADED)
        val track = entity.toTrackUi()
        assertEquals(UiSongStatus.DOWNLOADED, track.status)
    }

    @Test
    fun songEntityToTrackUiMapsCanceledStatusToPending() {
        val entity = songEntity(status = CanonicalSongStatus.CANCELED)
        val track = entity.toTrackUi()
        assertEquals(UiSongStatus.PENDING, track.status)
    }

    @Test
    fun songEntityToTrackUiMapsRemovedStatusToPending() {
        val entity = songEntity(status = CanonicalSongStatus.REMOVED)
        val track = entity.toTrackUi()
        assertEquals(UiSongStatus.PENDING, track.status)
    }

    @Test
    fun songEntityToTrackUiLargePosition() {
        val entity = songEntity().copy(position = 9999)
        val track = entity.toTrackUi()
        assertEquals(9999, track.position)
    }

    @Test
    fun songEntityToTrackUiEmptyTitle() {
        val entity = songEntity().copy(title = "")
        val track = entity.toTrackUi()
        assertEquals("", track.title)
    }

    @Test
    fun songEntityToTrackUiEmptyArtists() {
        val entity = songEntity().copy(artists = "")
        val track = entity.toTrackUi()
        assertEquals("", track.artists)
    }

    // ── SongEntity.toPlayerSong ─────────────────────────────────────────

    @Test
    fun songEntityToPlayerSongMapsAllFields() {
        val entity = songEntity().copy(
            filePath = "/music/song.mp3",
            coverUrl = "https://example.com/cover.jpg",
        )
        val song = entity.toPlayerSong()

        assertEquals("entity-1", song.id)
        assertEquals("Test Title", song.title)
        assertEquals("Artist A, Artist B", song.artists)
        assertEquals("Test Album", song.album)
        assertEquals(210000L, song.durationMs)
        assertEquals("/music/song.mp3", song.filePath)
        assertEquals(PlayerSongStatus.PENDING, song.status)
        assertEquals("https://example.com/cover.jpg", song.coverUrl)
    }

    @Test
    fun songEntityToPlayerSongNullAlbumBecomesEmpty() {
        val entity = songEntity(album = null)
        val song = entity.toPlayerSong()
        assertEquals("", song.album)
    }

    @Test
    fun songEntityToPlayerSongNullFilePath() {
        val entity = songEntity().copy(filePath = null)
        val song = entity.toPlayerSong()
        assertEquals(null, song.filePath)
    }

    @Test
    fun songEntityToPlayerSongMapsDownloadedStatus() {
        val entity = songEntity(status = CanonicalSongStatus.DOWNLOADED)
        val song = entity.toPlayerSong()
        assertEquals(PlayerSongStatus.DOWNLOADED, song.status)
    }

    @Test
    fun songEntityToPlayerSongMapsFailedStatus() {
        val entity = songEntity(status = CanonicalSongStatus.FAILED)
        val song = entity.toPlayerSong()
        assertEquals(PlayerSongStatus.FAILED, song.status)
    }

    @Test
    fun songEntityToPlayerSongNullCoverUrl() {
        val entity = songEntity().copy(coverUrl = null)
        val song = entity.toPlayerSong()
        assertEquals(null, song.coverUrl)
    }

    @Test
    fun songEntityToPlayerSongLargeDuration() {
        val entity = songEntity().copy(durationMs = 3_600_000)
        val song = entity.toPlayerSong()
        assertEquals(3_600_000L, song.durationMs)
    }

    // ── PlaylistMetadata.toPreview ──────────────────────────────────────

    @Test
    fun playlistMetadataToPreviewMapsAllFields() {
        val metadata = PlaylistMetadata(
            name = "My Playlist",
            owner = "Owner Name",
            coverUrl = "https://example.com/cover.jpg",
            description = "A description",
            trackCount = 42,
            tracks = emptyList(),
        )
        val preview = metadata.toPreview()

        assertEquals("My Playlist", preview.name)
        assertEquals("Owner Name", preview.owner)
        assertEquals("https://example.com/cover.jpg", preview.coverUrl)
        assertEquals(42, preview.trackCount)
    }

    @Test
    fun playlistMetadataToPreviewNullCoverUrl() {
        val metadata = PlaylistMetadata(
            name = "Test",
            owner = "Owner",
            coverUrl = null,
            description = null,
            trackCount = 0,
            tracks = emptyList(),
        )
        val preview = metadata.toPreview()
        assertEquals(null, preview.coverUrl)
    }

    @Test
    fun playlistMetadataToPreviewZeroTracks() {
        val metadata = PlaylistMetadata(
            name = "Empty",
            owner = "",
            coverUrl = null,
            description = null,
            trackCount = 0,
            tracks = emptyList(),
        )
        val preview = metadata.toPreview()
        assertEquals(0, preview.trackCount)
    }

    @Test
    fun playlistMetadataToPreviewLargeTrackCount() {
        val metadata = PlaylistMetadata(
            name = "Big",
            owner = "Owner",
            coverUrl = null,
            description = null,
            trackCount = 10000,
            tracks = emptyList(),
        )
        val preview = metadata.toPreview()
        assertEquals(10000, preview.trackCount)
    }

    @Test
    fun playlistMetadataToPreviewEmptyStrings() {
        val metadata = PlaylistMetadata(
            name = "",
            owner = "",
            coverUrl = null,
            description = null,
            trackCount = 0,
            tracks = emptyList(),
        )
        val preview = metadata.toPreview()
        assertEquals("", preview.name)
        assertEquals("", preview.owner)
    }

    // ── bitrateFromKbps ─────────────────────────────────────────────────

    @Test
    fun bitrateFromKbps128ReturnsLow() {
        assertEquals(Bitrate.LOW, bitrateFromKbps("128"))
    }

    @Test
    fun bitrateFromKbps192ReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps("192"))
    }

    @Test
    fun bitrateFromKbps320ReturnsHigh() {
        assertEquals(Bitrate.HIGH, bitrateFromKbps("320"))
    }

    @Test
    fun bitrateFromKbpsUnknownReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps("999"))
    }

    @Test
    fun bitrateFromKbpsEmptyStringReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps(""))
    }

    @Test
    fun bitrateFromKbpsRandomStringReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps("abc"))
    }

    @Test
    fun bitrateFromKbpsZeroReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps("0"))
    }

    @Test
    fun bitrateFromKbpsNegativeReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps("-128"))
    }

    @Test
    fun bitrateFromKbpsDecimalReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps("128.5"))
    }

    @Test
    fun bitrateFromKbpsWithWhitespaceReturnsMedium() {
        assertEquals(Bitrate.MEDIUM, bitrateFromKbps(" 128 "))
    }

    // ── Bitrate.toKbpsString ────────────────────────────────────────────

    @Test
    fun bitrateToKbpsStringLow() {
        assertEquals("128", Bitrate.LOW.toKbpsString())
    }

    @Test
    fun bitrateToKbpsStringMedium() {
        assertEquals("192", Bitrate.MEDIUM.toKbpsString())
    }

    @Test
    fun bitrateToKbpsStringHigh() {
        assertEquals("320", Bitrate.HIGH.toKbpsString())
    }

    @Test
    fun bitrateToKbpsStringRoundTrips() {
        for (bitrate in Bitrate.entries) {
            assertEquals(bitrate, bitrateFromKbps(bitrate.toKbpsString()))
        }
    }

    // ── Player RepeatMode.toUi ──────────────────────────────────────────

    @Test
    fun playerRepeatModeOffToUiOff() {
        assertEquals(UiRepeatMode.OFF, PlayerRepeatMode.OFF.toUi())
    }

    @Test
    fun playerRepeatModeOneToUiOne() {
        assertEquals(UiRepeatMode.ONE, PlayerRepeatMode.ONE.toUi())
    }

    @Test
    fun playerRepeatModeAllToUiAll() {
        assertEquals(UiRepeatMode.ALL, PlayerRepeatMode.ALL.toUi())
    }
}
