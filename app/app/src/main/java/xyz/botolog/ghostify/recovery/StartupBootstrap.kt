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
 */
class StartupBootstrap(
    private val crashHandler: GhostifyCrashHandler,
    private val recovery: KilledProcessRecovery,
    private val recoveryGate: RecoveryGate,
    private val scope: CoroutineScope,
) {
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

    val isRecovered: StateFlow<Boolean> = _isRecovered

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
