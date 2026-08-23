package xyz.botolog.ghostify.recovery

import timber.log.Timber

/**
 * Pure, deterministic computation of the state resets needed after a killed process.
 *
 * Rules (idempotent, defensive, never deletes data):
 *  - Songs stuck in QUEUED or DOWNLOADING are reset to PENDING so a later download run
 *    picks them up again. Nothing is left stuck in DOWNLOADING (T-159) and nothing is
 *    downloaded twice — a fresh run re-claims the rows before touching the network.
 *  - DOWNLOADED / FAILED / PENDING / REMOVED songs are never touched.
 *  - A playlist stuck in DOWNLOADING is brought back to a consistent status:
 *      READY when at least one song is downloaded, otherwise NEW. Per-song FAILED rows
 *      stay FAILED and remain individually retryable.
 *  - Every other playlist status (NEW / READY / ERROR) is left alone.
 */
object KilledProcessRecoveryLogic {

    /**
     * Computes the [RecoveryPlan] needed to restore consistency after a killed process.
     *
     * @param songs current snapshot of all songs.
     * @param playlists current snapshot of all playlists.
     * @return a plan with the minimal set of resets needed.
     */
    fun plan(songs: List<SongState>, playlists: List<PlaylistState>): RecoveryPlan {
        Timber.i("KilledProcessRecoveryLogic.plan: START")
        val songResets = songs.asSequence()
            .filter { it.status == SongStatus.QUEUED || it.status == SongStatus.DOWNLOADING }
            .map { SongReset(it.id) }
            .toList()

        val playlistResets = playlists.asSequence()
            .filter { it.status == PlaylistStatus.DOWNLOADING }
            .map {
                PlaylistReset(
                    id = it.id,
                    toStatus = if (it.downloadedCount > 0) PlaylistStatus.READY else PlaylistStatus.NEW,
                )
            }
            .toList()

        val result = RecoveryPlan(songResets, playlistResets)
        Timber.i("KilledProcessRecoveryLogic.plan: returning $result")
        return result
    }

    /**
     * True when a second recovery pass would change nothing (idempotency check).
     *
     * @param songs current snapshot of all songs.
     * @param playlists current snapshot of all playlists.
     * @return true if the state is already stable.
     */
    fun isStable(songs: List<SongState>, playlists: List<PlaylistState>): Boolean {
        Timber.i("KilledProcessRecoveryLogic.isStable: START")
        val result = plan(songs, playlists).isEmpty
        Timber.i("KilledProcessRecoveryLogic.isStable: returning $result")
        return result
    }
}
