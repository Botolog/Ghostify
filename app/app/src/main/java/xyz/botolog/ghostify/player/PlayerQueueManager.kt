package xyz.botolog.ghostify.player

import androidx.media3.common.MediaItem
import xyz.botolog.ghostify.player.core.QueueItem
import timber.log.Timber

/**
 * Single source of truth for the playback queue ordering and user-queued tracking.
 *
 * Owns a [MutableList] of [QueueItem]s. Every mutation (add, reorder, clear) happens here.
 * The caller (PlayerController) mirrors each operation on ExoPlayer.
 *
 * The [QueueItem.queuedByUser] flag lives on each item — no separate tracking set.
 *
 * All methods must be called from the main thread.
 */
class PlayerQueueManager {

    private val _queue = mutableListOf<QueueItem>()

    /** Stores the original (unshuffled) queue order for restoration when shuffle is turned off. */
    private val _originalQueue = mutableListOf<QueueItem>()

    /** Whether the queue is currently in shuffled order. */
    var isShuffled: Boolean = false
        private set

    /** Read-only snapshot of the current queue. */
    val queue: List<QueueItem> get() = _queue.toList()

    /** The number of items in the queue. */
    val size: Int get() = _queue.size

    // ── Query ────────────────────────────────────────────────────────────

    /** Returns the index of the item with the given song ID, or -1 if not found. */
    fun indexOf(songId: String): Int = _queue.indexOfFirst { it.songId == songId }

    /** Returns the item at the given index, or null if out of bounds. */
    fun getOrNull(index: Int): QueueItem? = _queue.getOrNull(index)

    // ── Initialization ───────────────────────────────────────────────────

    /**
     * Replaces the entire queue with [items]. All items are marked as not user-queued.
     */
    fun setQueue(items: List<QueueItem>) {
        Timber.i("PlayerQueueManager.setQueue: ${items.size} items")
        _queue.clear()
        _queue.addAll(items.map { it.copy(queuedByUser = false) })
        _originalQueue.clear()
        isShuffled = false
    }

    /**
     * Replaces the entire queue with [items], preserving existing [QueueItem.queuedByUser] flags
     * for items whose [QueueItem.songId] was already present.
     */
    fun setQueuePreservingFlags(items: List<QueueItem>) {
        Timber.i("PlayerQueueManager.setQueuePreservingFlags: ${items.size} items")
        val previousFlags = _queue.associate { it.songId to it.queuedByUser }
        _queue.clear()
        _queue.addAll(items.map { it.copy(queuedByUser = previousFlags[it.songId] ?: false) })
        _originalQueue.clear()
        isShuffled = false
    }

    /**
     * Shuffles the queue and stores the current order in `_originalQueue` for later restoration.
     *
     * @param keepFirstPinned when true the first item keeps its position and only the rest is
     *   shuffled (used when playback starts from a clicked song that must stay first).
     */
    fun shuffle(keepFirstPinned: Boolean = false) {
        if (_queue.isEmpty()) return
        if (!isShuffled) {
            _originalQueue.clear()
            _originalQueue.addAll(_queue)
        }
        val pinned = if (keepFirstPinned) _queue.first() else null
        val shuffled = _queue.drop(if (keepFirstPinned) 1 else 0).shuffled()
        _queue.clear()
        if (pinned != null) _queue.add(pinned)
        _queue.addAll(shuffled)
        isShuffled = true
        Timber.i("PlayerQueueManager.shuffle: shuffled ${_queue.size} items")
    }

    fun shuffle(anchorIndex: Int) {
        shuffleAfter(anchorIndex)
    }

    fun shuffleAfter(activeIndex: Int) {
        if (activeIndex !in _queue.indices) return
        if (!isShuffled) {
            _originalQueue.clear()
            _originalQueue.addAll(_queue)
        }
        val suffixStart = activeIndex + 1
        if (suffixStart < _queue.size) {
            val suffix = _queue.subList(suffixStart, _queue.size).toList()
            _queue.subList(suffixStart, _queue.size).clear()
            _queue.addAll(suffix.shuffled())
        }
        isShuffled = true
        Timber.i("PlayerQueueManager.shuffleAfter: anchored at $activeIndex, shuffled ${_queue.size} items")
    }

    fun reshuffle() {
        if (_queue.isEmpty()) return
        if (!isShuffled) {
            _originalQueue.clear()
            _originalQueue.addAll(_queue)
        }
        if (_queue.size == 1) {
            isShuffled = true
            return
        }
        val reshuffled = _queue.shuffled()
        _queue.clear()
        _queue.addAll(reshuffled)
        isShuffled = true
        Timber.i("PlayerQueueManager.reshuffle: reshuffled ${_queue.size} items")
    }

    /** Restores the queue to its original (unshuffled) order. */
    fun unshuffle() {
        isShuffled = false
        if (_originalQueue.isEmpty()) return
        _queue.clear()
        _queue.addAll(_originalQueue)
        _originalQueue.clear()
        Timber.i("PlayerQueueManager.unshuffle: restored ${_queue.size} items")
    }

