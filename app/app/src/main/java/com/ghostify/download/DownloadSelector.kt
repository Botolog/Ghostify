package com.ghostify.download

/**
 * Decides which songs a `downloadAll`/`retry` run should pick up:
 * only PENDING and FAILED (T-042), never DOWNLOADED (T-043),
 * preserving playlist track order (T-045).
 */
object DownloadSelector {

    fun select(songs: List<SongRecord>): List<SongRecord> =
        songs
            .filter { it.status.isDownloadable }
            .sortedBy { it.position }
}
