package xyz.botolog.ghostify.crash

/**
 * Immutable snapshot of an uncaught exception, ready for persistence.
 *
 * @param thread the thread on which the exception was thrown.
 * @param throwable the uncaught exception itself.
 * @param timestampMs epoch milliseconds when the crash occurred.
 */
data class Crash(
    val thread: Thread,
    val throwable: Throwable,
    val timestampMs: Long,
) {
    /** The fully-qualified class name of the exception. */
    val type: String get() = throwable.javaClass.name

    /** Formatted stack trace with indentation for log readability. */
    val stackTraceText: String
        get() = throwable.stackTrace.joinToString("\n") { "    at $it" }
}

/**
 * Persists crash facts so the next launch can recover and inform the user.
 *
 * Implementations must be fast, allocation-light, and must never throw — a crashing
 * crash reporter would mask the very failure it reports.
 *
 * @return true if the crash was successfully recorded, false otherwise.
 */
fun interface CrashReporter {
    fun report(crash: Crash): Boolean
}

/**
 * Records whether the previous shutdown ended in a crash. Used for optional
 * post-recovery UX (e.g. "we recovered after a crash") and for diagnostics.
 */
interface CrashMarker {
    /** Marks the current session as having ended in a crash. */
    fun markCrashDetected()

    /** Marks the current session as having started cleanly. */
    fun markCleanStartup()

    /** Returns true if the previous session ended in a crash. */
    fun wasCrashDetected(): Boolean

    /** Clears the crash marker. */
    fun clear()
}
