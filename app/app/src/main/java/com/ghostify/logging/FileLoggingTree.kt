package com.ghostify.logging

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
    private val maxFileSizeBytes: Long = 1024 * 1024,
    private val maxFileCount: Int = 5,
) : Timber.Tree() {

    val logDir: File = File(context.filesDir, "logs").also { it.mkdirs() }

    private val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTsFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.DEBUG) return
        executor.execute { writeLog(priority, tag, message, t) }
    }

    private fun writeLog(priority: Int, tag: String?, message: String, t: Throwable?) {
        val ts = tsFormat.format(Date())
        val lvl = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val line = buildString {
            append("[$ts] $lvl/$tag: $message\n")
            if (t != null) append("${Log.getStackTraceString(t)}\n")
        }
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
}
