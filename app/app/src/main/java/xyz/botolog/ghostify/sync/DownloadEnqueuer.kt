package xyz.botolog.ghostify.sync

/**
 * Starts downloads for every PENDING track of a playlist.
 *
 * Implemented in the app by the DownloadManager (PROJECT.md §6.1). The contract
 * that makes "no duplicate downloads" hold under concurrency (T-068): the
 * implementation claims tracks atomically via `UPDATE songs SET status = QUEUED
 * WHERE playlist_id = ? AND status = PENDING`, so two racing enqueue calls can
 * claim a given track only once. (The Room `SongDao.updateStatus` write in the
 * `database` component provides the same single-writer guarantee inside a
 * SQLite transaction.)
 */
fun interface DownloadEnqueuer {
    suspend fun enqueuePendingDownloads(playlistId: String)
}
