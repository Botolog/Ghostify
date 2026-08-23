package xyz.botolog.ghostify.recovery

import xyz.botolog.ghostify.crash.GhostifyCrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Orders cold-start work so the two resilience guarantees of the app hold:
 *
 *  1. The [GhostifyCrashHandler] is installed before anything else can run, so no early
 *     failure can escape unrecorded.
 *  2. Recovery — a fast, local, DB-only transaction — runs before any network call.
 *     Network-touching work must await [RecoveryGate] (which also covers "UI renders from
 *     DB before any network call", T-160: the first frame is fed by Room Flows, and
 *     recovery only ever reads/writes local DB state).
 *
 * @param crashHandler the uncaught exception handler to install.
 * @param recovery the killed-process recovery orchestrator.
 * @param recoveryGate the gate that blocks network work until recovery completes.
 * @param scope the coroutine scope for launching recovery in the background.
 */
class StartupBootstrap(
    private val crashHandler: GhostifyCrashHandler,
    private val recovery: KilledProcessRecovery,
    private val recoveryGate: RecoveryGate,
    private val scope: CoroutineScope,
) {
    /**
     * Performs cold-start initialization: installs the crash handler and
     * launches recovery in the background, opening the recovery gate when done.
     */
    fun onColdStart() {
        Timber.i("StartupBootstrap.onColdStart: START")
        crashHandler.install()
        scope.launch {
            val report = recovery.recover()
            recoveryGate.open(report)
        }
    }
}

/**
 * Blocks download/network work until the recovery run of this process has completed.
 * Opened exactly once per process.
 */
class RecoveryGate {

    private val _isRecovered = MutableStateFlow(false)
    private var lastReport: RecoveryReport? = null

    /** Observable state: true once recovery has completed. */
    val isRecovered: StateFlow<Boolean> = _isRecovered

    /**
     * Opens the gate, allowing blocked work to proceed.
     *
     * @param report the result of the recovery run.
     */
    fun open(report: RecoveryReport) {
        Timber.i("RecoveryGate.open: START")
        lastReport = report
        _isRecovered.value = true
        Timber.d("RecoveryGate: state changed to RECOVERED")
    }

    /** Suspends until recovery finished and returns the report. */
    suspend fun await(): RecoveryReport {
        Timber.i("RecoveryGate.await: START")
        _isRecovered.first { it }
        val result = lastReport ?: RecoveryReport(RecoveryPlan())
        Timber.i("RecoveryGate.await: returning $result")
        return result
    }
}
