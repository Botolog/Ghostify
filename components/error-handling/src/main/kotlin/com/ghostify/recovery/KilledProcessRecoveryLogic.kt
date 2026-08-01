package com.ghostify.recovery

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

    fun plan(songs: List<SongState>, playlists: List<PlaylistState>): RecoveryPlan {
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

        return RecoveryPlan(songResets, playlistResets)
    }

    /** True when a second recovery pass would change nothing (idempotency check). */
    fun isStable(songs: List<SongState>, playlists: List<PlaylistState>): Boolean =
        plan(songs, playlists).isEmpty
}
