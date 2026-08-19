package com.ghostify.ui

import com.ghostify.ui.contract.AddPlaylistContract
import com.ghostify.ui.contract.LibraryContract
import com.ghostify.ui.contract.PlayerContract
import com.ghostify.ui.contract.PlaylistDetailContract
import com.ghostify.ui.contract.SettingsContract
import com.ghostify.ui.model.Bitrate
import com.ghostify.ui.model.CacheStats
import com.ghostify.ui.model.NowPlaying
import com.ghostify.data.model.PlaylistOrigin
import com.ghostify.ui.model.PlaylistUi
import com.ghostify.ui.model.QueueItem
import com.ghostify.ui.model.TrackUi
import com.ghostify.ui.util.RepeatCycle
import com.ghostify.ui.util.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Test doubles for the UI contracts. State is a plain [MutableStateFlow], so tests drive
 * recomposition by mutating `.value` and assert on what the fakes recorded.
 */

class FakeLibraryContract(
    initial: LibraryContract.LibraryUiState = LibraryContract.LibraryUiState(loading = false),
) : LibraryContract {
    override val state: MutableStateFlow<LibraryContract.LibraryUiState> = MutableStateFlow(initial)
    val addClicks = mutableListOf<Unit>()
    val openSettingsClicks = mutableListOf<Unit>()

    override fun onAddClick() {
        addClicks += Unit
        state.update { it.copy(isAddDialogOpen = true) }
    }

    override fun onOpenSettings() {
        openSettingsClicks += Unit
    }
}

class FakeAddPlaylistContract : AddPlaylistContract {
    override val state: MutableStateFlow<AddPlaylistContract.AddPlaylistUiState> =
        MutableStateFlow(AddPlaylistContract.AddPlaylistUiState())

    val fetchedIds = mutableListOf<String>()
    val fetchedOrigins = mutableListOf<PlaylistOrigin>()
    val saveClicks = mutableListOf<Unit>()

    override fun onUrlChange(url: String) {
        state.update { it.copy(url = url) }
    }

    override fun fetch(playlistId: String, origin: PlaylistOrigin) {
        fetchedIds += playlistId
        fetchedOrigins += origin
    }

    override fun onSave() {
        saveClicks += Unit
        state.update { it.copy(canSave = false) }
    }

    override fun onDismiss() = Unit
}

class FakePlaylistDetailContract(
    initial: PlaylistDetailContract.PlaylistDetailUiState = PlaylistDetailContract.PlaylistDetailUiState(loading = false),
) : PlaylistDetailContract {
    override val state: MutableStateFlow<PlaylistDetailContract.PlaylistDetailUiState> =
        MutableStateFlow(initial)
    val downloadAllClicks = mutableListOf<Unit>()
    val syncClicks = mutableListOf<Unit>()
    val playAllClicks = mutableListOf<Unit>()
    val retriedTracks = mutableListOf<String>()

    override fun downloadAll() {
        downloadAllClicks += Unit
        state.update { it.copy(isDownloadingAll = true, downloadAllProgress = 0) }
    }

    override fun sync() {
        syncClicks += Unit
    }

    override fun playAll() {
        playAllClicks += Unit
    }

    override fun retryTrack(trackId: String) {
        retriedTracks += trackId
    }
}

class FakePlayerContract(
    initial: PlayerContract.PlayerUiState = PlayerContract.PlayerUiState(),
) : PlayerContract {
    override val state: MutableStateFlow<PlayerContract.PlayerUiState> = MutableStateFlow(initial)
    val calls = mutableListOf<String>()
    val seeks = mutableListOf<Long>()
    val volumes = mutableListOf<Float>()
    var currentIndex = 0

    override fun togglePlay() {
        calls += "togglePlay"
        state.update { it.copy(isPlaying = !it.isPlaying) }
    }

    override fun next() {
        calls += "next"
        moveTo(currentIndex + 1)
    }

    override fun previous() {
        calls += "previous"
        moveTo(currentIndex - 1)
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        state.update { it.copy(positionMs = positionMs) }
    }

    override fun toggleShuffle() {
        calls += "shuffle"
        state.update { it.copy(shuffle = !it.shuffle) }
    }

    override fun cycleRepeat() {
        calls += "repeat"
        state.update { it.copy(repeatMode = RepeatCycle.next(it.repeatMode)) }
    }

    override fun setVolume(fraction: Float) {
        volumes += fraction
        state.update { it.copy(volume = fraction) }
    }

    override fun jumpToQueueIndex(index: Int) {
        calls += "jump:$index"
        currentIndex = index
        state.update {
            it.copy(
                queueOpen = false,
                nowPlaying = queueItemAt(index),
            )
        }
    }

    override fun toggleQueue() {
        state.update { it.copy(queueOpen = !it.queueOpen) }
    }

    override fun closeQueue() {
        state.update { it.copy(queueOpen = false) }
    }

    private fun moveTo(index: Int) {
        val queue = state.value.queue
        if (queue.isEmpty()) return
        val bounded = index.coerceIn(0, queue.lastIndex)
        currentIndex = bounded
        state.update {
            it.copy(
                nowPlaying = queueItemAt(bounded),
                positionMs = 0,
            )
        }
    }

    private fun queueItemAt(index: Int): NowPlaying? {
        val item = state.value.queue.getOrNull(index) ?: return null
        return NowPlaying(title = item.title, artist = item.artist, album = item.album, coverUrl = null)
    }
}

