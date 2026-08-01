package com.ghostify.recovery

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
        val plan = KilledProcessRecoveryLogic.plan(dao.songsSnapshot(), dao.playlistsSnapshot())
        if (!plan.isEmpty) {
            dao.applyPlan(plan)
            log(
                "Recovery applied: reset ${plan.songResets.size} song(s), " +
                    "${plan.playlistResets.size} playlist(s)",
            )
        } else {
            log("Recovery: nothing to repair")
        }
        return RecoveryReport(plan)
    }
}
