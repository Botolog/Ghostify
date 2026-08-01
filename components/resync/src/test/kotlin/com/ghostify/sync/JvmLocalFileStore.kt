package com.ghostify.sync

import java.io.File

/**
 * Real-disk [LocalFileStore] for tests (temp dir), so file deletion, existence
 * and mtime behavior are genuine (T-058, T-063, T-065). Mirrors the semantics
 * of `MusicStore.exists`/`deleteSongFile` from the `file-management` component.
 */
class JvmLocalFileStore(private val root: File) : LocalFileStore {

    override fun exists(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return File(filePath).isFile
    }

    override fun delete(filePath: String) {
        if (filePath.isBlank()) return
        runCatching { File(filePath).delete() }
    }

    /** Writes a real file and returns its absolute path. */
    fun writeFile(relativeName: String, bytes: ByteArray = ByteArray(8)): String {
        val file = File(root, relativeName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun file(relativeName: String): File = File(root, relativeName)

    val rootDir: File get() = root
}
