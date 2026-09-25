package xyz.botolog.ghostify.player.core

import timber.log.Timber

/**
 * Builds a playback queue lazily from a song provider.
 *
 * Instead of loading all songs up front, this builder:
 * 1. Loads an initial batch of songs (including the start song) and returns them immediately.
 * 2. Provides a [loadMore] function the caller can use to fetch subsequent batches.
 *
 * This avoids the latency of loading 10K+ songs and validating all files before playback starts.
 *
 * @property fileValidator Validator used to check whether files exist on disk.
 * @property initialBatchSize Number of songs to load in the first batch.
 */
class LazyPlayerQueueBuilder(
    private val fileValidator: FileValidator = FileValidator.Default,
    private val initialBatchSize: Int = INITIAL_BATCH_SIZE,
) {
    private var allItems: MutableList<QueueItem> = mutableListOf()
    private var loadedCount: Int = 0
    private var startResolved: Boolean = false

    /**
     * Loads the initial batch of songs and returns a playable queue.
     *
     * @param songs First batch of songs from the provider (already fetched by caller).
     * @param startSongId Optional song ID to start from.
     * @return [QueueBuildResult.Ready] with the initial batch, or [QueueBuildResult.NothingToPlay].
     */
    fun buildInitial(songs: List<Song>, startSongId: String? = null): QueueBuildResult {
        Timber.i("LazyPlayerQueueBuilder.buildInitial: ${songs.size} songs, startSongId=$startSongId")
        allItems.clear()
        loadedCount = 0
        startResolved = false

        val items = songs
            .filter(Song::isDownloaded)
            .mapNotNull { createQueueItemIfPlayable(it) }
            .mapIndexed { index, item -> item.copy(indexInQueue = index) }

        allItems.addAll(items)
        loadedCount = songs.size

        if (allItems.isEmpty()) {
            return QueueBuildResult.NothingToPlay
        }

        val startIndex = resolveStartIndex(startSongId)
        Timber.i("LazyPlayerQueueBuilder.buildInitial: ${allItems.size} playable, startIndex=$startIndex")
        return QueueBuildResult.Ready(items = allItems.toList(), startIndex = startIndex, startSongId = startSongId)
    }

    /**
     * Loads the next batch of songs and returns new queue items to append.
     *
     * @param songs Next batch of songs from the provider.
     * @return List of new [QueueItem]s to append to the queue, or empty if no more playable songs.
     */
    fun loadMore(songs: List<Song>): List<QueueItem> {
        if (songs.isEmpty()) return emptyList()

        val existingIds = allItems.mapTo(mutableSetOf()) { it.songId }
        val newItems = songs
            .filter { it.id !in existingIds }
            .filter(Song::isDownloaded)
            .mapNotNull { createQueueItemIfPlayable(it) }
            .map { item ->
                val index = allItems.size
                allItems.add(item)
                item.copy(indexInQueue = index)
            }

        loadedCount += songs.size
        Timber.d("LazyPlayerQueueBuilder.loadMore: +${newItems.size} items, total=${allItems.size}, loaded=$loadedCount")
        return newItems
    }

    /** Whether all songs have been loaded (provider returned fewer than requested). */
    fun isFullyLoaded(lastBatchSize: Int, batchSize: Int): Boolean = lastBatchSize < batchSize

    /** Total number of queue items built so far. */
    val totalItems: Int get() = allItems.size

    /** Returns a snapshot copy of all queue items built so far. */
    fun allItemsCopy(): List<QueueItem> = allItems.toList()

    private fun createQueueItemIfPlayable(song: Song): QueueItem? {
        val path = song.filePath?.trim().orEmpty()
        if (path.isEmpty() || !fileValidator.exists(path)) {
            return null
        }
        return QueueItem(
            songId = song.id,
            title = song.title.ifBlank { null },
            artist = song.artists.ifBlank { null },
            album = song.album.ifBlank { null },
            durationMs = song.durationMs?.takeIf { it > 0L },
            filePath = path,
            indexInQueue = allItems.size,
            coverUrl = song.coverUrl,
        )
    }

    private fun resolveStartIndex(startSongId: String?): Int {
        if (startSongId == null) return 0
        val idx = allItems.indexOfFirst { it.songId == startSongId }
        return if (idx >= 0) idx else 0
    }

    companion object {
        const val INITIAL_BATCH_SIZE = 200
    }
}
