package xyz.botolog.ghostify.download

import timber.log.Timber

/**
 * Crash recovery (T-051 / T-159). When the process is killed mid-download a song
 * can be left in DOWNLOADING or QUEUED. On the next app start these are reset to
 * PENDING so the user can simply press download again — no stuck states, no
 * "downloading forever" entries.
 *
 * [DownloadQueueRunner] also performs the same recovery per-playlist at the start
 * of every run, so this class only needs to cover playlists that are *not* being
 * re-downloaded right away.
 */
class DownloadRecovery(private val repo: DownloadRepository) {

    suspend fun recoverAll() {
        Timber.i("DownloadRecovery.recoverAll: START")
        for (playlistId in repo.allPlaylistIds()) {
            recoverPlaylist(playlistId)
        }
    }

    suspend fun recoverPlaylist(playlistId: String) {
        Timber.i("DownloadRecovery.recoverPlaylist: START")
        repo.songsFor(playlistId)
            .filter { it.status.isRecoverable }
            .forEach { repo.setStatus(it.id, DownloadStatus.PENDING) }
    }
}
