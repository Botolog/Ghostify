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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
    private val persistence: PlayerStatePersistence? = null,
    private val loopPlaylists: Flow<Boolean>,
) : Player.Listener {

    private val _state = MutableStateFlow(PlayerUiState())
    private val artworkByMediaId = HashMap<String, ByteArray>()
    private var nothingToPlay = false
    private var lastError: PlayerError? = null
    private var tickerJob: Job? = null
    private var artworkBackfillJob: Job? = null
    private var saveJob: Job? = null
    val queueManager = PlayerQueueManager()
    private var isSyncingExoPlayer = false
    private var loopPlaylistsEnabled = true
    private var endOfQueueHandled = false
    var currentPlaylistId: String? = null
        private set

    /** Snapshot of the current playback state, updated on every relevant player event. */
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        exoPlayer.addListener(this)
        startPositionTicker()
        publishSnapshot()
        scope.launch {
            restoreSavedState()
        }
        scope.launch {
            loopPlaylists.distinctUntilChanged().collect { enabled ->
                loopPlaylistsEnabled = enabled
            }
        }
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
    fun playPlaylist(songs: List<Song>, startSongId: String? = null, playlistId: String? = null) {
        Timber.i("playPlaylist: ${songs.size} songs, startSongId=$startSongId, playlistId=$playlistId")
        currentPlaylistId = playlistId
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
        playlistId: String? = null,
        loadMore: suspend (offset: Int) -> List<Song>?,
    ) {
        Timber.i("playPlaylistLazy: initialBatch=${initialBatch.size}, startSongId=$startSongId, playlistId=$playlistId")
        currentPlaylistId = playlistId
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
                        queueManager.appendItems(newItems)
                        exoPlayer.addMediaItems(mediaItems)
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
        queueManager.clear()
        exoPlayer.clearMediaItems()
        _state.update {
            PlayerUiState(
                nothingToPlay = true,
                shuffleEnabled = queueManager.isShuffled,
                repeatMode = RepeatMode.fromMedia3(exoPlayer.repeatMode),
                volume = exoPlayer.volume,
            )
        }
    }

    private suspend fun handleQueueReady(result: QueueBuildResult.Ready) {
        val clickedSongId = result.startSongId
        val (currentItems, startIndex) = withContext(Dispatchers.Main.immediate) {
            val shouldShuffle = queueManager.isShuffled
            Timber.i("handleQueueReady: ${result.items.size} items, startIdx=${result.startIndex}, shuffle=$shouldShuffle, clickedSong=$clickedSongId")

            queueManager.setQueue(result.items)

            if (clickedSongId != null) {
                val clickedIdx = queueManager.indexOf(clickedSongId)
                if (clickedIdx > 0) {
                    val rotated = queueManager.queue.subList(clickedIdx, queueManager.size) +
                        queueManager.queue.subList(0, clickedIdx)
                    queueManager.setQueue(rotated)
                }
                if (shouldShuffle && queueManager.size > 1) {
                    queueManager.shuffle(keepFirstPinned = true)
                }
            } else {
                if (shouldShuffle) {
                    queueManager.shuffle()
                }
            }

            val items = queueManager.queue
            val index = if (clickedSongId != null) {
                0
            } else if (shouldShuffle) {
                0
            } else {
                result.startIndex
            }
            items to index
        }

        val mediaItems = buildMediaItems(currentItems, startIndex)

        withContext(Dispatchers.Main.immediate) {
            nothingToPlay = false
            lastError = null
            artworkByMediaId.clear()
            exoPlayer.shuffleModeEnabled = false
            artworkByMediaId.putAll(mediaItems.artwork)
            exoPlayer.setMediaItems(mediaItems.items, startIndex, PlaybackConstants.TIME_UNSET)
            exoPlayer.prepare()
            exoPlayer.play()
            publishSnapshot()
            startPlaybackService()
        }
        launchArtworkBackfill(currentItems, startIndex)
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
     * current without churning the UI. Exactly one backfill runs per controller: a new call
     * cancels the previous job.
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
                    exoPlayer.currentMediaItem?.mediaId
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

    // --- State persistence --------------------------------------------------------

    /**
     * Restores the player state from the last session.
     *
     * Rebuilds the queue from saved song IDs, seeks to the saved position,
     * and restores shuffle/repeat/volume settings.
     */
    private suspend fun restoreSavedState() {
        if (persistence == null) return
        val saved = persistence.load() ?: return
        Timber.i("PlayerController.restoreSavedState: restoring songId=${saved.currentSongId}")
        val songs = persistence.restoreSongs(saved.queueSongIds)
        if (songs.isEmpty()) {
            Timber.w("PlayerController.restoreSavedState: no playable songs, clearing state")
            persistence.clear()
            return
        }
        when (val result = queueBuilder.build(songs, saved.currentSongId)) {
            is QueueBuildResult.NothingToPlay -> {
                Timber.w("PlayerController.restoreSavedState: nothing to play after rebuild")
                persistence.clear()
            }
            is QueueBuildResult.Ready -> {
                val clickedSongId = saved.currentSongId
                withContext(Dispatchers.Main.immediate) {
                    queueManager.setQueue(result.items)
                    if (clickedSongId != null) {
                        val clickedIdx = queueManager.indexOf(clickedSongId)
                        if (clickedIdx > 0) {
                            val rotated = queueManager.queue.subList(clickedIdx, queueManager.size) +
                                queueManager.queue.subList(0, clickedIdx)
                            queueManager.setQueue(rotated)
                        }
                        if (saved.shuffleEnabled && queueManager.size > 1) {
                            queueManager.shuffle(keepFirstPinned = true)
                        }
                    } else if (saved.shuffleEnabled) {
                        queueManager.shuffle()
                    }
                }
                val currentItems = queueManager.queue
                val startIndex = if (clickedSongId != null) {
                    0
                } else if (saved.shuffleEnabled) {
                    0
                } else {
                    saved.currentSongId?.let { queueManager.indexOf(it) }?.coerceAtLeast(0) ?: 0
                }
                val mediaItems = buildMediaItems(currentItems, startIndex)
                withContext(Dispatchers.Main.immediate) {
                    nothingToPlay = false
                    lastError = null
                    artworkByMediaId.clear()
                    artworkByMediaId.putAll(mediaItems.artwork)
                    exoPlayer.shuffleModeEnabled = false
                    exoPlayer.setMediaItems(mediaItems.items, startIndex, PlaybackConstants.TIME_UNSET)
                    exoPlayer.prepare()
                    exoPlayer.repeatMode = saved.repeatMode.media3Value
                    exoPlayer.volume = saved.volume.coerceIn(0f, 1f)
                    if (saved.positionMs > 0) {
                        exoPlayer.seekTo(saved.positionMs)
                    }
                    if (saved.wasPlaying) {
                        exoPlayer.play()
                    }
                    publishSnapshot()
                    startPlaybackService()
                }
                launchArtworkBackfill(currentItems, startIndex)
                Timber.i("PlayerController.restoreSavedState: restored successfully")
            }
        }
    }

    /**
     * Saves the current player state to persistence (debounced).
     *
     * Only saves when there is a queue loaded. Cancels any pending save
     * and reschedules with a 2-second delay to avoid excessive writes
     * during rapid state changes (e.g. position ticker).
     */
    private fun saveState() {
        if (persistence == null) return
        if (nothingToPlay || queueManager.queue.isEmpty()) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val snapshot = _state.value
            val queueSongIds = queueManager.queue.map { it.songId }
            val currentSongId = snapshot.currentItem?.mediaId
            val currentQueueIdx = queueManager.queue.indexOfFirst { it.songId == currentSongId }
            persistence.save(
                currentSongId = currentSongId,
                playlistId = currentPlaylistId,
                queueSongIds = queueSongIds,
                queueIndex = currentQueueIdx.coerceAtLeast(0),
                shuffleEnabled = snapshot.shuffleEnabled,
                repeatMode = snapshot.repeatMode,
                volume = snapshot.volume,
                positionMs = snapshot.positionMs,
                wasPlaying = snapshot.isPlaying,
            )
            Timber.d("PlayerController.saveState: saved idx=$currentQueueIdx, queueSize=${queueSongIds.size}")
        }
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
        Timber.i("PlayerController.setShuffleEnabled: enabled=$enabled")
        if (exoPlayer.shuffleModeEnabled) {
            exoPlayer.shuffleModeEnabled = false
        }
        if (enabled != queueManager.isShuffled && queueManager.size > 0) {
            if (enabled) {
                val activeIndex = currentQueueIndex()
                if (activeIndex >= 0) {
                    queueManager.shuffleAfter(activeIndex)
                    syncExoPlayerFromQueueManager(activeIndex)
                }
            } else {
                val activeMediaId = exoPlayer.currentMediaItem?.mediaId
                queueManager.unshuffle()
                val canonicalIndex = activeMediaId?.let(queueManager::indexOf)?.takeIf { it >= 0 }
                syncExoPlayerFromQueueManager(canonicalIndex)
            }
        }
        if (exoPlayer.shuffleModeEnabled) {
            exoPlayer.shuffleModeEnabled = false
        }
        publishSnapshot()
    }

    /** Toggles shuffle mode on/off. */
    fun toggleShuffle() {
        Timber.i("PlayerController.toggleShuffle")
        setShuffleEnabled(!queueManager.isShuffled)
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
     * Moves a media item within the queue from one position to another.
     *
     * @param fromIndex the current position of the item to move.
     * @param toIndex the target position.
     */
    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        Timber.i("PlayerController.reorderQueue: from=$fromIndex, to=$toIndex")
        if (!queueManager.reorder(fromIndex, toIndex)) return

        val savedMediaId = exoPlayer.currentMediaItem?.mediaId
        val savedPosition = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.isPlaying

        exoPlayer.moveMediaItem(fromIndex, toIndex)

        if (savedMediaId != null) {
            val newIndex = queueManager.indexOf(savedMediaId)
            if (newIndex >= 0 && newIndex != exoPlayer.currentMediaItemIndex) {
                exoPlayer.seekTo(newIndex, savedPosition.coerceAtLeast(0))
            }
        }
        if (wasPlaying && !exoPlayer.isPlaying) exoPlayer.play()
        publishSnapshot()
    }

    /**
     * Adds a song to the queue after all user-queued songs.
     *
     * @param songId the song database ID.
     * @param mediaItem the Media3 item to insert.
     */
    fun addToQueueNext(songId: String, mediaItem: MediaItem) {
        Timber.i("PlayerController.addToQueueNext: songId=$songId")
        if (exoPlayer.mediaItemCount == 0) return
        val currentIdx = exoPlayer.currentMediaItemIndex

        val mutation = queueManager.addToQueueNext(songId, mediaItem, currentIdx) ?: return

        // Mirror the mutation on ExoPlayer.
        val savedMediaId = exoPlayer.currentMediaItem?.mediaId
        val savedPosition = exoPlayer.currentPosition
        val wasPlaying = exoPlayer.isPlaying

        when (mutation) {
            is QueueMutation.Add -> {
                exoPlayer.addMediaItem(mutation.index, mutation.mediaItem)
            }
            is QueueMutation.Move -> {
                exoPlayer.moveMediaItem(mutation.from, mutation.to)
            }
        }

        if (savedMediaId != null) {
            val newCurrentIdx = queueManager.indexOf(savedMediaId)
            if (newCurrentIdx >= 0 && newCurrentIdx != exoPlayer.currentMediaItemIndex) {
                exoPlayer.seekTo(newCurrentIdx, savedPosition.coerceAtLeast(0))
            }
        }
        if (wasPlaying && !exoPlayer.isPlaying) exoPlayer.play()
        publishSnapshot()
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
        saveJob?.cancel()
        backfillJob?.cancel()
        cancelArtworkBackfill("controller released")
        exoPlayer.removeListener(this)
        scope.cancel()
    }

    // --- Player.Listener -------------------------------------------------------------

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Timber.d("PlayerController.onMediaItemTransition: mediaId=${mediaItem?.mediaId}, reason=$reason")
        if (isSyncingExoPlayer) return
        endOfQueueHandled = false
        val completedRepeatAllCycle =
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                exoPlayer.repeatMode == Player.REPEAT_MODE_ALL &&
                loopPlaylistsEnabled &&
                queueManager.isShuffled
        if (completedRepeatAllCycle) {
            startNextQueueCycle(reshuffle = true)
        } else {
            publishSnapshot()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Timber.d("PlayerController.onPlaybackStateChanged: state=$playbackState")
        if (isSyncingExoPlayer) return
        if (playbackState != Player.STATE_ENDED) {
            endOfQueueHandled = false
            publishSnapshot()
            return
        }
        if (
            exoPlayer.repeatMode != Player.REPEAT_MODE_OFF ||
            endOfQueueHandled ||
            exoPlayer.mediaItemCount == 0
        ) {
            publishSnapshot()
            return
        }
        endOfQueueHandled = true
        if (loopPlaylistsEnabled) {
            startNextQueueCycle(reshuffle = queueManager.isShuffled)
        } else {
            publishSnapshot()
        }
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
        if (shuffleModeEnabled) {
            if (!isSyncingExoPlayer) {
                setShuffleEnabled(true)
            }
            if (exoPlayer.shuffleModeEnabled) {
                exoPlayer.shuffleModeEnabled = false
            }
        } else {
            publishSnapshot()
        }
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

    private fun currentQueueIndex(): Int {
        if (exoPlayer.mediaItemCount == 0) return -1
        val currentMediaId = exoPlayer.currentMediaItem?.mediaId
        if (currentMediaId != null) {
            return queueManager.indexOf(currentMediaId).takeIf { it >= 0 } ?: -1
        }
        val playerIndex = exoPlayer.currentMediaItemIndex
        return if (playerIndex in 0 until queueManager.size) playerIndex else -1
    }

    private fun syncExoPlayerFromQueueManager(anchoredIndex: Int? = null) {
        isSyncingExoPlayer = true
        try {
            resequenceExoPlayerFromQueueManager(anchoredIndex)
        } finally {
            isSyncingExoPlayer = false
        }
    }

    private fun restorePlayWhenReady(wasPlayWhenReady: Boolean) {
        if (wasPlayWhenReady == exoPlayer.playWhenReady) return
        if (wasPlayWhenReady) exoPlayer.play() else exoPlayer.pause()
    }

    private fun resequenceExoPlayerFromQueueManager(anchoredIndex: Int? = null) {
        val desiredCount = queueManager.size
        if (desiredCount == 0) {
            exoPlayer.shuffleModeEnabled = false
            exoPlayer.clearMediaItems()
            return
        }

        exoPlayer.shuffleModeEnabled = false
        val savedMediaId = exoPlayer.currentMediaItem?.mediaId
        val savedPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlayWhenReady = exoPlayer.playWhenReady
        val currentCount = exoPlayer.mediaItemCount
        val currentById = HashMap<String, MediaItem>(currentCount)
        var hasDuplicateCurrentId = false
        for (index in 0 until currentCount) {
            val mediaItem = exoPlayer.getMediaItemAt(index)
            if (currentById.put(mediaItem.mediaId, mediaItem) != null) {
                hasDuplicateCurrentId = true
            }
        }

        val desiredIds = HashSet<String>(desiredCount)
        var hasDuplicateDesiredId = false
        for (index in 0 until desiredCount) {
            val queueItem = queueManager.getOrNull(index) ?: return
            if (!desiredIds.add(queueItem.songId)) hasDuplicateDesiredId = true
        }

        if (
            anchoredIndex != null &&
            currentCount == desiredCount &&
            !hasDuplicateCurrentId &&
            !hasDuplicateDesiredId &&
            anchoredIndex in 0 until desiredCount &&
            currentById.size == desiredCount &&
            desiredIds.all { currentById.containsKey(it) } &&
            (savedMediaId == null || queueManager.getOrNull(anchoredIndex)?.songId == savedMediaId)
        ) {
            var prefixMatches = true
            for (index in 0..anchoredIndex) {
                val queueItem = queueManager.getOrNull(index)
                if (queueItem == null || exoPlayer.getMediaItemAt(index).mediaId != queueItem.songId) {
                    prefixMatches = false
                    break
                }
            }
            if (prefixMatches) {
                val suffixStart = anchoredIndex + 1
                val orderedSuffix = ArrayList<MediaItem>(desiredCount - suffixStart)
                var suffixChanged = false
                for (index in suffixStart until desiredCount) {
                    val queueItem = queueManager.getOrNull(index) ?: return
                    val mediaItem = currentById[queueItem.songId] ?: return
                    orderedSuffix.add(mediaItem)
                    if (exoPlayer.getMediaItemAt(index).mediaId != mediaItem.mediaId) {
                        suffixChanged = true
                    }
                }
                if (suffixChanged) {
                    exoPlayer.replaceMediaItems(suffixStart, currentCount, orderedSuffix)
                }
                restorePlayWhenReady(wasPlayWhenReady)
                if (suffixChanged && exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
                Timber.i(
                    "syncExoPlayerFromQueueManager: reordered suffix $suffixStart..$currentCount " +
                        "in one transaction",
                )
                return
            }
        }

        val orderedExistingItems = ArrayList<MediaItem>(desiredCount)
        var canReplaceExistingItems =
            currentCount == desiredCount && !hasDuplicateCurrentId && !hasDuplicateDesiredId
        var savedItemTargetIndex = -1
        for (index in 0 until desiredCount) {
            val queueItem = queueManager.getOrNull(index) ?: return
            if (queueItem.songId == savedMediaId) savedItemTargetIndex = index
            if (canReplaceExistingItems) {
                val mediaItem = currentById[queueItem.songId]
                if (mediaItem == null) {
                    canReplaceExistingItems = false
                } else {
                    orderedExistingItems.add(mediaItem)
                }
            }
        }

        if (canReplaceExistingItems) {
            exoPlayer.replaceMediaItems(0, currentCount, orderedExistingItems)
            if (savedMediaId != null && savedItemTargetIndex >= 0) {
                exoPlayer.seekTo(savedItemTargetIndex, savedPosition)
            }
            restorePlayWhenReady(wasPlayWhenReady)
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            Timber.i("syncExoPlayerFromQueueManager: reordered ${currentCount} items in one transaction")
        } else {
            val mediaItems = ArrayList<MediaItem>(desiredCount)
            for (index in 0 until desiredCount) {
                val queueItem = queueManager.getOrNull(index) ?: return
                mediaItems.add(
                    currentById[queueItem.songId]
                        ?: MediaItemMapper.toMediaItem(queueItem, null),
                )
            }
            val savedItemIndex = savedMediaId?.let { queueManager.indexOf(it) } ?: -1
            val startIndex = savedItemIndex.coerceAtLeast(0)
            val startPosition = if (savedItemIndex >= 0) savedPosition else 0L
            exoPlayer.setMediaItems(mediaItems, startIndex, startPosition)
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            restorePlayWhenReady(wasPlayWhenReady)
            Timber.i("syncExoPlayerFromQueueManager: rebuilt ${mediaItems.size} items, current=$startIndex")
        }
    }

    private fun startNextQueueCycle(reshuffle: Boolean) {
        if (exoPlayer.mediaItemCount == 0) {
            publishSnapshot()
            return
        }
        val shouldPlay = exoPlayer.playWhenReady
        isSyncingExoPlayer = true
        try {
            if (reshuffle) {
                queueManager.reshuffle()
                resequenceExoPlayerFromQueueManager()
            }
            exoPlayer.seekTo(0, 0L)
            if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
            if (shouldPlay) exoPlayer.play()
        } finally {
            isSyncingExoPlayer = false
        }
        publishSnapshot()
    }

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
        val repeatMode = exoPlayer.repeatMode
        val loopAtEnd =
            repeatMode == Player.REPEAT_MODE_ALL ||
                (repeatMode == Player.REPEAT_MODE_OFF && loopPlaylistsEnabled)
        val targetIndex = resolveSkipTargetIndex(currentIndex, count, loopAtEnd)
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
    private fun resolveSkipTargetIndex(currentIndex: Int, count: Int, loopAtEnd: Boolean): Int = when {
        currentIndex + 1 < count -> currentIndex + 1
        loopAtEnd -> 0
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
        if (isSyncingExoPlayer) return
        Timber.d("PlayerController.publishSnapshot: START")
        val snapshot = buildPlayerSnapshot()
        _state.update { current ->
            PlayerStateMapper.toUiState(
                snapshot = snapshot,
                queue = queueManager.queue,
                nothingToPlay = nothingToPlay,
                lastError = lastError,
            )
        }
        saveState()
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
                shuffleEnabled = queueManager.isShuffled,
            volume = exoPlayer.volume,
            currentMediaId = currentItem?.mediaId,
            currentTitle = currentItem?.mediaMetadata?.title?.toString(),
            currentArtist = currentItem?.mediaMetadata?.artist?.toString(),
            currentAlbum = currentItem?.mediaMetadata?.albumTitle?.toString(),
            artworkBytes = currentItem?.mediaId?.let { artworkByMediaId[it] },
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

        /** Delay in milliseconds before persisting state (debounce). */
        private const val SAVE_DEBOUNCE_MS = 2000L

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
         * @param persistence optional persistence for restoring player state across restarts.
         */
        fun create(
            context: Context,
            queueBuilder: PlayerQueueBuilder = PlayerQueueBuilder(),
            artworkExtractor: ArtworkExtractor = MediaMetadataRetrieverArtworkExtractor(),
            sessionActivityClass: Class<*>? = null,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            persistence: PlayerStatePersistence? = null,
            loopPlaylists: Flow<Boolean> = flowOf(true),
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
                persistence = persistence,
                loopPlaylists = loopPlaylists,
            )
        }
    }
}
