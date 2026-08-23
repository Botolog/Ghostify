package xyz.botolog.ghostify.crash

import android.content.Context
import android.util.Log
import xyz.botolog.ghostify.error.ErrorMapper
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
 *
 * @param context the application context for file access.
 * @param marker the crash marker to flag unclean shutdowns.
 */
class FileLogCrashReporter(
    context: Context,
    private val marker: CrashMarker,
) : CrashReporter {

    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val formatter = DateTimeFormatter.ofPattern(TIMESTAMP_FORMAT)
        .withZone(ZoneId.systemDefault())

    override fun report(crash: Crash): Boolean {
        Timber.e("FileLogCrashReporter.report: START")
        val result = runCatching {
            marker.markCrashDetected()
            val entry = buildEntry(crash)
            val previous = logFile.takeIf { it.exists() }?.readText()?.takeLast(MAX_LOG_CHARS) ?: ""
            logFile.writeText(previous + entry)
            Log.w(TAG, "Uncaught crash: ${crash.type}", crash.throwable)
            true
        }.getOrDefault(false)
        Timber.e("FileLogCrashReporter.report: returning $result")
        return result
    }

    /** Builds a single crash log entry with timestamp, thread, type, and stack trace. */
    private fun buildEntry(crash: Crash): String = buildString {
        append("$SECTION_SEPARATOR${formatter.format(Instant.ofEpochMilli(crash.timestampMs))}$SECTION_SUFFIX\n")
        append("$THREAD_HEADER${crash.thread.name}\n")
        append("$TYPE_HEADER${crash.type}\n")
        append("$MESSAGE_HEADER${crash.throwable.message}\n")
        append("$MAPPED_HEADER${ErrorMapper.map(crash.throwable).code}\n")
        append(crash.stackTraceText)
        append("\n\n")
    }

    companion object {
        private const val TAG = "GhostifyCrash"
        private const val MAX_LOG_CHARS = 64 * 1024
        private const val LOG_FILE_NAME = "crash_log.txt"
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss z"
        private const val THREAD_HEADER = "Thread: "
        private const val TYPE_HEADER = "Type: "
        private const val MESSAGE_HEADER = "Message: "
        private const val MAPPED_HEADER = "Mapped: "
        private const val SECTION_SEPARATOR = "== "
        private const val SECTION_SUFFIX = " =="
    }
}

/**
 * [CrashMarker] backed by a private SharedPreferences file.
 *
 * @param context the application context for SharedPreferences access.
 */
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
