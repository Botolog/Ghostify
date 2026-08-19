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
    fun isPlayable(filePath: String): Boolean

    companion object {
        /** Files must exist, be regular files and non-empty. */
        val Default: FileValidator = DefaultFileValidator
    }
}

private object DefaultFileValidator : FileValidator {
    override fun isPlayable(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists() && file.isFile && file.length() > 0L
    }
}

/**
 * Builds the Media3 queue for a playlist.
 *
 * Contract:
 * - One [QueueItem] per song whose row is DOWNLOADED with a file path that passes
 *   [FileValidator] — missing/corrupt-on-disk files are filtered out here.
 * - Input order is preserved, which must be the playlist order (the caller passes songs
 *   already sorted by playlist position).
 * - [QueueBuildResult.NothingToPlay] when no song is playable.
 * - The queue is a pure snapshot: it never observes the database. Rebuilding is an explicit
 *   opt-in by the caller (this is what keeps an already-playing queue stable).
 */
class PlayerQueueBuilder(
    private val fileValidator: FileValidator = FileValidator.Default,
) {
    /**
     * @param songs songs of the playlist, in playlist order.
     * @param startSongId optional song to start from; falls back to the first item.
     */
    fun build(songs: List<Song>, startSongId: String? = null): QueueBuildResult {
        Timber.i("PlayerQueueBuilder.build: START songs=${songs.size}, startSongId=$startSongId")
        val items = songs
            .filter(Song::isDownloaded)
            .mapNotNull { song ->
                val path = song.filePath?.trim().orEmpty()
                if (path.isEmpty() || !fileValidator.isPlayable(path)) {
                    null
                } else {
                    QueueItem(
                        songId = song.id,
                        title = song.title.ifBlank { null },
                        artist = song.artists.ifBlank { null },
                        album = song.album.ifBlank { null },
                        durationMs = song.durationMs?.takeIf { it > 0L },
                        filePath = path,
                        indexInQueue = -1, // assigned below
                        coverUrl = song.coverUrl,
                    )
                }
            }
            .mapIndexed { index, item -> item.copy(indexInQueue = index) }

        if (items.isEmpty()) {
            Timber.i("PlayerQueueBuilder.build: returning NothingToPlay")
            return QueueBuildResult.NothingToPlay
        }

        val startIndex = startSongId
            ?.let { id -> items.indexOfFirst { it.songId == id } }
            ?.takeIf { it >= 0 }
            ?: 0

        Timber.i("PlayerQueueBuilder.build: returning Ready items=${items.size}, startIndex=$startIndex")
        return QueueBuildResult.Ready(items = items, startIndex = startIndex)
    }
}
