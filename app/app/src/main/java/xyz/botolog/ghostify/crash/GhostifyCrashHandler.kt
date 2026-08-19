package xyz.botolog.ghostify.crash

import xyz.botolog.ghostify.error.ErrorMapper
import timber.log.Timber

/**
 * Top-level uncaught-exception handler.
 *
 * Responsibilities:
 *  1. Map any escaped [Throwable] to a typed [xyz.botolog.ghostify.error.AppError] and hand it to
 *     [reporter], which persists it for next-launch recovery. Normal flows catch and map
 *     failures at the boundary instead; this is the last line of defense.
 *  2. Show NO dialog. Every realistic failure is surfaced as a readable in-app message;
 *     there is no "app crashed" dialog anywhere in the app.
 *  3. Delegate to [previous] so the platform still terminates the process predictably and
 *     the OS can reclaim resources (files, sockets, foreground-service state). Deliberately
 *     suppressing process death (e.g. by looping) is avoided — it is an anti-pattern that
 *     leaves the app in an undefined state.
 *
 * Next-launch recovery: [CrashReporter] writes a marker + log before the process dies; on
 * the next cold start [xyz.botolog.ghostify.recovery.KilledProcessRecovery] resets any in-flight
 * state (e.g. DOWNLOADING songs back to PENDING) so nothing is stuck forever.
 */
class GhostifyCrashHandler(
    private val reporter: CrashReporter,
    private val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Timber.e(throwable, "GhostifyCrashHandler.uncaughtException: START")
        runCatching { ErrorMapper.map(throwable) }
        runCatching { reporter.report(Crash(thread, throwable, System.currentTimeMillis())) }
        previous?.uncaughtException(thread, throwable)
    }

    fun install() {
        Timber.i("GhostifyCrashHandler.install: START")
        Thread.setDefaultUncaughtExceptionHandler(this)
    }
}
