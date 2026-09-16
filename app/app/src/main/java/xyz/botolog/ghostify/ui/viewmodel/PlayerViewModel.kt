package xyz.botolog.ghostify.ui.viewmodel

import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.download.DownloadManager
import xyz.botolog.ghostify.player.PlayerController
import xyz.botolog.ghostify.player.core.CurrentItem
import xyz.botolog.ghostify.player.core.PlayerUiState as CorePlayerUiState
import xyz.botolog.ghostify.ui.contract.PlayerContract
import xyz.botolog.ghostify.ui.contract.PlayerContract.PlayerUiState
import xyz.botolog.ghostify.ui.model.NowPlaying
import xyz.botolog.ghostify.ui.model.QueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * Backs the Player screen: a pure presentation layer over [PlayerController].
 *
 * It maps the raw player state stream onto the render-ready [PlayerUiState]
 * (now-playing / position / shuffle / repeat / queue / lyrics) and forwards user
 * commands. It never owns playback state itself.
 *
 * @property player the underlying media playback controller.
 * @property songDao DAO for observing lyrics from the database.
 * @property downloads download manager for retry-lyrics support.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val player: PlayerController,
    private val songDao: SongDao,
    private val downloads: DownloadManager,
) : ContractViewModel(), PlayerContract {

    private val _state = MutableStateFlow(PlayerUiState())
    override val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Mutable flag that tracks whether the queue drawer is open. */
    private val queueOpen = MutableStateFlow(false)

    /** Current lyrics for the active track, observed reactively from the DB. */
    private val currentLyrics = MutableStateFlow<String?>(null)

    /** Current song id — drives the lyrics observation switch. */
    private val currentSongId = MutableStateFlow<String?>(null)

    /** Cached media id of the current track to avoid re-extracting artwork on every tick. */
    private var cachedMediaId: String? = null

    /** Cached cover art bytes for the current track. */
    private var cachedCover: Any? = null

    init {
        Timber.i("PlayerViewModel: init")

        // Observe lyrics reactively — switch to a new Flow whenever the current track changes.
        launch {
            currentSongId
                .flatMapLatest { id ->
                    if (id != null) songDao.observeLyricsById(id) else emptyFlow()
                }
                .catch { e -> Timber.e(e, "PlayerViewModel: lyrics stream FAILED") }
                .collect { lyrics ->
                    currentLyrics.value = lyrics
                }
        }

        // Observe player state, queue flag, AND lyrics — combine all three into one emission
        // so lyrics never get overwritten by a stale player-state update.
        launch {
            combine(player.state, queueOpen, currentLyrics) { core, open, lyrics ->
                val ui = mapToUi(core, open)
                // Sync the current song id for lyrics observation.
                val mediaId = ui.nowPlaying?.let { findMediaId(ui) }
                currentSongId.value = mediaId
                ui.copy(lyrics = lyrics)
            }
                .catch { e -> Timber.e(e, "PlayerViewModel: stream collection FAILED") }
                .collect { _state.value = it }
        }
    }

    override fun togglePlay() {
        Timber.i("PlayerViewModel.togglePlay: START")
        player.togglePlayPause()
    }

    override fun next() {
        Timber.i("PlayerViewModel.next: START")
        player.next()
    }

    override fun previous() {
        Timber.i("PlayerViewModel.previous: START")
        player.previous()
    }

    override fun seekTo(positionMs: Long) {
        Timber.i("PlayerViewModel.seekTo: START")
        player.seekTo(positionMs)
    }

    override fun toggleShuffle() {
        Timber.i("PlayerViewModel.toggleShuffle: START")
        player.toggleShuffle()
    }

    override fun cycleRepeat() {
        Timber.i("PlayerViewModel.cycleRepeat: START")
        player.toggleRepeatMode()
    }

    override fun setVolume(fraction: Float) {
        Timber.i("PlayerViewModel.setVolume: START")
        player.setVolume(fraction)
    }

    override fun jumpToQueueIndex(index: Int) {
        Timber.i("PlayerViewModel.jumpToQueueIndex: START")
        player.skipToMediaItem(index)
    }

    override fun toggleQueue() {
        Timber.i("PlayerViewModel.toggleQueue: START")
        queueOpen.update { !it }
    }

    override fun closeQueue() {
        Timber.i("PlayerViewModel.closeQueue: START")
        queueOpen.value = false
    }

    override fun retryLyrics() {
        Timber.i("PlayerViewModel.retryLyrics: START")
        val songId = currentSongId.value ?: return
        launch {
            try {
                downloads.retryLyricsForSong(songId)
            } catch (e: Exception) {
                Timber.e(e, "PlayerViewModel.retryLyrics: FAILED for songId=$songId")
            }
        }
    }

    /**
     * Maps the raw core player state and queue-open flag to a render-ready [PlayerUiState].
     *
     * @param core the current state from the player core.
     * @param open whether the queue drawer is visible.
     */
    private fun mapToUi(core: CorePlayerUiState, open: Boolean): PlayerUiState {
        val isEmpty = core.nothingToPlay || core.queue.isEmpty()
        val current = core.currentItem
        return PlayerUiState(
            empty = isEmpty,
            nowPlaying = if (isEmpty || current == null) null else toNowPlaying(current),
            isPlaying = core.isPlaying,
            positionMs = core.positionMs,
            durationMs = core.durationMs,
            shuffle = core.shuffleEnabled,
            repeatMode = core.repeatMode.toUi(),
            volume = core.volume,
            queue = mapQueueItems(core),
            queueOpen = open,
        )
    }

    /**
     * Extracts the media id from the current state for lyrics observation.
     */
    private fun findMediaId(ui: PlayerUiState): String? {
        // The queue items carry the songId which matches the DB primary key.
        val idx = player.state.value.currentQueueIndex
        if (idx < 0 || idx >= player.state.value.queue.size) return null
        return player.state.value.queue[idx].songId
    }

    /**
     * Maps the core queue list to a list of render-ready [QueueItem]s.
     *
     * @param core the current state from the player core.
     */
    private fun mapQueueItems(core: CorePlayerUiState): List<QueueItem> =
        core.queue.mapIndexed { index, item ->
            QueueItem(
                title = item.title.orEmpty(),
                artist = item.artist.orEmpty(),
                durationMs = item.durationMs ?: DEFAULT_DURATION_MS,
                isCurrent = index == core.currentQueueIndex,
            )
        }

    /**
     * Converts a [CurrentItem] from the player core into a [NowPlaying] model.
     *
     * @param current the current item from the player core.
     */
    private fun toNowPlaying(current: CurrentItem): NowPlaying = NowPlaying(
        title = current.title.orEmpty(),
        artist = current.artist.orEmpty(),
        album = current.album.orEmpty(),
        coverUrl = coverFor(current),
    )

    /**
     * Returns cover art for the current track. Passes the raw [ByteArray] to Coil
     * directly (more efficient than base64-encoding), falling back to the HTTP URL
     * from the database when embedded artwork is unavailable.
     *
     * @param current the current item from the player core.
     */
    private fun coverFor(current: CurrentItem): Any? {
        val mediaId = current.mediaId ?: return current.coverUrl
        val bytes = current.artworkBytes ?: return current.coverUrl
        if (cachedMediaId == mediaId) return cachedCover
        cachedMediaId = mediaId
        cachedCover = bytes
        return cachedCover
    }

    companion object {
        /** Default duration used when the player core reports `null`. */
        private const val DEFAULT_DURATION_MS = 0L
    }
}
