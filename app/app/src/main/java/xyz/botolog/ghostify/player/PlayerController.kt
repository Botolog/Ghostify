package xyz.botolog.ghostify.player

import android.content.Context
import android.os.SystemClock
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
import xyz.botolog.ghostify.player.core.SleepTimerState
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
import java.io.File

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

    /**
     * Embedded cover bytes keyed by song id, bounded by [MAX_ARTWORK_ENTRIES] and
     * [MAX_ARTWORK_BYTES].
     *
     * A large queue can hold thousands of tracks whose covers are each hundreds of KB, so
     * retaining every one for the whole session fed avoidable GC pressure. The bounded cache
     * evicts least-recently-used entries instead; the track on screen is pinned by
     * [BoundedArtworkCache.pinnedKey] so its cover is never evicted.
     */
    private val artworkByMediaId = BoundedArtworkCache(MAX_ARTWORK_ENTRIES, MAX_ARTWORK_BYTES)
    private var nothingToPlay = false
    private var lastError: PlayerError? = null
    private var tickerJob: Job? = null
    private var artworkBackfillJob: Job? = null
    private var saveJob: Job? = null

    /**
     * The last state actually written by [saveState], or `null` when nothing has been written
     * yet. Compared against a fresh signature so an unchanged state does not re-write the
     * settings table (which would otherwise happen on every idle ExoPlayer listener event).
     */
    private var lastSavedSignature: PersistedSignature? = null

    /** The running sleep timer, or [SleepTimerState.Inactive] when there is none. */
    private var sleepTimer: SleepTimerState = SleepTimerState.Inactive

    /**
     * Bumped every time a sleep timer is started or cancelled.
     *
     * The ticker captures the value it was launched with and stops as soon as it no longer
     * matches, so a ticker that is already queued on the main thread can never pause playback
     * for a timer the user already cancelled.
     */
    private var sleepTimerGeneration = 0
    private var sleepTimerJob: Job? = null
    val queueManager = PlayerQueueManager()

    /**
     * Incremented every time a queue is (re)built or torn down.
     *
     * Queue building — and the lazy backfill that keeps extending it — runs off the main
     * thread, so a stale result can land after the queue was replaced. Each builder captures
     * the generation it was started for and drops its result once the generation moved on,
     * which is what stops a deleted playlist's tracks (or a lazy backfill) from resurrecting a
     * queue that was just cleared.
     */
    private var queueGeneration = 0
    private var isSyncingExoPlayer = false
    private var loopPlaylistsEnabled = true
    private var endOfQueueHandled = false
    private var mediaController: MediaController? = null

    /**
     * Test seam for local cover-art bytes (`covers/<songId>.jpg` or
     * `coverArtLocalPath` file). When set, it replaces filesystem reads so unit
     * tests can supply artwork without Android storage. Production leaves it null
     * and reads from disk.
     */
    internal var coverBytesProvider: ((songId: String, coverPath: String?) -> ByteArray?)? = null

    /**
     * Starts [PlaybackService] by connecting the notification [MediaController] (a binder
     * connection). Replaced in unit tests, which have no Android framework.
     */
    internal var startPlaybackService: () -> Unit = ::startForegroundPlayback

    /**
     * The monotonic clock the sleep timer counts down on. Replaced in unit tests, which have
     * no Android framework; defaults to [SystemClock.elapsedRealtime], which keeps counting
     * while the device is asleep, so a timer set before a long doze still fires on time.
     */
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }

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
        val generation = ++queueGeneration
        // A new playlist supersedes any in-flight artwork backfill from the previous queue.
        cancelArtworkBackfill("superseded by new playPlaylist")
        backfillJob?.cancel()
        backfillJob = null
        scope.launch(Dispatchers.IO) {
            when (val result = queueBuilder.build(songs, startSongId)) {
                is QueueBuildResult.NothingToPlay -> handleNothingToPlay(generation)
                is QueueBuildResult.Ready -> handleQueueReady(result, generation)
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
        val generation = ++queueGeneration
        cancelArtworkBackfill("superseded by new playPlaylistLazy")
        backfillJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val lazyBuilder = LazyPlayerQueueBuilder()
            when (val result = lazyBuilder.buildInitial(initialBatch, startSongId)) {
                is QueueBuildResult.NothingToPlay -> {
                    // Initial batch had nothing — try loading more
                    val nextBatch = loadMore(initialBatch.size)
                    if (nextBatch.isNullOrEmpty()) {
                        handleNothingToPlay(generation)
                        return@launch
                    }
                    when (val retry = lazyBuilder.buildInitial(nextBatch, startSongId)) {
                        is QueueBuildResult.NothingToPlay -> handleNothingToPlay(generation)
                        is QueueBuildResult.Ready -> {
                            handleQueueReady(retry, generation)
                            launchBackfill(lazyBuilder, retry.items.size + initialBatch.size, loadMore, generation)
                        }
                    }
                }
                is QueueBuildResult.Ready -> {
                    handleQueueReady(result, generation)
                    launchBackfill(lazyBuilder, initialBatch.size, loadMore, generation)
                }
            }
        }
    }

    /**
     * Loads remaining songs in the background and appends them to the ExoPlayer queue.
     *
     * Aborts as soon as the queue generation moves on (a new playlist was started, or the
     * playlist was deleted), so a deleted playlist's remaining pages can never be appended to a
     * queue that no longer belongs to it.
     */
    private fun launchBackfill(
        lazyBuilder: LazyPlayerQueueBuilder,
        offset: Int,
        loadMore: suspend (offset: Int) -> List<Song>?,
        generation: Int = queueGeneration,
    ) {
        backfillJob = scope.launch(Dispatchers.IO) {
            var currentOffset = offset
            while (isActive) {
                if (generation != queueGeneration) {
                    Timber.i("playPlaylistLazy: backfill superseded, aborting")
                    return@launch
                }
                val batch = loadMore(currentOffset) ?: break
                if (batch.isEmpty()) break
                val newItems = lazyBuilder.loadMore(batch)
                if (newItems.isNotEmpty()) {
                    // Prefer already-cached/file artwork so lazily-appended items carry
                    // artworkData for the notification immediately instead of null.
                    // IO thread: only file reads here (no artworkByMediaId touch).
                    val pairs = newItems.map { item -> item to cheapFileArtwork(item) }
                    val mediaItems = pairs.map { (item, art) ->
                        MediaItemMapper.toMediaItem(item, art)
                    }
                    val appended = withContext(Dispatchers.Main.immediate) {
                        if (generation != queueGeneration) {
                            false
                        } else {
                            queueManager.appendItems(newItems)
                            exoPlayer.addMediaItems(mediaItems)
                            for ((item, art) in pairs) {
                                if (art != null) artworkByMediaId[item.songId] = art
                            }
                            for ((index, pair) in pairs.withIndex()) {
                                val art = mediaItems.getOrNull(index)?.mediaMetadata?.artworkData
                                if (art != null) artworkByMediaId[pair.first.songId] = art
                            }
                            true
                        }
                    }
                    if (!appended) {
                        Timber.i("playPlaylistLazy: queue replaced while loading, dropping batch")
                        return@launch
                    }
                    Timber.d("playPlaylistLazy: appended ${newItems.size} items, total=${lazyBuilder.totalItems}")
                    // Backfill anything that had no cheap/cached bytes via extractor,
                    // updating the ExoPlayer timeline so the notification picks it up.
                    for ((item, cheap) in pairs) {
                        if (cheap != null) continue
                        ensureActive()
                        if (generation != queueGeneration) return@launch
                        val art = runCatching { artworkExtractor.extractArtwork(item.filePath) }.getOrNull()
                            ?: continue
                        withContext(Dispatchers.Main.immediate) {
                            if (generation != queueGeneration) return@withContext
                            artworkByMediaId[item.songId] = art
                            replaceExoPlayerArtwork(item.songId, art)
                        }
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == queueGeneration) publishSnapshot()
                    }
                }
                currentOffset += batch.size
                if (lazyBuilder.isFullyLoaded(batch.size, LazyPlayerQueueBuilder.INITIAL_BATCH_SIZE)) break
            }
            Timber.i("playPlaylistLazy: backfill complete, total=${lazyBuilder.totalItems}")
        }
    }

    /**
     * Applies the idle state when no songs are downloadable / queueable.
     *
     * @param generation the queue generation this build was started for; a stale result is
     *   dropped instead of clobbering whatever replaced the queue in the meantime.
     */
    private suspend fun handleNothingToPlay(generation: Int = queueGeneration) {
        if (generation != queueGeneration) {
            Timber.i("handleNothingToPlay: stale build (gen=$generation), ignoring")
            return
        }
        withContext(Dispatchers.Main.immediate) {
            Timber.w("playPlaylist: nothing to play")
            nothingToPlay = true
            lastError = null
            // The artwork map is cleared here, so a stale backfill must not keep writing into it.
            cancelArtworkBackfill("nothing to play")
            artworkByMediaId.clear()
            queueManager.clear()
            exoPlayer.clearMediaItems()
            val sleepNow = elapsedRealtimeMs()
            _state.update {
                PlayerUiState(
                    nothingToPlay = true,
                    shuffleEnabled = queueManager.isShuffled,
                    repeatMode = RepeatMode.fromMedia3(exoPlayer.repeatMode),
                    volume = exoPlayer.volume,
                    sleepTimerActive = sleepTimer.isActive,
                    sleepTimerRemainingMs = sleepTimer.remainingMs(sleepNow),
                    sleepTimerTotalMs = sleepTimer.totalMs,
                )
            }
        }
    }

    private suspend fun handleQueueReady(
        result: QueueBuildResult.Ready,
        generation: Int = queueGeneration,
    ) {
        if (generation != queueGeneration) {
            Timber.i("handleQueueReady: stale build (gen=$generation), ignoring")
            return
        }
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

        val applied = withContext(Dispatchers.Main.immediate) {
            if (generation != queueGeneration) {
                Timber.i("handleQueueReady: queue replaced while building, discarding result")
                return@withContext false
            }
            nothingToPlay = false
            lastError = null
            artworkByMediaId.clear()
            artworkByMediaId.pinnedKey = mediaItems.items.getOrNull(startIndex)?.mediaId
            exoPlayer.shuffleModeEnabled = false
            artworkByMediaId.putAll(mediaItems.artwork)
            exoPlayer.setMediaItems(mediaItems.items, startIndex, PlaybackConstants.TIME_UNSET)
            exoPlayer.prepare()
            exoPlayer.play()
            publishSnapshot()
            startPlaybackService()
            true
        }
        if (applied) launchArtworkBackfill(currentItems, startIndex)
    }

    /** Holds a batch of built [MediaItem]s together with their extracted artwork bytes. */
    private data class BuiltMediaItems(
        val items: List<MediaItem>,
        val artwork: Map<String, ByteArray>,
    )

    /**
     * Reads cached cover bytes without touching [artworkByMediaId] (safe on any thread).
     *
     * Order: injected test provider -> `coverArtLocalPath` file -> `covers/<songId>.jpg`.
     * Returns null when no local file exists or the read fails.
     */
    private fun cheapFileArtwork(item: QueueItem): ByteArray? {
        coverBytesProvider?.invoke(item.songId, item.coverArtLocalPath)?.let { return it }
        readFileBytes(item.coverArtLocalPath)?.let { return it }
        return readCachedCoverFile(item.songId)
    }

    private fun readFileBytes(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) null else f.readBytes().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readCachedCoverFile(songId: String): ByteArray? {
        coverBytesProvider?.let { provider ->
            // When a test provider is set, filesystem is bypassed entirely.
            return null
        }
        return try {
            val filesDir = try { context.filesDir } catch (_: Exception) { null } ?: return null
            val f = File(File(filesDir, "covers"), "$songId.jpg")
            if (!f.exists() || !f.isFile) null else f.readBytes().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the ExoPlayer timeline index for [songId]. Must run on the main thread.
     *
     * Prefers the queue-manager index when it still lines up with the timeline, then
     * falls back to a linear scan so a reorder/shuffle that moved the timeline cannot
     * cause an artwork write to land on the wrong track.
     */
    private fun findExoPlayerIndex(songId: String): Int? {
        val count = try { exoPlayer.mediaItemCount } catch (_: Exception) { return null }
        val qIdx = try { queueManager.indexOf(songId) } catch (_: Exception) { -1 }
        if (qIdx in 0 until count) {
            try {
                if (exoPlayer.getMediaItemAt(qIdx).mediaId == songId) return qIdx
            } catch (_: Exception) { /* fall through to scan */ }
        }
        for (i in 0 until count) {
            try {
                if (exoPlayer.getMediaItemAt(i).mediaId == songId) return i
            } catch (_: Exception) { continue }
        }
        return null
    }

    /**
     * Replaces the ExoPlayer item for [songId] with one carrying [art] in
     * `artworkData`, so the MediaSession/notification (which reads
     * `MediaMetadata.artworkData` via DefaultMediaNotificationProvider) shows art
     * for backfilled items, not just the start item.
     *
     * Must run on the main thread. Indices are resolved via [findExoPlayerIndex]
     * and the current item's position/play state is restored around the replace so
     * there is no audible glitch or restart.
     *
     * @return true when an item was replaced.
     */
    private fun replaceExoPlayerArtwork(songId: String, art: ByteArray): Boolean {
        val idx = findExoPlayerIndex(songId) ?: return false
        val existing = try {
            exoPlayer.getMediaItemAt(idx)
        } catch (_: Exception) {
            return false
        }
        if (existing.mediaMetadata.artworkData != null) return false
        val queueItem = try {
            queueManager.getOrNull(idx)?.takeIf { it.songId == songId }
        } catch (_: Exception) {
            null
        }
        // Rebuild from the canonical QueueItem when available so edited titles
        // stay in sync; otherwise reuse the timeline item's metadata.
        val updated = if (queueItem != null) {
            MediaItemMapper.toMediaItem(queueItem, art)
        } else {
            val meta = existing.mediaMetadata.buildUpon()
                .setArtworkData(art, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .build()
            existing.buildUpon().setMediaMetadata(meta).build()
        }
        val isCurrent = try {
            idx == exoPlayer.currentMediaItemIndex
        } catch (_: Exception) {
            false
        }
        val savedPosition = try { exoPlayer.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { 0L }
        val wasPlaying = try { exoPlayer.isPlaying } catch (_: Exception) { false }
        try {
            exoPlayer.replaceMediaItem(idx, updated)
        } catch (t: Exception) {
            Timber.e(t, "replaceExoPlayerArtwork: FAILED for $songId at $idx")
            return false
        }
        if (isCurrent) {
            try {
                exoPlayer.seekTo(idx, savedPosition)
                if (exoPlayer.playbackState == Player.STATE_IDLE) exoPlayer.prepare()
                if (wasPlaying && !exoPlayer.isPlaying) exoPlayer.play()
            } catch (t: Exception) {
                Timber.e(t, "replaceExoPlayerArtwork: restore FAILED for $songId")
            }
        }
        return true
    }

    /**
     * Converts each [QueueItem] into a Media3 [MediaItem].
     *
     * Cheap local sources (`coverArtLocalPath` / `covers/<songId>.jpg`) are embedded
     * synchronously for every item; the MP3 tag is only parsed for the start item so
     * `setMediaItems`/`play` are never delayed. Everything else is backfilled by
     * [launchArtworkBackfill].
     *
     * @param items queue items in playlist order.
     * @param startIndex index playback starts at; its artwork is embedded synchronously.
     * @return the MediaItem list plus a map of songId -> artwork bytes for embedded items.
     */
    private fun buildMediaItems(items: List<QueueItem>, startIndex: Int): BuiltMediaItems {
        val mediaItems = ArrayList<MediaItem>(items.size)
        val artwork = HashMap<String, ByteArray>(items.size.coerceAtMost(8))
        for ((index, item) in items.withIndex()) {
            val cheap = cheapFileArtwork(item)
            val art = cheap ?: if (index == startIndex) {
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
     * Extracts artwork for every queued item except [startIndex] in the background and
     * mirrors each hit onto the ExoPlayer timeline via [replaceExoPlayerArtwork].
     *
     * Previously this only filled [artworkByMediaId] + UI snapshot, so the
     * notification/lockscreen (which reads `artworkData` off the timeline) kept showing
     * art only for the start item. Now each hit updates the timeline item in place,
     * preserving the current position/play state.
     *
     * Runs one item at a time on [Dispatchers.IO]; map writes + timeline replaces happen
     * on the main thread where all player access lives. Exactly one backfill runs per
     * controller: a new call cancels the previous job. Aborts when [queueGeneration]
     * moves on so a stale queue can never write art into its replacement.
     *
     * @param items queue items in playlist order.
     * @param startIndex index whose artwork was already extracted during queue building.
     */
    private fun launchArtworkBackfill(items: List<QueueItem>, startIndex: Int) {
        cancelArtworkBackfill("queue swapped")
        val generation = queueGeneration
        val pending = items.filterIndexed { index, _ -> index != startIndex }
        if (pending.isEmpty()) return
        Timber.i("launchArtworkBackfill: START pending=${pending.size}, startIndex=$startIndex")
        artworkBackfillJob = scope.launch(Dispatchers.IO) {
            val batcher = ArtworkBackfillBatcher()
            for (item in pending) {
                ensureActive()
                if (generation != queueGeneration) {
                    Timber.i("launchArtworkBackfill: superseded, aborting")
                    return@launch
                }
                val art = cheapFileArtwork(item)
                    ?: runCatching { artworkExtractor.extractArtwork(item.filePath) }.getOrNull()
                    ?: continue
                val shouldPublish = withContext(Dispatchers.Main.immediate) {
                    if (generation != queueGeneration) return@withContext null
                    artworkByMediaId[item.songId] = art
                    replaceExoPlayerArtwork(item.songId, art)
                    val currentMediaId = try { exoPlayer.currentMediaItem?.mediaId } catch (_: Exception) { null }
                    batcher.shouldPublish(item.songId, currentMediaId)
                }
                if (shouldPublish == true) {
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == queueGeneration) publishSnapshot()
                    }
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
        val generation = ++queueGeneration
        currentPlaylistId = saved.playlistId
        val songs = persistence.restoreSongs(saved.queueSongIds)
        if (songs.isEmpty()) {
            Timber.w("PlayerController.restoreSavedState: no playable songs, clearing state")
            currentPlaylistId = null
            persistence.clear()
            return
        }
        when (val result = withContext(Dispatchers.IO) {
            // Existence/length checks per song are blocking disk I/O: keep them off main.
            queueBuilder.build(songs, saved.currentSongId)
        }) {
            is QueueBuildResult.NothingToPlay -> {
                Timber.w("PlayerController.restoreSavedState: nothing to play after rebuild")
                persistence.clear()
            }
            is QueueBuildResult.Ready -> {
                val clickedSongId = saved.currentSongId
                // Queue ordering and the start index are main-thread owned.
                val prepared = withContext(Dispatchers.Main.immediate) {
                    if (generation != queueGeneration) return@withContext null
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
                    val items = queueManager.queue
                    val start = if (clickedSongId != null) {
                        0
                    } else if (saved.shuffleEnabled) {
                        0
                    } else {
                        saved.currentSongId?.let { queueManager.indexOf(it) }?.coerceAtLeast(0) ?: 0
                    }
                    items to start
                }
                if (prepared == null) {
                    Timber.i("restoreSavedState: queue replaced while restoring, discarding result")
                    return
                }
                val (currentItems, startIndex) = prepared
                // Cover-byte reads and embedded-art decode are blocking: keep them off main.
                val mediaItems = withContext(Dispatchers.IO) {
                    buildMediaItems(currentItems, startIndex)
                }
                val applied = withContext(Dispatchers.Main.immediate) {
                    if (generation != queueGeneration) {
                        Timber.i("restoreSavedState: queue replaced while restoring, discarding result")
                        return@withContext false
                    }
                    nothingToPlay = false
                    lastError = null
                    artworkByMediaId.clear()
                    artworkByMediaId.pinnedKey = mediaItems.items.getOrNull(startIndex)?.mediaId
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
                    // Connect the service (a binder connection through the MediaController) even
                    // on a paused restore: the restored queue, position and notification must be
                    // available so the user's next play / media-button resume works immediately.
                    // This is ANR-safe because starting the service is a bind now, not
                    // `startForegroundService()`, so there is no 5s foreground contract to break
                    // while the player is prepared-but-paused.
                    startPlaybackService()
                    true
                }
                if (applied) launchArtworkBackfill(currentItems, startIndex)
                Timber.i("PlayerController.restoreSavedState: restored successfully")
            }
        }
    }

    /**
     * The persisted fields whose combined value decides whether a save is worth doing.
     *
     * Deliberately excludes fields that change on every position tick (the ticker updates
     * [_state] directly and never calls [saveState]); it mirrors exactly what
     * [PlayerStatePersistence.save] writes.
     */
    private data class PersistedSignature(
        val currentSongId: String?,
        val playlistId: String?,
        val queueSongIds: List<String>,
        val queueIndex: Int,
        val shuffleEnabled: Boolean,
        val repeatMode: RepeatMode,
        val volume: Float,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    /**
     * Saves the current player state to persistence (debounced).
     *
     * Only saves when there is a queue loaded. Cancels any pending save
     * and reschedules with a 2-second delay to avoid excessive writes
     * during rapid state changes (e.g. position ticker).
     *
     * The state is fingerprinted before writing and the write is skipped entirely when the
     * fingerprint matches the last one persisted, so ordinary ExoPlayer listener events on an
     * idle player no longer trigger the (batched) settings write on every event. The actual
     * Room write runs on [Dispatchers.IO].
     */
    private fun saveState() {
        val store = persistence ?: return
        if (nothingToPlay || queueManager.queue.isEmpty()) return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            // All reads below are main-thread owned (queueManager / _state).
            val snapshot = _state.value
            val queueSongIds = queueManager.queue.map { it.songId }
            val currentSongId = snapshot.currentItem?.mediaId
            val currentQueueIdx = queueManager.queue.indexOfFirst { it.songId == currentSongId }
            val signature = PersistedSignature(
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
            if (signature == lastSavedSignature) {
                Timber.d("PlayerController.saveState: unchanged, skipping persist")
                return@launch
            }
            withContext(Dispatchers.IO) {
                store.save(
                    currentSongId = signature.currentSongId,
                    playlistId = signature.playlistId,
                    queueSongIds = signature.queueSongIds,
                    queueIndex = signature.queueIndex,
                    shuffleEnabled = signature.shuffleEnabled,
                    repeatMode = signature.repeatMode,
                    volume = signature.volume,
                    positionMs = signature.positionMs,
                    wasPlaying = signature.wasPlaying,
                )
            }
            lastSavedSignature = signature
            Timber.d("PlayerController.saveState: saved idx=$currentQueueIdx, queueSize=${queueSongIds.size}")
        }
    }

    // --- Transport controls ----------------------------------------------------------

    /**
     * Resumes playback.
     *
     * A paused player can be left in [Player.STATE_IDLE] when its media notification is
     * dismissed: the foreground teardown stops the player, and `play()` alone only flips
     * `playWhenReady` on an idle player, so playback would never restart. Re-prepare first
     * and re-assert the foreground service so the queue, position and notification survive.
     */
    fun play() {
        Timber.i("PlayerController.play: START")
        val resumedFromIdle = ensurePrepared()
        exoPlayer.play()
        if (resumedFromIdle) {
            Timber.i("PlayerController.play: resumed from idle, re-asserting PlaybackService")
            startPlaybackService()
        }
    }

    /** Pauses playback. */
    fun pause() {
        Timber.i("PlayerController.pause: START")
        exoPlayer.pause()
    }

    /** Toggles between play and pause. */
    fun togglePlayPause() {
        Timber.i("PlayerController.togglePlayPause: START")
        if (exoPlayer.isPlaying) pause() else play()
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
        if (enabled != queueManager.isShuffled && queueManager.size == 0) {
            // Empty queue (fresh start / deleted playlist / cleared): no items to reorder,
            // but persist the flag so the next playPlaylist()/playPlaylistLazy() build
            // is randomized when enabled. handleQueueReady captures queueManager.isShuffled
            // before setQueue and shuffles the fresh queue accordingly.
            queueManager.setShuffleFlag(enabled)
            if (exoPlayer.shuffleModeEnabled) {
                exoPlayer.shuffleModeEnabled = false
            }
            publishSnapshot()
            return
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
     * @param coverUrl remote artwork URL, carried separately because [MediaItem] metadata
     *   does not preserve it.
     * @param coverArtLocalPath local artwork file path, preferred for offline covers.
     */
    fun addToQueueNext(
        songId: String,
        mediaItem: MediaItem,
        coverUrl: String? = null,
        coverArtLocalPath: String? = null,
    ) {
        Timber.i("PlayerController.addToQueueNext: songId=$songId")
        if (exoPlayer.mediaItemCount == 0) return
        val currentIdx = exoPlayer.currentMediaItemIndex

        // Prefer already-available local art so user-queued items carry artworkData
        // for the notification instead of null.
        var effectiveMediaItem = mediaItem
        if (effectiveMediaItem.mediaMetadata.artworkData == null) {
            val cached = artworkByMediaId[songId]
                ?: readFileBytes(coverArtLocalPath)
                ?: readCachedCoverFile(songId)
                ?: coverBytesProvider?.invoke(songId, coverArtLocalPath)
            if (cached != null) {
                val meta = effectiveMediaItem.mediaMetadata.buildUpon()
                    .setArtworkData(cached, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .build()
                effectiveMediaItem = effectiveMediaItem.buildUpon().setMediaMetadata(meta).build()
                artworkByMediaId[songId] = cached
            }
        } else {
            effectiveMediaItem.mediaMetadata.artworkData?.let { artworkByMediaId[songId] = it }
        }

        val mutation = queueManager.addToQueueNext(songId, effectiveMediaItem, currentIdx, coverUrl, coverArtLocalPath)
        if (mutation == null) {
            // Already correctly positioned: still patch the timeline when we now have
            // art it was missing, otherwise schedule an extractor backfill.
            val art = effectiveMediaItem.mediaMetadata.artworkData
            if (art != null) {
                if (replaceExoPlayerArtwork(songId, art)) publishSnapshot()
            } else {
                val qIdx = queueManager.indexOf(songId)
                queueManager.getOrNull(qIdx)?.let { launchSingleArtworkBackfill(it.songId, it.filePath) }
            }
            return
        }

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
        if (mutation is QueueMutation.Add && mutation.mediaItem.mediaMetadata.artworkData == null) {
            queueManager.getOrNull(mutation.index)?.let { launchSingleArtworkBackfill(it.songId, it.filePath) }
        }
    }

    /**
     * Extracts artwork for a single user-queued song and mirrors it onto the timeline.
     * Used when `addToQueueNext` had no cheap local bytes to embed synchronously.
     */
    private fun launchSingleArtworkBackfill(songId: String, filePath: String) {
        val generation = queueGeneration
        scope.launch(Dispatchers.IO) {
            ensureActive()
            if (generation != queueGeneration) return@launch
            val art = cheapFileArtwork(
                queueManager.getOrNull(queueManager.indexOf(songId)) ?: QueueItem(
                    songId = songId,
                    title = null,
                    artist = null,
                    album = null,
                    durationMs = null,
                    filePath = filePath,
                    indexInQueue = -1,
                ),
            ) ?: runCatching { artworkExtractor.extractArtwork(filePath) }.getOrNull()
            ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                if (generation != queueGeneration) return@withContext
                artworkByMediaId[songId] = art
                if (replaceExoPlayerArtwork(songId, art)) publishSnapshot()
            }
        }
    }

    /**
     * Mirrors edited display metadata onto the live queue, the ExoPlayer timeline and the
     * media session, then publishes a fresh snapshot.
     *
     * The queue (and so the queue editor) and the now-playing line are both re-read from
     * the timeline, so this is what makes a saved title/artist/album visible immediately
     * instead of only after the queue is rebuilt. Only metadata is touched — the URI,
     * the position and the play state are all restored around the item replacement, so
     * saving while a song is playing does not restart it.
     *
     * Must run on the main thread.
     *
     * @param songId the song database ID whose metadata was edited.
     * @param title the new track title.
     * @param artist the new artist name.
     * @param album the new album name, or `null` when the track has no album.
     * @return `true` when the song was queued and has been updated.
     */
    fun updateQueueItemMetadata(
        songId: String,
        title: String,
        artist: String,
        album: String?,
    ): Boolean {
        Timber.i("PlayerController.updateQueueItemMetadata: songId=$songId")
        val index = queueManager.updateMetadata(songId, title, artist, album)
        if (index < 0) return false
        if (index < exoPlayer.mediaItemCount) {
            val mediaItem = exoPlayer.getMediaItemAt(index)
            val metadata = mediaItem.mediaMetadata.buildUpon()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .build()
            val isCurrent = exoPlayer.currentMediaItem?.mediaId == songId
            val savedPosition = exoPlayer.currentPosition
            val wasPlaying = exoPlayer.isPlaying
            exoPlayer.replaceMediaItem(index, mediaItem.buildUpon().setMediaMetadata(metadata).build())
            if (isCurrent) {
                // Replacing the current item drops it back to the start, so put the
                // listener back where they were and keep the music running.
                exoPlayer.seekTo(exoPlayer.currentMediaItemIndex, savedPosition.coerceAtLeast(0L))
                ensurePrepared()
                if (wasPlaying) exoPlayer.play()
            }
        }
        publishSnapshot()
        return true
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

    // --- Sleep timer ---------------------------------------------------------------

    /**
     * Starts a sleep timer that pauses playback once [durationMs] has elapsed, replacing any
     * timer that is already running.
     *
     * The countdown lives on a monotonic deadline rather than on a tick counter, so it stays
     * correct while the app is backgrounded or the screen is off, as long as the playback
     * process stays alive. Nothing is written to disk: a process death clears the timer
     * rather than resurrecting a stale one.
     *
     * A duration shorter than [SleepTimerState.MIN_DURATION_MS] is rejected and any running
     * timer is cancelled, so a timer can never fire the instant it is started.
     *
     * @param durationMs how long playback may continue, in milliseconds.
     */
    fun startSleepTimer(durationMs: Long) {
        val now = elapsedRealtimeMs()
        val started = SleepTimerState.start(durationMs, now)
        if (!started.isActive) {
            Timber.w("PlayerController.startSleepTimer: rejected durationMs=$durationMs")
            cancelSleepTimer()
            return
        }
        Timber.i("PlayerController.startSleepTimer: durationMs=$durationMs")
        sleepTimer = started
        publishSnapshot()
        startSleepTicker()
    }

    /**
     * Cancels a running sleep timer and returns the player to the idle timer state.
     *
     * A no-op when no timer is running, so it never disturbs playback by itself.
     *
     * @return `true` when a timer was running and has now been cancelled.
     */
    fun cancelSleepTimer(): Boolean {
        Timber.i("PlayerController.cancelSleepTimer")
        val hadTimer = sleepTimer.isActive
        cancelSleepTimerJob()
        if (!hadTimer) return false
        sleepTimer = SleepTimerState.Inactive
        publishSnapshot()
        return true
    }

    /**
     * Counts the sleep timer down, publishing the remaining time as it goes.
     *
     * Ticks coarsely and derives every value from the deadline, so a slow or suspended
     * process catches up in one step instead of drifting, and nothing is scheduled at all
     * while no timer is running.
     */
    private fun startSleepTicker() {
        cancelSleepTimerJob()
        val generation = sleepTimerGeneration
        sleepTimerJob = scope.launch {
            while (isActive) {
                delay(SLEEP_TIMER_TICK_MS)
                ensureActive()
                if (generation != sleepTimerGeneration) return@launch
                val now = elapsedRealtimeMs()
                if (sleepTimer.hasExpired(now)) {
                    expireSleepTimer()
                    return@launch
                }
                publishSleepTimer(now)
            }
        }
    }

    /**
     * Fires the sleep timer: playback is paused and the timer resets to inactive.
     *
     * Playback is only paused — the queue, the position and the notification are left exactly
     * as they are, so the listener can resume straight away.
     */
    private fun expireSleepTimer() {
        Timber.i("PlayerController: sleep timer expired, pausing playback")
        sleepTimer = SleepTimerState.Inactive
        pause()
        publishSnapshot()
    }

    /** Cancels the ticker and invalidates anything it may already have queued. */
    private fun cancelSleepTimerJob() {
        sleepTimerGeneration++
        sleepTimerJob?.cancel()
        sleepTimerJob = null
    }

    /**
     * Pushes the current countdown into the state flow without re-reading the whole player.
     *
     * Used by the ticker so the countdown is not written to persistent player state once a
     * second; [publishSnapshot] carries the same fields for every other state change.
     */
    private fun publishSleepTimer(nowMs: Long) {
        val remaining = sleepTimer.remainingMs(nowMs)
        _state.update { current ->
            current.copy(
                sleepTimerActive = sleepTimer.isActive,
                sleepTimerRemainingMs = remaining,
                sleepTimerTotalMs = sleepTimer.totalMs,
            )
        }
    }

    /** Releases all resources held by this controller. */
    fun release() {
        Timber.i("PlayerController.release: START")
        tickerJob?.cancel()
        saveJob?.cancel()
        backfillJob?.cancel()
        cancelSleepTimerJob()
        cancelArtworkBackfill("controller released")
        exoPlayer.removeListener(this)
        mediaController?.release()
        mediaController = null
        scope.cancel()
    }

    /**
     * Stops playback and removes every trace of [playlistId] from the player.
     *
     * Called when a playlist is deleted: the queue, the ExoPlayer timeline, the artwork cache
     * and the persisted state all point at rows that no longer exist, so leaving any of them
     * behind would keep a deleted song on the mini player, in the media notification, and in
     * the next session's restore.
     *
     * Scoped by playlist id, so deleting one playlist can never interrupt another: a playlist
     * that is not the loaded one is a no-op. Passing `null` clears unconditionally.
     *
     * Everything a background job could still write is cancelled or invalidated first — the
     * pending state save (which would otherwise re-persist the deleted playlist two seconds
     * later), the lazy queue backfill and the artwork backfill — and the queue generation is
     * bumped so an in-flight build result is discarded instead of re-populating the queue.
     * `stop()` also drops the player to `STATE_IDLE`, which is what makes `PlaybackService`
     * tear down its foreground notification.
     *
     * Must run on the main thread.
     *
     * @param playlistId the deleted playlist's id, or `null` to clear unconditionally.
     * @return `true` when this playlist was the loaded one and the player was reset.
     */
    fun stopPlaylist(playlistId: String?): Boolean {
        if (playlistId != null && currentPlaylistId != playlistId) {
            Timber.i("PlayerController.stopPlaylist: $playlistId is not loaded (current=$currentPlaylistId), nothing to do")
            return false
        }
        Timber.i("PlayerController.stopPlaylist: clearing playlist=$playlistId")
        queueGeneration++
        currentPlaylistId = null
        saveJob?.cancel()
        saveJob = null
        backfillJob?.cancel()
        backfillJob = null
        cancelArtworkBackfill("playlist deleted")
        nothingToPlay = false
        lastError = null
        endOfQueueHandled = false
        artworkByMediaId.clear()
        queueManager.clear()
        exoPlayer.shuffleModeEnabled = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        publishSnapshot()
        lastSavedSignature = null
        persistence?.let { store -> scope.launch { store.clear() } }
        return true
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
                        ?: MediaItemMapper.toMediaItem(
                            queueItem,
                            artworkByMediaId[queueItem.songId] ?: cheapFileArtwork(queueItem),
                        ),
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

    /**
     * Ensures the player is prepared when idle but has media items queued.
     *
     * @return true when the player was idle and has just been prepared, false otherwise.
     */
    private fun ensurePrepared(): Boolean {
        Timber.d("PlayerController.ensurePrepared: START")
        if (exoPlayer.playbackState != Player.STATE_IDLE || exoPlayer.mediaItemCount <= 0) return false
        exoPlayer.prepare()
        return true
    }

    /**
     * Starts [PlaybackService] by BINDING it through a [MediaController] and keeps the
     * controller connected so the media notification appears.
     *
     * We deliberately do NOT call `Context.startForegroundService()` here. media3's
     * [androidx.media3.session.MediaSessionService] only promotes itself to the foreground
     * (calls `startForeground()`) once there is actually something to play. Starting it with a
     * plain intent therefore gives media3 no action/extras to act on, so
     * `MediaSessionService.onStartCommand` returns without ever calling `startForeground()`,
     * and Android kills the process ~5s later with
     * "Context.startForegroundService() did not then call Service.startForeground()".
     * Binding a [MediaController] to the [SessionToken] starts the service without that 5s
     * foreground contract, and media3 posts the notification and enters the foreground safely
     * (synchronously) when playback actually starts.
     *
     * Idempotent: the controller is built once and reused, so re-asserting the service after a
     * resume does not pile up connected controllers.
     */
    private fun startForegroundPlayback() {
        Timber.i("startPlaybackService: connecting MediaController to PlaybackService (bind)")
        if (mediaController != null) {
            Timber.i("startPlaybackService: reusing existing MediaController")
            return
        }
        val sessionToken = SessionToken(
            context,
            android.content.ComponentName(context, PlaybackService::class.java),
        )
        Timber.i("startPlaybackService: calling MediaController.Builder.buildAsync")
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                mediaController = future.get()
                Timber.i("startPlaybackService: MediaController connected OK, controller=${mediaController?.javaClass?.simpleName}")
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
        val now = elapsedRealtimeMs()
        _state.update { current ->
            PlayerStateMapper.toUiState(
                snapshot = snapshot,
                queue = queueManager.queue,
                nothingToPlay = nothingToPlay,
                lastError = lastError,
                sleepTimerActive = sleepTimer.isActive,
                sleepTimerRemainingMs = sleepTimer.remainingMs(now),
                sleepTimerTotalMs = sleepTimer.totalMs,
            )
        }
        saveState()
    }

    /** Creates a snapshot of every relevant ExoPlayer field. */
    private fun buildPlayerSnapshot(): PlayerSnapshot {
        val currentItem = exoPlayer.currentMediaItem
        // Keep the on-screen track's cover resident no matter how large the queue is.
        artworkByMediaId.pinnedKey = currentItem?.mediaId
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

    /**
     * LRU-bounded store of embedded cover bytes keyed by song id.
     *
     * Backed by an access-ordered [LinkedHashMap], so the least-recently-used entry is evicted
     * first once either [maxEntries] or [maxBytes] is exceeded; reads refresh recency. The
     * currently displayed track ([pinnedKey]) is never evicted, so the now-playing cover stays
     * available even when the queue is far larger than the cap.
     */
    private class BoundedArtworkCache(
        private val maxEntries: Int,
        private val maxBytes: Long,
    ) : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {

        /** Song id whose entry must survive eviction, or `null` when nothing is pinned. */
        var pinnedKey: String? = null

        private var totalBytes = 0L

        override fun put(key: String, value: ByteArray): ByteArray? {
            val previous = super.put(key, value)
            if (previous != null) totalBytes -= previous.size
            totalBytes += value.size
            evictIfNeeded()
            return previous
        }

        override fun putAll(from: Map<out String, ByteArray>) {
            for ((key, value) in from) put(key, value)
        }

        override fun remove(key: String): ByteArray? {
            val removed = super.remove(key)
            if (removed != null) totalBytes -= removed.size
            return removed
        }

        override fun clear() {
            super.clear()
            totalBytes = 0L
            pinnedKey = null
        }

        private fun evictIfNeeded() {
            val oldest = entries.iterator()
            while ((size > maxEntries || totalBytes > maxBytes) && oldest.hasNext()) {
                val entry = oldest.next()
                if (entry.key == pinnedKey) continue
                totalBytes -= entry.value.size
                oldest.remove()
            }
        }
    }

    companion object {

        /** Interval in milliseconds between position-ticker updates. */
        private const val TICK_INTERVAL_MS = 250L

        /**
         * Interval in milliseconds between sleep-ticker updates.
         *
         * Coarse on purpose: the countdown is a whole number of seconds, so anything finer
         * than this only wakes the main thread without changing what the user sees, and the
         * remaining time is recomputed from the deadline on every tick anyway.
         */
        private const val SLEEP_TIMER_TICK_MS = 500L

        /** Delay in milliseconds before persisting state (debounce). */
        private const val SAVE_DEBOUNCE_MS = 2000L

        /** Maximum number of embedded-cover byte arrays retained at once. */
        private const val MAX_ARTWORK_ENTRIES = 32

        /** Maximum total bytes of embedded covers retained at once (8 MiB). */
        private const val MAX_ARTWORK_BYTES = 8L * 1024L * 1024L

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
