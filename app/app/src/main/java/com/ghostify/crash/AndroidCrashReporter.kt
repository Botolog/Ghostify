package com.ghostify.crash

import android.content.Context
import android.util.Log
import com.ghostify.error.ErrorMapper
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import timber.log.Timber

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

    override fun report(crash: Crash): Boolean {
        Timber.e("FileLogCrashReporter.report: START")
        val result = runCatching {
            marker.markCrashDetected()
            val entry = buildString {
                append("== ${formatter.format(Instant.ofEpochMilli(crash.timestampMs))} ==\n")
                append("Thread: ${crash.thread.name}\n")
                append("Type: ${crash.type}\n")
                append("Message: ${crash.throwable.message}\n")
                append("Mapped: ${ErrorMapper.map(crash.throwable).code}\n")
                append(crash.stackTraceText)
                append("\n\n")
            }
            val previous = logFile.takeIf { it.exists() }?.readText()?.takeLast(MAX_LOG_CHARS) ?: ""
            logFile.writeText(previous + entry)
            Log.w(TAG, "Uncaught crash: ${crash.type}", crash.throwable)
            true
        }.getOrDefault(false)
        Timber.e("FileLogCrashReporter.report: returning $result")
        return result
    }

    companion object {
        private const val TAG = "GhostifyCrash"
        private const val MAX_LOG_CHARS = 64 * 1024
    }
}

/** [CrashMarker] backed by a private SharedPreferences file. */
class PrefsCrashMarker(context: Context) : CrashMarker {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun markCrashDetected() {
        Timber.e("PrefsCrashMarker.markCrashDetected: START")
        prefs.edit().putBoolean(KEY_WAS_CRASHED, true).apply()
    }

    override fun markCleanStartup() {
        Timber.i("PrefsCrashMarker.markCleanStartup: START")
        prefs.edit().putBoolean(KEY_WAS_CRASHED, false).apply()
    }

    override fun wasCrashDetected(): Boolean {
        Timber.i("PrefsCrashMarker.wasCrashDetected: START")
        val result = prefs.getBoolean(KEY_WAS_CRASHED, false)
        Timber.i("PrefsCrashMarker.wasCrashDetected: returning $result")
        return result
    }

    override fun clear() {
        Timber.i("PrefsCrashMarker.clear: START")
        prefs.edit().remove(KEY_WAS_CRASHED).apply()
    }

    companion object {
        private const val PREFS_NAME = "ghostify_crash_marker"
        private const val KEY_WAS_CRASHED = "was_crash_detected"
    }
}
