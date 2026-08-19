package xyz.botolog.ghostify.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import xyz.botolog.ghostify.background.PlaybackService
import xyz.botolog.ghostify.player.core.ErrorAction
import xyz.botolog.ghostify.player.core.PlaybackConstants
import xyz.botolog.ghostify.player.core.PlayerError
import xyz.botolog.ghostify.player.core.PlayerErrorClassifier
import xyz.botolog.ghostify.player.core.PlayerQueueBuilder
import xyz.botolog.ghostify.player.core.PlayerSnapshot
import xyz.botolog.ghostify.player.core.PlayerStateMapper
import xyz.botolog.ghostify.player.core.PlayerUiState
import xyz.botolog.ghostify.player.core.QueueBuildResult
import xyz.botolog.ghostify.player.core.QueueItem
import xyz.botolog.ghostify.player.core.RepeatMode
import xyz.botolog.ghostify.player.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The app's single playback controller.
 *
 * Responsibilities:
 * - Build a Media3 queue from a playlist's DOWNLOADED songs (playlist order) and play it.
 * - Expose play/pause/next/prev/seek/shuffle/repeat/volume and the full player state as a
 *   [StateFlow] for the UI.
 * - Auto-advance on song end (native ExoPlayer) and skip corrupt/unplayable files gracefully.
 * - Report "nothing to play" for empty queues without crashing.
 * - Never rebuild an already-playing queue on its own: the queue only changes when the caller
 *   explicitly asks to [playPlaylist] again, so a mid-play DB refresh can never disturb the
 *   current song.
 *
 * All player access happens on the main thread (the player's own thread); queue building and
 * artwork extraction run on [Dispatchers.IO].
 *
 * @param sessionActivityClass optional activity for the media notification tap-through; pass
 *   the player screen.
 */
class PlayerController private constructor(
    private val context: Context,
    private val exoPlayer: ExoPlayer,
    private val queueBuilder: PlayerQueueBuilder,
    private val artworkExtractor: ArtworkExtractor,
    private val session: MediaSession?,
    private val scope: CoroutineScope,
) : Player.Listener {

    private val _state = MutableStateFlow(PlayerUiState())
    private val artworkByMediaId = HashMap<String, ByteArray>()
    private var nothingToPlay = false
    private var lastError: PlayerError? = null
    private var tickerJob: Job? = null

    /** Snapshot of the current playback state, updated on every relevant player event. */
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        exoPlayer.addListener(this)
        startPositionTicker()
        publishSnapshot()
    }

    // --- Queue building -------------------------------------------------------------

    /**
     * Builds a fresh queue from [songs] (in playlist order) and starts playing it.
     *
     * Only DOWNLOADED songs with a real, non-empty file are queued. If none qualify the player
     * reports the "nothing to play" state and stays idle. This is the *only* way the queue
     * changes; the controller never observes the database itself.
     *
     * @param startSongId optional song to start from (by `songs.id`); defaults to the first item.
     */
    fun playPlaylist(songs: List<Song>, startSongId: String? = null) {
        Timber.i("playPlaylist: ${songs.size} songs, startSongId=$startSongId")
        scope.launch(Dispatchers.IO) {
            when (val result = queueBuilder.build(songs, startSongId)) {
                is QueueBuildResult.NothingToPlay -> withContext(Dispatchers.Main.immediate) {
                    Timber.w("playPlaylist: nothing to play")
                    nothingToPlay = true
                    lastError = null
                    artworkByMediaId.clear()
                    exoPlayer.clearMediaItems()
                    _state.update {
                        PlayerUiState(
                            nothingToPlay = true,
                            shuffleEnabled = exoPlayer.shuffleModeEnabled,
                            repeatMode = RepeatMode.fromMedia3(exoPlayer.repeatMode),
                            volume = exoPlayer.volume,
                        )
                    }
                }

                is QueueBuildResult.Ready -> {
                    val mediaItems = buildMediaItems(result.items)
                    Timber.i("playPlaylist: ${result.items.size} playable items, starting at ${result.startIndex}")
                    withContext(Dispatchers.Main.immediate) {
                        nothingToPlay = false
                        lastError = null
                        artworkByMediaId.clear()
                        artworkByMediaId.putAll(mediaItems.artwork)
                        exoPlayer.setMediaItems(mediaItems.items, result.startIndex, PlaybackConstants.TIME_UNSET)
                        exoPlayer.prepare()
                        exoPlayer.play()
                        _state.update { it.copy(queue = result.items) }
                        publishSnapshot()
                        startPlaybackService()
                    }
                }
            }
        }
    }

    private data class BuiltMediaItems(
        val items: List<MediaItem>,
        val artwork: Map<String, ByteArray>,
    )

    private fun buildMediaItems(items: List<QueueItem>): BuiltMediaItems {
        val mediaItems = ArrayList<MediaItem>(items.size)
        val artwork = HashMap<String, ByteArray>(items.size)
        for (item in items) {
            val art = runCatching { artworkExtractor.extractArtwork(item.filePath) }.getOrNull()
            if (art != null) artwork[item.songId] = art
            mediaItems.add(MediaItemMapper.toMediaItem(item, art))
        }
        return BuiltMediaItems(mediaItems, artwork)
    }

    // --- Transport controls ----------------------------------------------------------

    fun play() {
        Timber.i("PlayerController.play: START")
        exoPlayer.play()
    }

    fun pause() {
        Timber.i("PlayerController.pause: START")
        exoPlayer.pause()
    }

    fun togglePlayPause() {
        Timber.i("PlayerController.togglePlayPause: START")
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    /** Next song (restarts the current one when it is the last with repeat off). */
    fun next() {
        Timber.i("PlayerController.next: START")
        exoPlayer.seekToNext()
    }

    /** Previous song (restarts the current one if it started less than 3s ago). */
    fun previous() {
        Timber.i("PlayerController.previous: START")
        exoPlayer.seekToPrevious()
    }

    /** Jumps to a specific queue position (by playlist-order index). */
    fun skipToMediaItem(index: Int) {
        Timber.i("PlayerController.skipToMediaItem: START index=$index")
        if (index !in 0 until exoPlayer.mediaItemCount) return
        ensurePrepared()
        exoPlayer.seekToDefaultPosition(index)
    }

    /** Seeks the current song to [positionMs] and keeps playing. */
    fun seekTo(positionMs: Long) {
        Timber.i("PlayerController.seekTo: START positionMs=$positionMs")
        if (exoPlayer.mediaItemCount == 0) return
        ensurePrepared()
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setShuffleEnabled(enabled: Boolean) {
        Timber.i("PlayerController.setShuffleEnabled: START enabled=$enabled")
        exoPlayer.shuffleModeEnabled = enabled
    }

    fun toggleShuffle() {
        Timber.i("PlayerController.toggleShuffle: START")
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    fun setRepeatMode(mode: RepeatMode) {
        Timber.i("PlayerController.setRepeatMode: START mode=$mode")
        exoPlayer.repeatMode = mode.media3Value
    }

    /** Cycles repeat OFF -> ALL -> ONE -> OFF. */
    fun toggleRepeatMode() {
        Timber.i("PlayerController.toggleRepeatMode: START")
        exoPlayer.repeatMode = RepeatMode.fromMedia3(exoPlayer.repeatMode).next().media3Value
    }

    /** @param volume 0.0 (mute) .. 1.0 (full). */
    fun setVolume(volume: Float) {
        Timber.i("PlayerController.setVolume: START volume=$volume")
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    fun release() {
        Timber.i("PlayerController.release: START")
        tickerJob?.cancel()
        exoPlayer.removeListener(this)
        scope.cancel()
    }

    // --- Player.Listener -------------------------------------------------------------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("PlayerController.onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
        publishSnapshot()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Timber.d("PlayerController.onPlaybackStateChanged: state=$playbackState")
        publishSnapshot()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        Timber.d("PlayerController.onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reason")
        publishSnapshot()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Timber.d("PlayerController.onIsPlayingChanged: isPlaying=$isPlaying")
        publishSnapshot()
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        Timber.d("PlayerController.onIsLoadingChanged: isLoading=$isLoading")
        publishSnapshot()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        Timber.d("PlayerController.onRepeatModeChanged: repeatMode=$repeatMode")
        publishSnapshot()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        Timber.d("PlayerController.onShuffleModeEnabledChanged: shuffleModeEnabled=$shuffleModeEnabled")
        publishSnapshot()
    }

    override fun onVolumeChanged(volume: Float) {
        Timber.d("PlayerController.onVolumeChanged: volume=$volume")
        publishSnapshot()
    }

    override fun onPlayerError(error: PlaybackException) {
        Timber.e(error, "PlayerController.onPlayerError: errorCode=${error.errorCode}")
        lastError = PlayerError(error.errorCode, error.message)
        when (PlayerErrorClassifier.classify(error.errorCode)) {
            ErrorAction.SKIP_CURRENT -> skipUnplayableItem()
            ErrorAction.STOP_PLAYBACK -> publishSnapshot()
        }
    }

    // --- Internals ------------------------------------------------------------------

    /**
     * Advances past a media item that failed to load/decode (corrupt or truncated file).
     *
     * Uses raw index arithmetic rather than [Player.seekToNextMediaItem] so the skip works
     * regardless of repeat mode: with repeat ONE a failing item must be skipped, not retried
     * forever. With repeat ALL the last item wraps to the start; otherwise a failure on the
     * last item stops playback cleanly instead of crashing.
     */
    private fun skipUnplayableItem() {
        Timber.d("PlayerController.skipUnplayableItem: START")
        val count = exoPlayer.mediaItemCount
        if (count == 0) return
        val currentIndex = exoPlayer.currentMediaItemIndex
        val repeatAll = exoPlayer.repeatMode == Player.REPEAT_MODE_ALL
        val targetIndex = when {
            currentIndex + 1 < count -> currentIndex + 1
            repeatAll -> 0
            else -> -1
        }
        if (targetIndex < 0) {
            exoPlayer.stop()
            publishSnapshot()
            return
        }
        exoPlayer.seekToDefaultPosition(targetIndex)
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        }
        publishSnapshot()
    }

    private fun ensurePrepared() {
        Timber.d("PlayerController.ensurePrepared: START")
        if (exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
            exoPlayer.prepare()
        }
    }

    private fun startPlaybackService() {
        Timber.i("startPlaybackService: starting PlaybackService")
        val intent = Intent(context, PlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
        // Create a MediaController to bind to the service. This triggers
        // onGetSession() → addSession() → notification appears.
        val sessionToken = SessionToken(
            context,
            android.content.ComponentName(context, PlaybackService::class.java)
        )
        Timber.i("startPlaybackService: calling MediaController.Builder.buildAsync")
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                Timber.i("startPlaybackService: MediaController connected OK, controller=${controller.javaClass.simpleName}")
            } catch (t: Throwable) {
                Timber.e(t, "startPlaybackService: MediaController FAILED to connect")
            }
        }, { it.run() })
        Timber.i("startPlaybackService: MediaController buildAsync called")
    }

    /** Reads the player and pushes a fresh [PlayerUiState]. Must run on the main thread. */
    private fun publishSnapshot() {
        Timber.d("PlayerController.publishSnapshot: START")
        val currentItem = exoPlayer.currentMediaItem
        val snapshot = PlayerSnapshot(
            playbackState = exoPlayer.playbackState,
            isPlaying = exoPlayer.isPlaying,
            playWhenReady = exoPlayer.playWhenReady,
            isLoading = exoPlayer.isLoading,
            currentItemIndex = exoPlayer.currentMediaItemIndex,
            itemCount = exoPlayer.mediaItemCount,
            currentPositionMs = exoPlayer.currentPosition,
            currentDurationMs = exoPlayer.duration,
            itemMetadataDurationMs = exoPlayer.mediaMetadata.durationMs,
            repeatMode = exoPlayer.repeatMode,
            shuffleEnabled = exoPlayer.shuffleModeEnabled,
            volume = exoPlayer.volume,
            currentMediaId = currentItem?.mediaId,
            currentTitle = currentItem?.mediaMetadata?.title?.toString(),
            currentArtist = currentItem?.mediaMetadata?.artist?.toString(),
            currentAlbum = currentItem?.mediaMetadata?.albumTitle?.toString(),
            artworkBytes = currentItem?.mediaId?.let { artworkByMediaId[it] },
        )
        _state.update { current ->
            PlayerStateMapper.toUiState(
                snapshot = snapshot,
                queue = current.queue,
                nothingToPlay = nothingToPlay,
                lastError = lastError,
            )
        }
    }

    /**
     * Polls [Player.currentPosition] into the state flow. 250ms is plenty smooth for a seek bar
     * while costing ~nothing; seeks and state transitions are also pushed synchronously by the
     * player listener.
     */
    private fun startPositionTicker() {
        tickerJob = scope.launch {
            while (isActive) {
                val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                _state.update { current ->
                    if (position == current.positionMs) current else current.copy(positionMs = position)
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    companion object {
        private const val TICK_INTERVAL_MS = 250L

        /**
         * Creates a controller backed by the shared process-wide [ExoPlayer] (see
         * [PlaybackEngine]) and its [MediaSession], so the UI and the background
         * media service always drive the same player.
         */
        fun create(
            context: Context,
            queueBuilder: PlayerQueueBuilder = PlayerQueueBuilder(),
            artworkExtractor: ArtworkExtractor = MediaMetadataRetrieverArtworkExtractor(),
            sessionActivityClass: Class<*>? = null,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        ): PlayerController {
            Timber.i("PlayerController.create: START")
            val appCtx = context.applicationContext
            Timber.i("PlayerController.create: getting ExoPlayer")
            val player = PlaybackEngine.exoPlayer(context)
            Timber.i("PlayerController.create: ExoPlayer obtained, getting session")
            val sess = PlaybackEngine.session(context, sessionActivityClass)
            Timber.i("PlayerController.create: session obtained, building controller")
            return PlayerController(
                context = appCtx,
                exoPlayer = player,
                queueBuilder = queueBuilder,
                artworkExtractor = artworkExtractor,
                session = sess,
                scope = scope,
            ).also { Timber.i("PlayerController.create: DONE") }
        }
    }
}
