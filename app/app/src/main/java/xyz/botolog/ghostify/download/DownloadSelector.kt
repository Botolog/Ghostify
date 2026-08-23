package xyz.botolog.ghostify.download

import timber.log.Timber

/**
 * Decides which songs a `downloadAll`/`retry` run should pick up:
 * only PENDING and FAILED (T-042), never DOWNLOADED (T-043),
 * preserving playlist track order (T-045).
 */
object DownloadSelector {

    /**
     * Selects downloadable songs from the given list, sorted by playlist position.
     *
     * @param songs all songs in the playlist.
     * @return songs eligible for download, ordered by [SongRecord.position].
     */
    fun select(songs: List<SongRecord>): List<SongRecord> {
        Timber.i("DownloadSelector.select: START")
        val result = songs
            .filter { it.status.isDownloadable }
            .sortedBy { it.position }
        Timber.i("DownloadSelector.select: returning ${result.size} songs")
        return result
    }
}