    /**
     * Appends items to the end of the queue (used by lazy backfill).
     * Returns the newly added items.
     */
    fun appendItems(items: List<QueueItem>): List<QueueItem> {
        Timber.d("PlayerQueueManager.appendItems: ${items.size} items")
        _queue.addAll(items)
        if (isShuffled) _originalQueue.addAll(items)
        return items
    }

    /**
     * Clears the entire queue.
     */
    fun clear() {
        Timber.i("PlayerQueueManager.clear")
        _queue.clear()
        _originalQueue.clear()
        isShuffled = false
    }

    // ── Mutations ────────────────────────────────────────────────────────

    /**
     * Adds a song to the queue, positioned after all user-queued songs.
     *
     * - If the song is currently playing, does nothing.
     * - If the song is already in the queue and correctly positioned, marks it as user-queued.
     * - If the song is already in the queue but wrong position, moves it.
     * - If the song is not in the queue, inserts it at the target position.
     *
     * @param songId the song database ID.
     * @param mediaItem built from the song's file and metadata (used to create QueueItem if new).
     * @param currentIndex the currently playing item's index.
     * @return a [QueueMutation] describing what happened, or null if no action was taken.
     */
    fun addToQueueNext(songId: String, mediaItem: MediaItem, currentIndex: Int): QueueMutation? {
        Timber.i("PlayerQueueManager.addToQueueNext: songId=$songId, currentIndex=$currentIndex")
        if (_queue.isEmpty()) return null
        if (currentIndex !in _queue.indices) return null

        val currentSongId = _queue.getOrNull(currentIndex)?.songId
        if (currentSongId == songId) {
            Timber.d("addToQueueNext: song is currently playing, ignoring")
            return null
        }

        // Find target: after the last user-queued song in the "next" section.
        var targetIdx = currentIndex + 1
        for (i in (currentIndex + 1) until _queue.size) {
            if (_queue[i].queuedByUser) {
                targetIdx = i + 1
            }
        }
        targetIdx = targetIdx.coerceAtMost(_queue.size)

        val existingIdx = indexOf(songId)

        if (existingIdx >= 0) {
            // Already in queue — check if it's in the right spot.
            if (existingIdx == targetIdx || existingIdx == targetIdx - 1) {
                Timber.d("addToQueueNext: already at correct position ($existingIdx), marking as user-queued")
                _queue[existingIdx] = _queue[existingIdx].copy(queuedByUser = true)
                val originalIdx = _originalQueue.indexOfFirst { it.songId == songId }
                if (originalIdx >= 0) {
                    _originalQueue[originalIdx] = _originalQueue[originalIdx].copy(queuedByUser = true)
                }
                return null
            }
            // Move existing item.
            // Remove first, then adjust target: if the removed item was before the
            // target, the target shifts left by 1.
            Timber.d("addToQueueNext: moving from $existingIdx to $targetIdx")
            val moved = _queue.removeAt(existingIdx).copy(queuedByUser = true)
            val adjustedTarget = if (existingIdx < targetIdx) targetIdx - 1 else targetIdx
            val insertAt = adjustedTarget.coerceAtMost(_queue.size)
            _queue.add(insertAt, moved)
            val originalIdx = _originalQueue.indexOfFirst { it.songId == songId }
            if (originalIdx >= 0) {
                _originalQueue[originalIdx] = _originalQueue[originalIdx].copy(queuedByUser = true)
            }
            return QueueMutation.Move(existingIdx, insertAt)
        } else {
            // New item.
            Timber.d("addToQueueNext: inserting new item at $targetIdx")
            val coreItem = QueueItem(
                songId = songId,
                title = mediaItem.mediaMetadata.title?.toString(),
                artist = mediaItem.mediaMetadata.artist?.toString(),
                album = mediaItem.mediaMetadata.albumTitle?.toString(),
                durationMs = mediaItem.mediaMetadata.durationMs,
                filePath = mediaItem.localConfiguration?.uri?.path.orEmpty(),
                indexInQueue = targetIdx,
                coverUrl = null,
                queuedByUser = true,
            )
            val insertAt = targetIdx.coerceAtMost(_queue.size)
            _queue.add(insertAt, coreItem)
            if (isShuffled) _originalQueue.add(coreItem)
            return QueueMutation.Add(insertAt, mediaItem)
        }
    }

    /**
     * Reorders the queue by moving an item from one position to another.
     *
     * @return true if the move was performed, false if invalid or no-op.
     */
    fun reorder(fromIndex: Int, toIndex: Int): Boolean {
        Timber.i("PlayerQueueManager.reorder: from=$fromIndex, to=$toIndex")
        if (fromIndex == toIndex) return false
        if (fromIndex !in _queue.indices) return false
        if (toIndex !in _queue.indices) return false

        val moved = _queue.removeAt(fromIndex)
        _queue.add(toIndex, moved)
        return true
    }
}

/**
 * Describes what [PlayerQueueManager.addToQueueNext] did to the queue.
 */
sealed class QueueMutation {
    /** A new item was inserted at [index]. */
    data class Add(val index: Int, val mediaItem: MediaItem) : QueueMutation()

    /** An existing item was moved from [from] to [to]. */
    data class Move(val from: Int, val to: Int) : QueueMutation()
}
