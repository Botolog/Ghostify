package xyz.botolog.ghostify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.contract.PlaylistDetailContract.PlaylistDetailUiState
import xyz.botolog.ghostify.ui.contract.SettingsContract.SettingsUiState
import xyz.botolog.ghostify.ui.model.Bitrate
import xyz.botolog.ghostify.ui.model.CacheStats
import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.model.QueueItem
import xyz.botolog.ghostify.ui.model.SongStatus
import xyz.botolog.ghostify.ui.model.TrackUi
import xyz.botolog.ghostify.ui.util.RepeatMode

class ContractModelsTest {

    // ── PlayerUiState defaults ──────────────────────────────────────────

    @Test
    fun playerUiStateDefaultsAreCorrect() {
        val state = PlayerUiState()
        assertTrue(state.empty)
        assertNull(state.nowPlaying)
        assertFalse(state.isPlaying)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertFalse(state.shuffle)
        assertEquals(RepeatMode.OFF, state.repeatMode)
        assertEquals(0.8f, state.volume, 0.001f)
        assertEquals(emptyList<QueueItem>(), state.queue)
        assertFalse(state.queueOpen)
    }

    @Test
    fun playerUiStateCreationWithAllFields() {
        val nowPlaying = NowPlaying("T", "A", "B", "url")
        val queue = listOf(QueueItem(songId = "S1", title = "T1", artist = "A1", durationMs = 100L, isCurrent = true))
        val state = PlayerUiState(
            empty = false,
            nowPlaying = nowPlaying,
            isPlaying = true,
            positionMs = 5000L,
            durationMs = 200000L,
            shuffle = true,
            repeatMode = RepeatMode.ALL,
            volume = 0.5f,
            queue = queue,
            queueOpen = true,
        )
        assertFalse(state.empty)
        assertEquals(nowPlaying, state.nowPlaying)
        assertTrue(state.isPlaying)
        assertEquals(5000L, state.positionMs)
        assertEquals(200000L, state.durationMs)
        assertTrue(state.shuffle)
        assertEquals(RepeatMode.ALL, state.repeatMode)
        assertEquals(0.5f, state.volume, 0.001f)
        assertEquals(queue, state.queue)
        assertTrue(state.queueOpen)
    }

    @Test
    fun playerUiStateEquality() {
        val a = PlayerUiState()
        val b = PlayerUiState()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun playerUiStateCopy() {
        val original = PlayerUiState()
        val copied = original.copy(isPlaying = true, positionMs = 1000L)
        assertTrue(copied.isPlaying)
        assertEquals(1000L, copied.positionMs)
        assertTrue(copied.empty)
        assertEquals(0.8f, copied.volume, 0.001f)
    }

    @Test
    fun playerUiStateVolumeRange() {
        val muted = PlayerUiState(volume = 0f)
        assertEquals(0f, muted.volume, 0.001f)
        val full = PlayerUiState(volume = 1f)
        assertEquals(1f, full.volume, 0.001f)
    }

    // ── PlaylistDetailUiState defaults ──────────────────────────────────

    @Test
    fun playlistDetailUiStateDefaultsAreCorrect() {
        val state = PlaylistDetailUiState()
        assertEquals("", state.playlistId)
        assertEquals("", state.name)
        assertNull(state.coverUrl)
        assertEquals(emptyList<TrackUi>(), state.tracks)
        assertEquals(0, state.downloadedCount)
        assertEquals(0, state.trackCount)
        assertTrue(state.loading)
        assertFalse(state.isDownloadingAll)
        assertNull(state.downloadAllProgress)
        assertFalse(state.isSyncing)
        assertNull(state.error)
    }

    @Test
    fun playlistDetailUiStateCreationWithAllFields() {
        val tracks = listOf(
            TrackUi("1", "s1", "T", "A", "B", 100L, SongStatus.DOWNLOADED, 0),
        )
        val state = PlaylistDetailUiState(
            playlistId = "pl-1",
            name = "My Playlist",
            coverUrl = "https://example.com/cover.jpg",
            tracks = tracks,
            downloadedCount = 1,
            trackCount = 10,
            loading = false,
            isDownloadingAll = true,
            downloadAllProgress = 50,
            isSyncing = true,
            error = "Network error",
        )
        assertEquals("pl-1", state.playlistId)
        assertEquals("My Playlist", state.name)
        assertEquals("https://example.com/cover.jpg", state.coverUrl)
        assertEquals(1, state.tracks.size)
        assertEquals(1, state.downloadedCount)
        assertEquals(10, state.trackCount)
        assertFalse(state.loading)
        assertTrue(state.isDownloadingAll)
        assertEquals(50, state.downloadAllProgress)
        assertTrue(state.isSyncing)
        assertEquals("Network error", state.error)
    }

    @Test
    fun playlistDetailUiStateEquality() {
        val a = PlaylistDetailUiState()
        val b = PlaylistDetailUiState()
        assertEquals(a, b)
    }

    @Test
    fun playlistDetailUiStateCopy() {
        val original = PlaylistDetailUiState(playlistId = "1", name = "A")
        val copied = original.copy(name = "B", loading = false)
        assertEquals("B", copied.name)
        assertFalse(copied.loading)
        assertEquals("1", copied.playlistId)
    }

    // ── SettingsUiState defaults ────────────────────────────────────────

    @Test
    fun settingsUiStateDefaultsAreCorrect() {
        val state = SettingsUiState()
        assertEquals(Bitrate.MEDIUM, state.bitrate)
        assertEquals("", state.storagePath)
        assertEquals(1, state.concurrentDownloads)
        assertFalse(state.autoDownloadOnAdd)
        assertTrue(state.loopPlaylists)
        assertEquals(CacheStats(0, 0), state.cacheStats)
        assertFalse(state.isClearingCache)
    }

    @Test
    fun settingsUiStateCreationWithAllFields() {
        val cache = CacheStats(10, 1024L * 1024L)
        val state = SettingsUiState(
            bitrate = Bitrate.HIGH,
            storagePath = "/storage/emulated/0/music",
            concurrentDownloads = 4,
            autoDownloadOnAdd = true,
            loopPlaylists = false,
            cacheStats = cache,
            isClearingCache = true,
        )
        assertEquals(Bitrate.HIGH, state.bitrate)
        assertEquals("/storage/emulated/0/music", state.storagePath)
        assertEquals(4, state.concurrentDownloads)
        assertTrue(state.autoDownloadOnAdd)
        assertFalse(state.loopPlaylists)
        assertEquals(cache, state.cacheStats)
        assertTrue(state.isClearingCache)
    }

    @Test
    fun settingsUiStateEquality() {
        val a = SettingsUiState()
        val b = SettingsUiState()
        assertEquals(a, b)
    }

    @Test
    fun settingsUiStateCopy() {
        val original = SettingsUiState()
        val copied = original.copy(bitrate = Bitrate.HIGH, concurrentDownloads = 3)
        assertEquals(Bitrate.HIGH, copied.bitrate)
        assertEquals(3, copied.concurrentDownloads)
        assertEquals("", copied.storagePath)
        assertFalse(copied.autoDownloadOnAdd)
    }

    @Test
    fun settingsUiStateConcurrencyMinimum() {
        val state = SettingsUiState(concurrentDownloads = 1)
        assertEquals(1, state.concurrentDownloads)
    }
}
