package xyz.botolog.ghostify.crash

/** Immutable snapshot of an uncaught exception, ready for persistence. */
data class Crash(
    val thread: Thread,
    val throwable: Throwable,
    val timestampMs: Long,
) {
    val type: String get() = throwable.javaClass.name
    val stackTraceText: String
        get() = throwable.stackTrace.joinToString("\n") { "    at $it" }
}

/**
 * Persists crash facts so the next launch can recover and inform the user.
 *
 * Implementations must be fast, allocation-light, and must never throw — a crashing
 * crash reporter would mask the very failure it reports.
 */
fun interface CrashReporter {
    fun report(crash: Crash): Boolean
}

/**
 * Records whether the previous shutdown ended in a crash. Used for optional
 * post-recovery UX (e.g. "we recovered after a crash") and for diagnostics.
 */
interface CrashMarker {
    fun markCrashDetected()
    fun markCleanStartup()
    fun wasCrashDetected(): Boolean
    fun clear()
}