class FakeSettingsContract(
    initial: SettingsContract.SettingsUiState = SettingsContract.SettingsUiState(),
) : SettingsContract {
    override val state: MutableStateFlow<SettingsContract.SettingsUiState> = MutableStateFlow(initial)
    val setBitrates = mutableListOf<Bitrate>()
    val storageChangeClicks = mutableListOf<Unit>()
    val setConcurrencyValues = mutableListOf<Int>()
    val setAutoDownloadValues = mutableListOf<Boolean>()
    val clearCacheClicks = mutableListOf<Unit>()

    override fun setBitrate(bitrate: Bitrate) {
        setBitrates += bitrate
        state.update { it.copy(bitrate = bitrate) }
    }

    override fun changeStoragePath() {
        storageChangeClicks += Unit
        state.update { it.copy(storagePath = "/storage/emulated/0/Music/Ghostify") }
    }

    override fun setConcurrency(count: Int) {
        setConcurrencyValues += count
        state.update { it.copy(concurrentDownloads = count) }
    }

    override fun setAutoDownload(enabled: Boolean) {
        setAutoDownloadValues += enabled
        state.update { it.copy(autoDownloadOnAdd = enabled) }
    }

    override fun clearCache() {
        clearCacheClicks += Unit
        state.update {
            it.copy(
                isClearingCache = true,
                cacheStats = CacheStats(fileCount = 0, sizeBytes = 0),
            )
        }
        state.update { it.copy(isClearingCache = false) }
    }
}

/** Convenience builders for tests. */
object Fixtures {

    fun playlist(
        id: String,
        name: String,
        trackCount: Int = 5,
        downloaded: Int = 0,
        status: com.ghostify.ui.model.PlaylistStatus = com.ghostify.ui.model.PlaylistStatus.READY,
        progress: Int? = null,
        lastSyncedAt: Long? = 1_700_000_000_000L,
    ) = PlaylistUi(
        id = id,
        name = name,
        owner = "owner",
        coverUrl = null,
        trackCount = trackCount,
        downloadedCount = downloaded,
        status = status,
        origin = PlaylistOrigin.SPOTIFY,
        progressPercent = progress,
        lastSyncedAt = lastSyncedAt,
    )

    fun track(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        durationMs: Long = 210_000,
        status: com.ghostify.ui.model.SongStatus = com.ghostify.ui.model.SongStatus.DOWNLOADED,
        position: Int = 0,
    ) = TrackUi(
        id = id,
        spotifyId = "spotify_$id",
        title = title,
        artists = artist,
        album = album,
        durationMs = durationMs,
        status = status,
        position = position,
    )

    fun nowPlaying(title: String, artist: String = "Artist", album: String = "Album") =
        NowPlaying(title = title, artist = artist, album = album, coverUrl = null)

    fun queueItem(title: String, artist: String = "Artist", durationMs: Long = 200_000) =
        QueueItem(title = title, artist = artist, durationMs = durationMs)

    fun playerState(
        empty: Boolean = false,
        title: String = "Song One",
        isPlaying: Boolean = false,
        positionMs: Long = 0,
        durationMs: Long = 180_000,
        shuffle: Boolean = false,
        repeatMode: RepeatMode = RepeatMode.OFF,
        volume: Float = 0.8f,
        queue: List<QueueItem> = emptyList(),
    ) = PlayerContract.PlayerUiState(
        empty = empty,
        nowPlaying = if (empty) null else nowPlaying(title),
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        shuffle = shuffle,
        repeatMode = repeatMode,
        volume = volume,
        queue = queue,
    )
}
