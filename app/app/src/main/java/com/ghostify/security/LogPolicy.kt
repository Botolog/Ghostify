package com.ghostify.security

import java.io.PrintStream
import timber.log.Timber

/**
 * Ghostify logging policy — the single gateway through which every log line in
 * the app is emitted. It guarantees three invariants (PROJECT.md §component;
 * T-168, T-170):
 *
 *  1. **No secrets in logs.** Every message passes through [SecretRedactor]
 *     before it reaches the sink, so credential-shaped values can never be
 *     written to logcat or stored.
 *  2. **No full Spotify URLs / track metadata beyond debug need.** Every
 *     message passes through [SpotifySanitizer]. In release (PROD) builds a
 *     Spotify URL is reduced to `spotify:<resource>` (the id is stripped); in
 *     debug builds the short id is kept. Track titles/artists are only kept at
 *     DEBUG level and only for non-sensitive messages.
 *  3. **Debug-only chatter is dropped in production.** VERBOSE/DEBUG messages
 *     are suppressed unless [LogPolicy.isDebug] is true, so incidental detail
 *     never ships.
 *
 * The policy is pure JVM: the sink is injected, so unit tests can capture the
 * exact bytes that would be logged (T-168, T-170 run locally, no device).
 * The app wires the default sink to `android.util.Log` in `GhostifyApplication`
 * via [LogPolicy.setup].
 */
class LogPolicy private constructor(
    /** True when this is a debug build (wired from BuildConfig.DEBUG). */
    val isDebug: Boolean,
    /** Where sanitized lines go. Default: standard error. */
    private val sink: (LogLevel, String, String) -> Unit,
    private val out: PrintStream? = null,
) {

    companion object {
        /** Process-wide policy. Null until [setup] is called. */
        @Volatile
        var instance: LogPolicy? = null
            private set

        /**
         * Install the process-wide policy. Calling it again reconfigures the
         * instance (idempotent — safe to call in `Application.onCreate`).
         */
        @JvmStatic
        fun setup(
            isDebug: Boolean,
            sink: (LogLevel, String, String) -> Unit = { level, tag, msg ->
                System.err.println("${level.name}/$tag: $msg")
            },
        ): LogPolicy {
            Timber.i("LogPolicy.setup: START")
            val policy = LogPolicy(isDebug, sink)
            instance = policy
            Timber.d("LogPolicy: state changed to configured (isDebug=$isDebug)")
            return policy
        }

        /**
         * Convenience: log via the installed policy, or no-op if unset.
         *
         * Intentionally NOT `@JvmStatic`: this companion declaration shares its
         * JVM descriptor with the instance [log] below, so making it static would
         * clash (duplicate `(LogLevel,String,String,Boolean)` signature). Keeping
         * it as a Companion member is sufficient — all call sites are Kotlin
         * (which resolves `LogPolicy.log(...)` to the Companion) and the app is
         * Kotlin-only; Java callers use `LogPolicy.Companion.log(...)`.
         */
        fun log(level: LogLevel, tag: String, message: String, sensitive: Boolean = false) {
            instance?.log(level, tag, message, sensitive)
        }
    }

    /**
     * Emit one sanitized log line.
     *
     * @param sensitive true for messages that may contain user-library data
     *   (track titles, playlist names, file paths). Sensitive messages are
     *   sanitized to PROD strictness even in debug builds.
     */
    fun log(level: LogLevel, tag: String, message: String, sensitive: Boolean = false) {
        Timber.i("LogPolicy.log: START")
        if (level.isVerbose() && !isDebug) return
        val levelForSanitize = if (isDebug && !sensitive) SanitizeLevel.DEBUG else SanitizeLevel.PROD
        val redacted = SecretRedactor.redact(message)
        val sanitized = SpotifySanitizer.sanitize(redacted, levelForSanitize)
        sink(level, tag, sanitized)
        out?.println("${level.name}/$tag: $sanitized")
    }

    fun v(tag: String, message: String, sensitive: Boolean = false): Unit { Timber.i("LogPolicy.v: START"); log(LogLevel.VERBOSE, tag, message, sensitive) }
    fun d(tag: String, message: String, sensitive: Boolean = false): Unit { Timber.i("LogPolicy.d: START"); log(LogLevel.DEBUG, tag, message, sensitive) }
    fun i(tag: String, message: String, sensitive: Boolean = false): Unit { Timber.i("LogPolicy.i: START"); log(LogLevel.INFO, tag, message, sensitive) }
    fun w(tag: String, message: String, sensitive: Boolean = false): Unit { Timber.i("LogPolicy.w: START"); log(LogLevel.WARN, tag, message, sensitive) }
    fun e(tag: String, message: String, sensitive: Boolean = false): Unit { Timber.i("LogPolicy.e: START"); log(LogLevel.ERROR, tag, message, sensitive) }

    /**
     * Build a debug-only message for a track. Keeps a truncated `title - artist`
     * but never the URL, album, or cover. Returns null outside debug builds so
     * callers can skip building the string entirely.
     */
    fun debugTrackRef(trackTitle: String?, artists: String?, spotifyUrl: String?): String? {
        Timber.i("LogPolicy.debugTrackRef: START")
        if (!isDebug) return null
        val result = SpotifySanitizer.trackRef(trackTitle, artists, spotifyUrl, SanitizeLevel.DEBUG)
        Timber.i("LogPolicy.debugTrackRef: returning $result")
        return result
    }
}

enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

private fun LogLevel.isVerbose(): Boolean = this == LogLevel.VERBOSE || this == LogLevel.DEBUG

/** Per-message strictness for URL/metadata sanitization. */
enum class SanitizeLevel { PROD, DEBUG }
