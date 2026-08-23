package xyz.botolog.ghostify.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    private fun song(
        id: String = "s1",
        status: DownloadStatus = DownloadStatus.PENDING,
        error: String? = null,
    ) = SongRecord(
        id = id,
        playlistId = "pl1",
        spotifyId = id,
        title = "Song $id",
        artists = "Artist",
        position = 0,
        status = status,
        error = error,
    )

    // ── DownloadStatus properties ──────────────────────────────────

    @Test
    fun `PENDING is not terminal`() {
        assertFalse(DownloadStatus.PENDING.isTerminal)
    }

    @Test
    fun `QUEUED is not terminal`() {
        assertFalse(DownloadStatus.QUEUED.isTerminal)
    }

    @Test
    fun `DOWNLOADING is not terminal`() {
        assertFalse(DownloadStatus.DOWNLOADING.isTerminal)
    }

    @Test
    fun `DOWNLOADED is terminal`() {
        assertTrue(DownloadStatus.DOWNLOADED.isTerminal)
    }

    @Test
    fun `FAILED is terminal`() {
        assertTrue(DownloadStatus.FAILED.isTerminal)
    }

    @Test
    fun `CANCELED is terminal`() {
        assertTrue(DownloadStatus.CANCELED.isTerminal)
    }

    @Test
    fun `PENDING is not recoverable`() {
        assertFalse(DownloadStatus.PENDING.isRecoverable)
    }

    @Test
    fun `QUEUED is recoverable`() {
        assertTrue(DownloadStatus.QUEUED.isRecoverable)
    }

    @Test
    fun `DOWNLOADING is recoverable`() {
        assertTrue(DownloadStatus.DOWNLOADING.isRecoverable)
    }

    @Test
    fun `DOWNLOADED is not recoverable`() {
        assertFalse(DownloadStatus.DOWNLOADED.isRecoverable)
    }

    @Test
    fun `FAILED is not recoverable`() {
        assertFalse(DownloadStatus.FAILED.isRecoverable)
    }

    @Test
    fun `CANCELED is recoverable`() {
        assertTrue(DownloadStatus.CANCELED.isRecoverable)
    }

    @Test
    fun `PENDING is downloadable`() {
        assertTrue(DownloadStatus.PENDING.isDownloadable)
    }

    @Test
    fun `FAILED is downloadable`() {
        assertTrue(DownloadStatus.FAILED.isDownloadable)
    }

    @Test
    fun `QUEUED is not downloadable`() {
        assertFalse(DownloadStatus.QUEUED.isDownloadable)
    }

    @Test
    fun `DOWNLOADING is not downloadable`() {
        assertFalse(DownloadStatus.DOWNLOADING.isDownloadable)
    }

    @Test
    fun `DOWNLOADED is not downloadable`() {
        assertFalse(DownloadStatus.DOWNLOADED.isDownloadable)
    }

    @Test
    fun `CANCELED is not downloadable`() {
        assertFalse(DownloadStatus.CANCELED.isDownloadable)
    }

    // ── fromSongs — basic ──────────────────────────────────────────

    @Test
    fun `fromSongs empty list has total 0 percent 100`() {
        val result = DownloadProgressCalculator.fromSongs("pl1", emptyList())
        assertEquals("pl1", result.playlistId)
        assertEquals(0, result.total)
        assertEquals(0, result.done)
        assertEquals(100f, result.overallPercent, 0.001f)
        assertTrue(result.perSong.isEmpty())
    }

    @Test
    fun `fromSongs single pending song has percent 0`() {
        val result = DownloadProgressCalculator.fromSongs("pl1", listOf(song("s1")))
        assertEquals(1, result.total)
        assertEquals(0, result.done)
        assertEquals(0f, result.overallPercent, 0.001f)
    }

    @Test
    fun `fromSongs single downloaded song has percent 100`() {
        val result = DownloadProgressCalculator.fromSongs(
            "pl1",
            listOf(song("s1", DownloadStatus.DOWNLOADED)),
        )
        assertEquals(1, result.total)
        assertEquals(1, result.done)
        assertEquals(100f, result.overallPercent, 0.001f)
    }

    // ── fromSongs — percent calculation ────────────────────────────

    @Test
    fun `fromSongs half done is 50 percent`() {
        val songs = listOf(
            song("s1", DownloadStatus.DOWNLOADED),
            song("s2", DownloadStatus.PENDING),
        )
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertEquals(50f, result.overallPercent, 0.001f)
    }

    @Test
    fun `fromSongs two of three done is 66_67 percent`() {
        val songs = listOf(
            song("s1", DownloadStatus.DOWNLOADED),
            song("s2", DownloadStatus.FAILED),
            song("s3", DownloadStatus.PENDING),
        )
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        // 2/3 * 100 ≈ 66.667
        assertEquals(66.666664f, result.overallPercent, 0.01f)
    }

    @Test
    fun `FAILED counts as done`() {
        val songs = listOf(
            song("s1", DownloadStatus.FAILED),
            song("s2", DownloadStatus.DOWNLOADED),
        )
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertEquals(2, result.done)
        assertEquals(100f, result.overallPercent, 0.001f)
    }

    @Test
    fun `CANCELED counts as done`() {
        val songs = listOf(
            song("s1", DownloadStatus.CANCELED),
            song("s2", DownloadStatus.DOWNLOADED),
        )
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertEquals(2, result.done)
        assertEquals(100f, result.overallPercent, 0.001f)
    }

    @Test
    fun `QUEUED and DOWNLOADING do not count as done`() {
        val songs = listOf(
            song("s1", DownloadStatus.QUEUED),
            song("s2", DownloadStatus.DOWNLOADING),
            song("s3", DownloadStatus.PENDING),
        )
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertEquals(0, result.done)
        assertEquals(0f, result.overallPercent, 0.001f)
    }

    // ── fromSongs — default state and playlistStatus ───────────────

    @Test
    fun `default state is IDLE`() {
        val result = DownloadProgressCalculator.fromSongs("pl1", emptyList())
        assertEquals(DownloadRunState.IDLE, result.state)
    }

    @Test
    fun `running state sets playlistStatus to DOWNLOADING`() {
        val result = DownloadProgressCalculator.fromSongs(
            "pl1",
            emptyList(),
            state = DownloadRunState.RUNNING,
        )
        assertEquals(PlaylistStatus.DOWNLOADING, result.playlistStatus)
    }

    @Test
    fun `idle state sets playlistStatus to READY`() {
        val result = DownloadProgressCalculator.fromSongs(
            "pl1",
            emptyList(),
            state = DownloadRunState.IDLE,
        )
        assertEquals(PlaylistStatus.READY, result.playlistStatus)
    }

    @Test
    fun `completed state sets playlistStatus to READY`() {
        val result = DownloadProgressCalculator.fromSongs(
            "pl1",
            emptyList(),
            state = DownloadRunState.COMPLETED,
        )
        assertEquals(PlaylistStatus.READY, result.playlistStatus)
    }

    @Test
    fun `custom playlistStatus overrides default`() {
        val result = DownloadProgressCalculator.fromSongs(
            "pl1",
            emptyList(),
            state = DownloadRunState.IDLE,
            playlistStatus = PlaylistStatus.ERROR,
        )
        assertEquals(PlaylistStatus.ERROR, result.playlistStatus)
    }

    // ── fromSongs — perSong fractions ──────────────────────────────

    @Test
    fun `downloaded song without fraction gets 1f`() {
        val songs = listOf(song("s1", DownloadStatus.DOWNLOADED))
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertEquals(1f, result.perSong["s1"]!!.fraction, 0.001f)
    }

    @Test
    fun `non-downloaded song without fraction gets 0f`() {
        val songs = listOf(song("s1", DownloadStatus.PENDING))
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertEquals(0f, result.perSong["s1"]!!.fraction, 0.001f)
    }

    @Test
    fun `explicit fraction overrides default`() {
        val songs = listOf(song("s1", DownloadStatus.DOWNLOADING))
        val fractions = mapOf("s1" to 0.75f)
        val result = DownloadProgressCalculator.fromSongs("pl1", songs, fractions = fractions)
        assertEquals(0.75f, result.perSong["s1"]!!.fraction, 0.001f)
    }

    @Test
    fun `perSong preserves songId and status`() {
        val songs = listOf(song("s1", DownloadStatus.FAILED, error = "timeout"))
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        val progress = result.perSong["s1"]!!
        assertEquals("s1", progress.songId)
        assertEquals(DownloadStatus.FAILED, progress.status)
        assertEquals("timeout", progress.error)
    }

    @Test
    fun `perSong error is null when song has no error`() {
        val songs = listOf(song("s1", DownloadStatus.PENDING))
        val result = DownloadProgressCalculator.fromSongs("pl1", songs)
        assertNull(result.perSong["s1"]!!.error)
    }

    // ── fromSongs — multiple songs with mixed fractions ────────────

    @Test
    fun `multiple songs all fractions respected`() {
        val songs = listOf(
            song("s1", DownloadStatus.DOWNLOADED),
            song("s2", DownloadStatus.DOWNLOADING),
            song("s3", DownloadStatus.PENDING),
        )
        val fractions = mapOf("s2" to 0.4f)
        val result = DownloadProgressCalculator.fromSongs("pl1", songs, fractions = fractions)
        assertEquals(1f, result.perSong["s1"]!!.fraction, 0.001f)
        assertEquals(0.4f, result.perSong["s2"]!!.fraction, 0.001f)
        assertEquals(0f, result.perSong["s3"]!!.fraction, 0.001f)
    }

    // ── SongProgress data class ────────────────────────────────────

    @Test
    fun `SongProgress default fraction is 0f`() {
        val progress = SongProgress(
            songId = "s1",
            status = DownloadStatus.PENDING,
        )
        assertEquals(0f, progress.fraction, 0.001f)
    }

    @Test
    fun `SongProgress default error is null`() {
        val progress = SongProgress(
            songId = "s1",
            status = DownloadStatus.PENDING,
        )
        assertNull(progress.error)
    }

    // ── TrackDownloadResult ────────────────────────────────────────

    @Test
    fun `TrackDownloadResult isSuccess when filePath and no error`() {
        val result = TrackDownloadResult(songId = "s1", filePath = "/path/to/file.mp3")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `TrackDownloadResult is not success when error is set`() {
        val result = TrackDownloadResult(
            songId = "s1",
            error = DownloadError.Generic("fail"),
        )
        assertFalse(result.isSuccess)
    }

    @Test
    fun `TrackDownloadResult is not success when filePath is null`() {
        val result = TrackDownloadResult(songId = "s1")
        assertFalse(result.isSuccess)
    }

    @Test
    fun `TrackDownloadResult is not success when both filePath and error set`() {
        val result = TrackDownloadResult(
            songId = "s1",
            filePath = "/some/path",
            error = DownloadError.Generic("fail"),
        )
        assertFalse(result.isSuccess)
    }

    // ── DownloadError subtypes ─────────────────────────────────────

    @Test
    fun `Canceled has default message`() {
        assertEquals("Download canceled by user", DownloadError.Canceled.message)
    }

    @Test
    fun `StorageFull has default message`() {
        assertEquals("Not enough storage space for this track", DownloadError.StorageFull().message)
    }

    @Test
    fun `StorageFull custom message`() {
        assertEquals("custom", DownloadError.StorageFull("custom").message)
    }

    @Test
    fun `Network default detail becomes message`() {
        assertEquals("Network error while downloading", DownloadError.Network().message)
    }

    @Test
    fun `Network custom detail`() {
        assertEquals("DNS failed", DownloadError.Network("DNS failed").message)
    }

    @Test
    fun `NotFound default detail becomes message`() {
        assertEquals("No audio source found for this track", DownloadError.NotFound().message)
    }

    @Test
    fun `NotFound custom detail`() {
        assertEquals("region locked", DownloadError.NotFound("region locked").message)
    }

    @Test
    fun `Generic has custom message`() {
        assertEquals("something broke", DownloadError.Generic("something broke").message)
    }

    // ── DownloadRunState enum ──────────────────────────────────────

    @Test
    fun `DownloadRunState has all expected values`() {
        val values = DownloadRunState.entries.map { it.name }.toSet()
        assertEquals(
            setOf("IDLE", "RUNNING", "COMPLETED", "CANCELED"),
            values,
        )
    }

    // ── PlaylistStatus enum ────────────────────────────────────────

    @Test
    fun `PlaylistStatus has all expected values`() {
        val values = PlaylistStatus.entries.map { it.name }.toSet()
        assertEquals(
            setOf("NEW", "READY", "DOWNLOADING", "ERROR"),
            values,
        )
    }
}
