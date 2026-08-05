package com.ghostify.recovery

import timber.log.Timber

/**
 * Orchestrates killed-process recovery: compute the [RecoveryPlan] from the current DB
 * snapshot, apply it atomically, and report what changed.
 *
 * Runs at every cold start (in [StartupBootstrap]) before any network work, and is
 * idempotent — running it twice is a no-op the second time.
 */
class KilledProcessRecovery(
    private val dao: RecoveryDao,
    private val log: (String) -> Unit = {},
) {
    fun recover(): RecoveryReport {
        Timber.i("KilledProcessRecovery.recover: START")
        val plan = KilledProcessRecoveryLogic.plan(dao.songsSnapshot(), dao.playlistsSnapshot())
        if (!plan.isEmpty) {
            dao.applyPlan(plan)
            Timber.d("KilledProcessRecovery: state changed to PLAN_APPLIED")
            log(
                "Recovery applied: reset ${plan.songResets.size} song(s), " +
                    "${plan.playlistResets.size} playlist(s)",
            )
        } else {
            Timber.d("KilledProcessRecovery: state changed to NO_OP")
            log("Recovery: nothing to repair")
        }
        val result = RecoveryReport(plan)
        Timber.i("KilledProcessRecovery.recover: returning $result")
        return result
    }
}
