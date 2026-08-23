package xyz.botolog.ghostify.file

import java.io.File

/**
 * Thin abstraction over the handful of filesystem operations [MusicStore]
 * needs. Keeping file I/O behind this interface means the store logic is fully
 * testable on the JVM against a temp directory, and a faked implementation can
 * inject failures (e.g. "disk full" scenarios) without touching real storage.
 *
 * The default [JvmFileSystem] uses `java.io.File`, which behaves identically on
 * Android and the JVM, so the same implementation ships in the app.
 */
interface FileSystem {

    fun exists(file: File): Boolean

    /** True only for an existing regular file (not a directory). */
    fun isFile(file: File): Boolean

    fun length(file: File): Long

    /** Direct children of [dir]; empty when the directory is missing/unreadable. */
    fun listFiles(dir: File): List<File>

    /** Returns true when [file] is gone after the call. */
    fun delete(file: File): Boolean

    fun totalSpace(file: File): Long

    fun freeSpace(file: File): Long

    /** Writes [bytes], creating parent directories. True on success. */
    fun writeBytes(file: File, bytes: ByteArray): Boolean
}

/** Production implementation backed by [java.io.File]. */
object JvmFileSystem : FileSystem {

    override fun exists(file: File): Boolean = file.exists()

    override fun isFile(file: File): Boolean = file.isFile

    override fun length(file: File): Long = if (file.isFile) file.length() else 0L

    override fun listFiles(dir: File): List<File> = dir.listFiles()?.toList() ?: emptyList()

    override fun delete(file: File): Boolean = file.delete()

    override fun totalSpace(file: File): Long = file.totalSpace

    override fun freeSpace(file: File): Long = file.freeSpace

    override fun writeBytes(file: File, bytes: ByteArray): Boolean = try {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (_: Throwable) {
        false
    }
}
