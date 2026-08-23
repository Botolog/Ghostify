package xyz.botolog.ghostify.download

import timber.log.Timber

/**
 * Enforces the legal download status transitions (T-044):
 *
 *   PENDING  -> QUEUED                                  (enqueue)
 *   FAILED   -> QUEUED                                  (retry)
 *   QUEUED   -> DOWNLOADING | PENDING                   (start / cancel-before-start)
 *   DOWNLOADING -> DOWNLOADED | FAILED | CANCELED | PENDING
 *                                                     (ok / err / user-cancel / crash-recovery)
 *   CANCELED -> PENDING                                 (recovery / re-queue)
 *   DOWNLOADED -> (nothing)                             (never re-downloaded, T-043)
 *
 * Any other combination is rejected with [IllegalStateTransition]. This is a
 * single source of truth used by the runner, the Room repository mapping and the
 * tests.
 */
class IllegalStateTransition(from: DownloadStatus, to: DownloadStatus) :
    IllegalStateException("Illegal download status transition: $from -> $to")

/**
 * State machine that governs legal [DownloadStatus] transitions.
 *
 * All status mutations in the download pipeline must go through this object
 * to guarantee database consistency.
 */
object SongStateMachine {

    private val transitions: Map<DownloadStatus, Set<DownloadStatus>> = mapOf(
        DownloadStatus.PENDING to setOf(DownloadStatus.QUEUED),
        DownloadStatus.FAILED to setOf(DownloadStatus.QUEUED),
        DownloadStatus.QUEUED to setOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING),
        DownloadStatus.DOWNLOADING to setOf(
            DownloadStatus.DOWNLOADED,
            DownloadStatus.FAILED,
            DownloadStatus.CANCELED,
            DownloadStatus.PENDING,
        ),
        DownloadStatus.CANCELED to setOf(DownloadStatus.PENDING),
        DownloadStatus.DOWNLOADED to emptySet(),
    )

    /**
     * Checks whether a transition from [from] to [to] is legal.
     *
     * @param from the current status.
     * @param to the desired target status.
     * @return `true` if the transition is allowed.
     */
    fun canTransition(from: DownloadStatus, to: DownloadStatus): Boolean {
        Timber.i("SongStateMachine.canTransition: START")
        val result = transitions[from]?.contains(to) == true
        Timber.i("SongStateMachine.canTransition: returning $result")
        return result
    }

    /**
     * Validates and returns the target status, or throws [IllegalStateTransition].
     *
     * @param from the current status.
     * @param to the desired target status.
     * @return [to] if the transition is legal.
     * @throws IllegalStateTransition if the transition is not allowed.
     */
    fun requireTransition(from: DownloadStatus, to: DownloadStatus): DownloadStatus {
        Timber.i("SongStateMachine.requireTransition: START")
        if (!canTransition(from, to)) {
            Timber.e("SongStateMachine: transition FAILED $from -> $to")
            throw IllegalStateTransition(from, to)
        }
        Timber.d("SongStateMachine: state changed to $to")
        return to
    }

    /**
     * Returns the set of statuses reachable from [from].
     *
     * @param from the current status.
     * @return the set of legal target statuses.
     */
    fun legalTargets(from: DownloadStatus): Set<DownloadStatus> {
        Timber.i("SongStateMachine.legalTargets: START")
        val result = transitions[from] ?: emptySet()
        Timber.i("SongStateMachine.legalTargets: returning $result")
        return result
    }
}
