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
    }

    /**
     * Appends items to the end of the queue (used by lazy backfill).
     * Returns the newly added items.
     */
    fun appendItems(items: List<QueueItem>): List<QueueItem> {
        Timber.d("PlayerQueueManager.appendItems: ${items.size} items")
        _queue.addAll(items)
        return items
    }

    /**
     * Clears the entire queue.
     */
    fun clear() {
        Timber.i("PlayerQueueManager.clear")
        _queue.clear()
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
