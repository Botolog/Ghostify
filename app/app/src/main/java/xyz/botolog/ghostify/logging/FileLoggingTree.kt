package xyz.botolog.ghostify.logging

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Timber [Timber.Tree] that writes log lines to rotating files in `filesDir/logs/`.
 *
 * - Each file is capped at [maxFileSizeBytes] (default 1 MB).
 * - At most [maxFileCount] files are kept; oldest are deleted.
 * - Writes happen on a single background thread to avoid blocking the caller.
 */
class FileLoggingTree(
    private val context: Context,
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE_BYTES,
    private val maxFileCount: Int = DEFAULT_MAX_FILE_COUNT,
) : Timber.Tree() {

    val logDir: File = File(context.filesDir, LOG_DIR_NAME).also { it.mkdirs() }

    private val tsFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US)
    private val fileTsFormat = SimpleDateFormat(FILE_TIMESTAMP_FORMAT, Locale.US)
    private val executor = ThreadPoolExecutor(
        THREAD_POOL_CORE_COUNT,
        THREAD_POOL_MAX_COUNT,
        KEEP_ALIVE_MILLIS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.DEBUG) return
        executor.execute { writeLog(priority, tag, message, t) }
    }

    private fun writeLog(priority: Int, tag: String?, message: String, t: Throwable?) {
        val ts = tsFormat.format(Date())
        val lvl = priorityToLabel(priority)
        val line = buildString {
            append("[$ts] $lvl/$tag: $message\n")
            if (t != null) append("${Log.getStackTraceString(t)}\n")
        }
        appendToFile(line)
    }

    /** Maps Android log priority to a single-character label. */
    private fun priorityToLabel(priority: Int): String = when (priority) {
        Log.VERBOSE -> LABEL_VERBOSE
        Log.DEBUG -> LABEL_DEBUG
        Log.INFO -> LABEL_INFO
        Log.WARN -> LABEL_WARN
        Log.ERROR -> LABEL_ERROR
        Log.ASSERT -> LABEL_ASSERT
        else -> LABEL_UNKNOWN
    }

    /** Appends [line] to the current log file, rotating if needed. */
    private fun appendToFile(line: String) {
        try {
            val file = currentFile()
            rotateIfFull()
            FileOutputStream(file, true).use { it.write(line.toByteArray()) }
        } catch (_: Exception) {
            // logging must never crash
        }
    }

    private fun currentFile(): File {
        val existing = logFiles().firstOrNull()
        if (existing != null && existing.length() < maxFileSizeBytes) return existing
        return File(logDir, "app_${fileTsFormat.format(Date())}.log")
    }

    private fun rotateIfFull() {
        val files = logFiles()
        if (files.size >= maxFileCount) {
            files.drop(maxFileCount - 1).forEach { it.delete() }
        }
    }

    fun logFiles(): List<File> =
        logDir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun allLogContent(): String = buildString {
        for (f in logFiles().reversed()) {
            append("===== ${f.name} =====\n")
            append(f.readText())
        }
    }

    fun clearLogs() {
        logDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val DEFAULT_MAX_FILE_SIZE_BYTES = 1024L * 1024L
        private const val DEFAULT_MAX_FILE_COUNT = 5
        private const val LOG_DIR_NAME = "logs"
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS"
        private const val FILE_TIMESTAMP_FORMAT = "yyyy-MM-dd_HH-mm-ss"
        private const val THREAD_POOL_CORE_COUNT = 1
        private const val THREAD_POOL_MAX_COUNT = 1
        private const val KEEP_ALIVE_MILLIS = 0L
        private const val LABEL_VERBOSE = "V"
        private const val LABEL_DEBUG = "D"
        private const val LABEL_INFO = "I"
        private const val LABEL_WARN = "W"
        private const val LABEL_ERROR = "E"
        private const val LABEL_ASSERT = "A"
        private const val LABEL_UNKNOWN = "?"
    }
}
