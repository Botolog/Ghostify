package com.ghostify.crash

import android.content.Context
import android.util.Log
import com.ghostify.error.ErrorMapper
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Android [CrashReporter]: persists a bounded crash log next to the app's files dir and
 * flags the crash marker so the next cold start can heal in-flight state.
 *
 * Guarantees: never throws (a failing reporter must not mask the crash), bounded log size,
 * no PII beyond stack traces, no dialogs.
 */
class FileLogCrashReporter(
    context: Context,
    private val marker: CrashMarker,
) : CrashReporter {

    private val logFile = File(context.filesDir, "crash_log.txt")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        .withZone(ZoneId.systemDefault())

    override fun report(crash: Crash): Boolean = runCatching {
        marker.markCrashDetected()
        val entry = buildString {
            append("== ${formatter.format(Instant.ofEpochMilli(crash.timestampMs))} ==\n")
            append("Thread: ${crash.thread.name}\n")
            append("Mapped: ${ErrorMapper.map(crash.throwable).code}\n")
            append(crash.stackTraceText)
            append("\n\n")
        }
        val previous = logFile.takeIf { it.exists() }?.readText()?.takeLast(MAX_LOG_CHARS) ?: ""
        logFile.writeText(previous + entry)
        Log.w(TAG, "Uncaught crash: ${crash.type}", crash.throwable)
        true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "GhostifyCrash"
        private const val MAX_LOG_CHARS = 64 * 1024
    }
}

/** [CrashMarker] backed by a private SharedPreferences file. */
class PrefsCrashMarker(context: Context) : CrashMarker {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun markCrashDetected() {
        prefs.edit().putBoolean(KEY_WAS_CRASHED, true).apply()
    }

    override fun markCleanStartup() {
        prefs.edit().putBoolean(KEY_WAS_CRASHED, false).apply()
    }

    override fun wasCrashDetected(): Boolean = prefs.getBoolean(KEY_WAS_CRASHED, false)

    override fun clear() {
        prefs.edit().remove(KEY_WAS_CRASHED).apply()
    }

    companion object {
        private const val PREFS_NAME = "ghostify_crash_marker"
        private const val KEY_WAS_CRASHED = "was_crash_detected"
    }
}
