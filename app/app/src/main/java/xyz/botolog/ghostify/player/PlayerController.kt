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
import xyz.botolog.ghostify.player.core.ArtworkBackfillBatcher
import xyz.botolog.ghostify.player.core.ErrorAction
import xyz.botolog.ghostify.player.core.PlaybackConstants
import xyz.botolog.ghostify.player.core.PlayerError
import xyz.botolog.ghostify.player.core.PlayerErrorClassifier
import xyz.botolog.ghostify.player.core.LazyPlayerQueueBuilder
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
import kotlinx.coroutines.ensureActive
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
 * Playback starts as soon as the queue is built: embedded artwork is only extracted
 * synchronously for the item playback starts on; every other item is backfilled in the
 * background ([ArtworkBackfillBatcher] batches the resulting UI updates).
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
    private var artworkBackfillJob: Job? = null

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
     * @param songs list of songs from which to build the playback queue.
     * @param startSongId optional song to start from (by `songs.id`); defaults to the first item.
     */
    fun playPlaylist(songs: List<Song>, startSongId: String? = null) {
        Timber.i("playPlaylist: ${songs.size} songs, startSongId=$startSongId")
        // A new playlist supersedes any in-flight artwork backfill from the previous queue.
        cancelArtworkBackfill("superseded by new playPlaylist")
        backfillJob?.cancel()
        backfillJob = null
        scope.launch(Dispatchers.IO) {
            when (val result = queueBuilder.build(songs, startSongId)) {
                is QueueBuildResult.NothingToPlay -> handleNothingToPlay()
                is QueueBuildResult.Ready -> handleQueueReady(result)
            }
        }
    }

    private var backfillJob: Job? = null

    /**
     * Lazily builds a queue: starts playback with [initialBatch], then loads more songs
     * in the background via [loadMore] and appends them to ExoPlayer incrementally.
     *
     * This avoids blocking playback while validating thousands of files on disk.
     *
     * @param initialBatch first page of songs (already fetched by the caller).
     * @param startSongId optional song to start from; defaults to the first playable item.
     * @param loadMore suspend function returning the next batch of songs, or `null` when done.
     */
    fun playPlaylistLazy(
        initialBatch: List<Song>,
        startSongId: String? = null,
        loadMore: suspend (offset: Int) -> List<Song>?,
    ) {
        Timber.i("playPlaylistLazy: initialBatch=${initialBatch.size}, startSongId=$startSongId")
        cancelArtworkBackfill("superseded by new playPlaylistLazy")
        backfillJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val lazyBuilder = LazyPlayerQueueBuilder()
            when (val result = lazyBuilder.buildInitial(initialBatch, startSongId)) {
                is QueueBuildResult.NothingToPlay -> {
                    // Initial batch had nothing — try loading more
                    val nextBatch = loadMore(initialBatch.size)
                    if (nextBatch.isNullOrEmpty()) {
                        handleNothingToPlay()
                        return@launch
                    }
                    when (val retry = lazyBuilder.buildInitial(nextBatch, startSongId)) {
                        is QueueBuildResult.NothingToPlay -> handleNothingToPlay()
                        is QueueBuildResult.Ready -> {
                            handleQueueReady(retry)
                            launchBackfill(lazyBuilder, retry.items.size + initialBatch.size, loadMore)
                        }
                    }
                }
                is QueueBuildResult.Ready -> {
                    handleQueueReady(result)
                    launchBackfill(lazyBuilder, initialBatch.size, loadMore)
                }
            }
        }
    }

    /**
     * Loads remaining songs in the background and appends them to the ExoPlayer queue.
     */
    private fun launchBackfill(
        lazyBuilder: LazyPlayerQueueBuilder,
        offset: Int,
        loadMore: suspend (offset: Int) -> List<Song>?,
    ) {
        backfillJob = scope.launch(Dispatchers.IO) {
            var currentOffset = offset
            while (isActive) {
                val batch = loadMore(currentOffset) ?: break
                if (batch.isEmpty()) break
                val newItems = lazyBuilder.loadMore(batch)
                if (newItems.isNotEmpty()) {
                    val mediaItems = newItems.map { item ->
                        MediaItemMapper.toMediaItem(item, null)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        exoPlayer.addMediaItems(mediaItems)
                        _state.update { it.copy(queue = lazyBuilder.allItemsCopy()) }
                    }
                    Timber.d("playPlaylistLazy: appended ${newItems.size} items, total=${lazyBuilder.totalItems}")
                }
                currentOffset += batch.size
                if (lazyBuilder.isFullyLoaded(batch.size, LazyPlayerQueueBuilder.INITIAL_BATCH_SIZE)) break
            }
            Timber.i("playPlaylistLazy: backfill complete, total=${lazyBuilder.totalItems}")
        }
    }

    /** Applies the idle state when no songs are downloadable / queueable. */
    private suspend fun handleNothingToPlay() = withContext(Dispatchers.Main.immediate) {
        Timber.w("playPlaylist: nothing to play")
        nothingToPlay = true
        lastError = null
        // The artwork map is cleared here, so a stale backfill must not keep writing into it.
        cancelArtworkBackfill("nothing to play")
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

    /** Builds MediaItems, configures the player and starts the foreground service. */
    private suspend fun handleQueueReady(result: QueueBuildResult.Ready) {
        val mediaItems = buildMediaItems(result.items, result.startIndex)
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
        // Playback is already running; fill in the remaining artwork off the critical path.
        launchArtworkBackfill(result.items, result.startIndex)
    }

    /** Holds a batch of built [MediaItem]s together with their extracted artwork bytes. */
    private data class BuiltMediaItems(
        val items: List<MediaItem>,
        val artwork: Map<String, ByteArray>,
    )

    /**
     * Converts each [QueueItem] into a Media3 [MediaItem], extracting artwork only for the
     * item playback starts on so `setMediaItems`/`play` are never delayed by tag parsing.
     *
     * @param items queue items in playlist order.
     * @param startIndex index playback starts at; its artwork is embedded synchronously.
     * @return the MediaItem list plus a map (start item only) of songId -> artwork bytes.
     */
    private fun buildMediaItems(items: List<QueueItem>, startIndex: Int): BuiltMediaItems {
        val mediaItems = ArrayList<MediaItem>(items.size)
        val artwork = HashMap<String, ByteArray>(1)
        for ((index, item) in items.withIndex()) {
            val art = if (index == startIndex) {
                runCatching { artworkExtractor.extractArtwork(item.filePath) }.getOrNull()
            } else {
                null
            }
            if (art != null) artwork[item.songId] = art
            mediaItems.add(MediaItemMapper.toMediaItem(item, art))
        }
        return BuiltMediaItems(mediaItems, artwork)
    }

    /**
     * Extracts embedded artwork for every queued item except [startIndex] in the background.
     *
     * Runs one item at a time on [Dispatchers.IO]; each result lands in [artworkByMediaId]
     * (main thread, where all map reads happen) followed by a batched [publishSnapshot] via
     * [ArtworkBackfillBatcher], so `CurrentItem.artworkBytes` fills in as tracks become
     * current without churning the UI.  If the extracted item happens to be the one currently
     * playing, its [MediaItem] is also updated so the notification shows artwork immediately.
     * Exactly one backfill runs per controller: a new call cancels the previous job.
     *
     * @param items queue items in playlist order.
     * @param startIndex index whose artwork was already extracted during queue building.
     */
    private fun launchArtworkBackfill(items: List<QueueItem>, startIndex: Int) {
        cancelArtworkBackfill("queue swapped")
        val pending = items.filterIndexed { index, _ -> index != startIndex }
        if (pending.isEmpty()) return
        Timber.i("launchArtworkBackfill: START pending=${pending.size}, startIndex=$startIndex")
        artworkBackfillJob = scope.launch(Dispatchers.IO) {
            val batcher = ArtworkBackfillBatcher()
            for (item in pending) {
                ensureActive()
                val art = runCatching { artworkExtractor.extractArtwork(item.filePath) }.getOrNull()
                    ?: continue
                val currentMediaId = withContext(Dispatchers.Main.immediate) {
                    artworkByMediaId[item.songId] = art
                    // If this item is now playing (e.g. user skipped ahead), update its
                    // MediaItem so the notification shows artwork.
                    val current = exoPlayer.currentMediaItem
                    if (current?.mediaId == item.songId && current.mediaMetadata.artworkData == null) {
                        updateCurrentItemArtwork(art)
                    }
                    current?.mediaId
                }
                if (batcher.shouldPublish(item.songId, currentMediaId)) {
                    withContext(Dispatchers.Main.immediate) { publishSnapshot() }
                }
            }
            Timber.i("launchArtworkBackfill: DONE (${pending.size} items)")
        }
    }

    /** Cancels any running artwork backfill (e.g. when a new queue supersedes it). */
    private fun cancelArtworkBackfill(reason: String) {
        artworkBackfillJob?.let { job ->
            Timber.d("cancelArtworkBackfill: cancelling ($reason)")
            job.cancel()
        }
        artworkBackfillJob = null
    }

    // --- Transport controls ----------------------------------------------------------

    /** Resumes playback. */
    fun play() {
        Timber.i("PlayerController.play: START")
        exoPlayer.play()
    }

    /** Pauses playback. */
    fun pause() {
        Timber.i("PlayerController.pause: START")
        exoPlayer.pause()
    }

    /** Toggles between play and pause. */
    fun togglePlayPause() {
        Timber.i("PlayerController.togglePlayPause: START")
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    /** Skips to the next song (restarts the current one when it is the last with repeat off). */
    fun next() {
        Timber.i("PlayerController.next: START")
        exoPlayer.seekToNext()
    }

    /** Returns to the previous song (restarts the current one if it started less than 3s ago). */
    fun previous() {
        Timber.i("PlayerController.previous: START")
        exoPlayer.seekToPrevious()
    }

    /** Always skips to the previous track, never restarts the current one. */
    fun skipToPreviousTrack() {
        Timber.i("PlayerController.skipToPreviousTrack: START")
        val index = exoPlayer.currentMediaItemIndex
        if (index > 0) {
            exoPlayer.seekTo(index - 1, 0)
        } else {
            exoPlayer.seekTo(0)
        }
    }

    /**
     * Jumps to a specific queue position (by playlist-order index).
     *
     * @param index zero-based queue index.
     */
    fun skipToMediaItem(index: Int) {
        Timber.i("PlayerController.skipToMediaItem: START index=$index")
        if (index !in 0 until exoPlayer.mediaItemCount) return
        ensurePrepared()
        exoPlayer.seekToDefaultPosition(index)
    }

    /**
     * Seeks the current song to the given position and keeps playing.
     *
     * @param positionMs target position in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        Timber.i("PlayerController.seekTo: START positionMs=$positionMs")
        if (exoPlayer.mediaItemCount == 0) return
        ensurePrepared()
        exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * Enables or disables shuffle mode.
     *
     * @param enabled true to shuffle, false for sequential order.
     */
    fun setShuffleEnabled(enabled: Boolean) {
        Timber.i("PlayerController.setShuffleEnabled: START enabled=$enabled")
        exoPlayer.shuffleModeEnabled = enabled
    }

    /** Toggles shuffle mode on/off. */
    fun toggleShuffle() {
        Timber.i("PlayerController.toggleShuffle: START")
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    /**
     * Sets the repeat mode.
     *
     * @param mode one of [RepeatMode.OFF], [RepeatMode.ALL], or [RepeatMode.ONE].
     */
    fun setRepeatMode(mode: RepeatMode) {
        Timber.i("PlayerController.setRepeatMode: START mode=$mode")
        exoPlayer.repeatMode = mode.media3Value
    }

    /** Cycles repeat OFF -> ALL -> ONE -> OFF. */
    fun toggleRepeatMode() {
        Timber.i("PlayerController.toggleRepeatMode: START")
        exoPlayer.repeatMode = RepeatMode.fromMedia3(exoPlayer.repeatMode).next().media3Value
    }

    /**
     * Sets the player volume.
     *
     * @param volume 0.0 (mute) .. 1.0 (full).
     */
    fun setVolume(volume: Float) {
        Timber.i("PlayerController.setVolume: START volume=$volume")
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    /** Releases all resources held by this controller. */
    fun release() {
        Timber.i("PlayerController.release: START")
        tickerJob?.cancel()
        backfillJob?.cancel()
        cancelArtworkBackfill("controller released")
        exoPlayer.removeListener(this)
        scope.cancel()
    }

    // --- Player.Listener -------------------------------------------------------------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("PlayerController.onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
        val mediaId = mediaItem?.mediaId
        val isExternal = mediaId != null &&
            _state.value.queue.none { it.songId == mediaId } &&
            exoPlayer.mediaItemCount > 0
        if (isExternal) {
            Timber.i("PlayerController.onMediaItemTransition: external queue change detected (e.g. Android Auto)")
            nothingToPlay = false
            val newQueue = buildQueueFromExoPlayer()
            _state.update { it.copy(queue = newQueue) }
            extractArtworkForCurrentItem()
        }
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

    /**
     * Builds queue items from ExoPlayer's current media items.
     *
     * Used when items are added externally (e.g. Android Auto) so the phone UI's
     * internal queue stays in sync with what ExoPlayer is actually playing.
     */
    private fun buildQueueFromExoPlayer(): List<QueueItem> {
        Timber.i("PlayerController.buildQueueFromExoPlayer: itemCount=${exoPlayer.mediaItemCount}")
        return (0 until exoPlayer.mediaItemCount).map { index ->
            val item = exoPlayer.getMediaItemAt(index)
            QueueItem(
                songId = item.mediaId,
                title = item.mediaMetadata.title?.toString(),
                artist = item.mediaMetadata.artist?.toString(),
                album = item.mediaMetadata.albumTitle?.toString(),
                durationMs = item.mediaMetadata.durationMs,
                filePath = item.localConfiguration?.uri?.path.orEmpty(),
                indexInQueue = index,
            )
        }
    }

    /**
     * Extracts artwork for the current item in the background.
     *
     * Used when items are added externally (e.g. Android Auto) so the phone UI
     * shows cover art without blocking the callback.  Also updates the [MediaItem]
     * in ExoPlayer so the foreground-service notification displays album art.
     */
    private fun extractArtworkForCurrentItem() {
        val currentItem = exoPlayer.currentMediaItem ?: return
        val mediaId = currentItem.mediaId ?: return
        val filePath = currentItem.localConfiguration?.uri?.path ?: return

        // If artwork was already backfilled but not written into the MediaItem, fix
        // that immediately so the notification picks it up.
        val cachedArt = artworkByMediaId[mediaId]
        if (cachedArt != null) {
            if (currentItem.mediaMetadata.artworkData == null) {
                updateCurrentItemArtwork(cachedArt)
            }
            return
        }

        Timber.i("PlayerController.extractArtworkForCurrentItem: mediaId=$mediaId")
        scope.launch(Dispatchers.IO) {
            val art = runCatching { artworkExtractor.extractArtwork(filePath) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                artworkByMediaId[mediaId] = art
                updateCurrentItemArtwork(art)
                publishSnapshot()
            }
        }
    }

    /**
     * Writes [artwork] into the current [MediaItem]'s metadata so the media
     * notification (and lockscreen) show the album art.
     *
     * No-op if the item already carries artwork data (avoids redundant replace calls).
     */
    private fun updateCurrentItemArtwork(artwork: ByteArray) {
        val currentItem = exoPlayer.currentMediaItem ?: return
        if (currentItem.mediaMetadata.artworkData != null) return
        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setArtworkData(artwork, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()
        exoPlayer.replaceMediaItem(exoPlayer.currentMediaItemIndex, updatedItem)
        Timber.i("PlayerController: updated MediaItem artwork for mediaId=${currentItem.mediaId}")
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
        val targetIndex = resolveSkipTargetIndex(currentIndex, count, repeatAll)
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

    /**
     * Computes the next index when skipping a broken item.
     *
     * @return the target index, or -1 if there is nothing left to play.
     */
    private fun resolveSkipTargetIndex(currentIndex: Int, count: Int, repeatAll: Boolean): Int = when {
        currentIndex + 1 < count -> currentIndex + 1
        repeatAll -> 0
        else -> -1
    }

    /** Ensures the player is prepared when idle but has media items queued. */
    private fun ensurePrepared() {
        Timber.d("PlayerController.ensurePrepared: START")
        if (exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
            exoPlayer.prepare()
        }
    }

    /**
     * Starts the foreground [PlaybackService] and creates a [MediaController] that
     * binds to it so the notification appears.
     */
    private fun startPlaybackService() {
        Timber.i("startPlaybackService: starting PlaybackService")
        val intent = Intent(context, PlaybackService::class.java)
        ContextCompat.startForegroundService(context, intent)
        val sessionToken = SessionToken(
            context,
            android.content.ComponentName(context, PlaybackService::class.java),
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

    /**
     * Reads the player and pushes a fresh [PlayerUiState].
     *
     * Must run on the main thread.
     */
    private fun publishSnapshot() {
        Timber.d("PlayerController.publishSnapshot: START")
        val snapshot = buildPlayerSnapshot()
        _state.update { current ->
            PlayerStateMapper.toUiState(
                snapshot = snapshot,
                queue = current.queue,
                nothingToPlay = nothingToPlay,
                lastError = lastError,
            )
        }
    }

    /** Creates a snapshot of every relevant ExoPlayer field. */
    private fun buildPlayerSnapshot(): PlayerSnapshot {
        val currentItem = exoPlayer.currentMediaItem
        return PlayerSnapshot(
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
            artworkBytes = currentItem?.mediaId?.let { id -> artworkByMediaId[id] ?: currentItem.mediaMetadata.artworkData },
        )
    }

    /**
     * Polls [Player.currentPosition] into the state flow.
     *
     * 250ms is plenty smooth for a seek bar while costing ~nothing; seeks and state transitions
     * are also pushed synchronously by the player listener.
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

        /** Interval in milliseconds between position-ticker updates. */
        private const val TICK_INTERVAL_MS = 250L

        /**
         * Creates a controller backed by the shared process-wide [ExoPlayer] (see
         * [PlaybackEngine]) and its [MediaSession], so the UI and the background
         * media service always drive the same player.
         *
         * @param context used to obtain the application context.
         * @param queueBuilder builds the playback queue from song lists.
         * @param artworkExtractor extracts embedded album art.
         * @param sessionActivityClass optional activity class for the notification tap target.
         * @param scope coroutine scope for background work and the position ticker.
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
            val player = PlaybackEngine.exoPlayer(context)
            val sess = PlaybackEngine.session(context, sessionActivityClass)
            Timber.i("PlayerController.create: DONE")
            return PlayerController(
                context = appCtx,
                exoPlayer = player,
                queueBuilder = queueBuilder,
                artworkExtractor = artworkExtractor,
                session = sess,
                scope = scope,
            )
        }
    }
}
