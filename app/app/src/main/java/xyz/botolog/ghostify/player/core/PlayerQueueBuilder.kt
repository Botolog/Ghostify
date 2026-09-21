package xyz.botolog.ghostify.player.core

import timber.log.Timber
import java.io.File

/**
 * Decides whether a file on disk is safe to hand to the media player.
 *
 * Abstracted so the pure queue-building logic is unit-testable without touching the
 * filesystem, and so the app can later plug in stricter checks (e.g. spotdl's sidecar).
 */
fun interface FileValidator {
    /**
     * Checks whether the file at the given path is safe for playback.
     *
     * @param filePath Absolute path to the file to validate.
     * @return `true` if the file exists, is a regular file, and is non-empty.
     */
    fun isPlayable(filePath: String): Boolean

    /**
     * Cheap existence-only check used at queue-build time.
     *
     * Downloads already guarantee integrity (the download pipeline validates each MP3 before
     * its status flips), so queue building only verifies presence; emptiness is NOT checked.
     * Defaults to delegating to [isPlayable]; override for a cheaper implementation.
     *
     * @param filePath Absolute path to the file to check.
     * @return `true` if the file exists and is a regular file, regardless of size.
     */
    fun exists(filePath: String): Boolean = isPlayable(filePath)

    companion object {
        /** Default validator: files must exist, be regular files, and be non-empty. */
        val Default: FileValidator = DefaultFileValidator
    }
}

private object DefaultFileValidator : FileValidator {

    override fun isPlayable(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists() && file.isFile && file.length() > 0L
    }

    override fun exists(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists() && file.isFile
    }
}

/**
 * Builds the Media3 queue for a playlist.
 *
 * Contract:
 * - One [QueueItem] per song whose row is DOWNLOADED with a file path that passes
 *   [FileValidator.exists] — paths missing on disk are filtered out here. Integrity is not
 *   re-checked at queue time (the download pipeline validates each MP3 before its status
 *   flips), so empty files are allowed through.
 * - Input order is preserved, which must be the playlist order (the caller passes songs
 *   already sorted by playlist position).
 * - [QueueBuildResult.NothingToPlay] when no song is playable.
 * - The queue is a pure snapshot: it never observes the database. Rebuilding is an explicit
 *   opt-in by the caller (this is what keeps an already-playing queue stable).
 *
 * @property fileValidator Validator used to check whether files exist on disk.
 */
class PlayerQueueBuilder(
    private val fileValidator: FileValidator = FileValidator.Default,
) {
    /**
     * Builds a playback queue from the given songs.
     *
     * @param songs Songs of the playlist, in playlist order.
     * @param startSongId Optional song ID to start from; falls back to the first item.
     * @return [QueueBuildResult.Ready] with playable items and start index,
     *   or [QueueBuildResult.NothingToPlay] if no songs are playable.
     */
    fun build(songs: List<Song>, startSongId: String? = null): QueueBuildResult {
        Timber.i("PlayerQueueBuilder.build: START songs=${songs.size}, startSongId=$startSongId")
        val items = buildQueueItems(songs)

        if (items.isEmpty()) {
            Timber.i("PlayerQueueBuilder.build: returning NothingToPlay")
            return QueueBuildResult.NothingToPlay
        }

        val startIndex = resolveStartIndex(items, startSongId)
        Timber.i("PlayerQueueBuilder.build: returning Ready items=${items.size}, startIndex=$startIndex")
        return QueueBuildResult.Ready(items = items, startIndex = startIndex)
    }

    /**
     * Filters and maps songs into queue items, assigning queue indices.
     *
     * Only songs that are downloaded and pass the file validator are included.
     *
     * @param songs Input songs in playlist order.
     * @return Ordered list of [QueueItem]s with assigned indices.
     */
    private fun buildQueueItems(songs: List<Song>): List<QueueItem> =
        songs
            .filter(Song::isDownloaded)
            .mapNotNull { song -> createQueueItemIfPlayable(song) }
            .mapIndexed { index, item -> item.copy(indexInQueue = index) }

    /**
     * Creates a [QueueItem] for a song if its file exists on disk.
     *
     * @param song The song to create a queue item for.
     * @return A [QueueItem] if the file exists, or `null` if it should be skipped.
     */
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
            indexInQueue = INDEX_PLACEHOLDER,
            coverUrl = song.coverUrl,
            lyrics = song.lyrics,
        )
    }

    /**
     * Resolves the starting index for playback within the built queue.
     *
     * @param items Built queue items.
     * @param startSongId Optional song ID to start from.
     * @return The index of the matching song, or 0 if not found.
     */
    private fun resolveStartIndex(items: List<QueueItem>, startSongId: String?): Int =
        startSongId
            ?.let { id -> items.indexOfFirst { it.songId == id } }
            ?.takeIf { it >= 0 }
            ?: 0

    companion object {
        /** Placeholder index assigned before the final queue index is resolved. */
        private const val INDEX_PLACEHOLDER = -1
    }
}
