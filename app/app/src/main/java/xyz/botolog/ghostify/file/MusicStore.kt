package xyz.botolog.ghostify.file

import java.io.File

/**
 * The local MP3 store: output-path resolution, deletion of song files and
 * their spotdl sidecars, orphan detection/cleanup, file-exists checks for
 * re-sync, and disk-space reporting.
 *
 * All storage access goes through [FileSystem] rooted at [root], so the class
 * is 100% JVM-testable and carries no Android dependency. The Android app wires
 * the root from `Context.getExternalFilesDir(null)` (app-scoped, no storage
 * permission) via [AppStorageDir].
 *
 * Layout: every MP3 lives directly in [root] as `{artists} - {title}.mp3`;
 * spotdl writes a sidecar `<file>.mp3.spotdl` next to each MP3, which the
 * re-sync logic relies on to avoid re-downloading existing tracks.
 */
class MusicStore(
    root: File,
    private val fs: FileSystem = JvmFileSystem,
) {

    companion object {
        /** Suffix spotdl appends to the MP3 path for its metadata sidecar. */
        const val SIDECAR_SUFFIX = ".spotdl"
    }

    /** Absolute, normalized root directory. */
    @Volatile
    var rootDir: File = root
        private set

    /** Updates the store root directory (e.g. when the user changes it in Settings). */
    fun updateRoot(newRoot: File) {
        rootDir = newRoot
        ensureRoot()
    }

    /** Creates the store directory if it does not exist. True if present afterwards. */
    fun ensureRoot(): Boolean = fs.exists(rootDir) || rootDir.mkdirs()

    // ---- Output path resolution (T-150) ------------------------------------

    /**
     * The exact file the download will produce for a track, derived from the
     * same template spotdl uses. Deterministic by construction: the DB
     * `file_path` is always this path, so it always matches disk after download.
     */
    fun resolveOutputPath(artists: String, title: String): File =
        rootDir.resolve(FileNames.trackFileName(artists, title))

    /** DB form of [resolveOutputPath] — the absolute path string stored in `songs.file_path`. */
    fun dbFilePath(artists: String, title: String): String =
        resolveOutputPath(artists, title).absolutePath

    /**
     * Reconciles where spotdl actually wrote the download against the expected
     * path. Returns the expected path when present; otherwise a single direct
     * child MP3 that looks like the expected name (including spotdl's ` (n)`
     * deduplication suffix). Falls back to the expected path so the caller
     * always has a stable answer.
     */
    fun findOutputFile(artists: String, title: String): File {
        val expected = resolveOutputPath(artists, title)
        if (fs.exists(expected)) return expected

        val stem = expected.name.removeSuffix(FileNames.MP3_EXTENSION)
        return fs.listFiles(rootDir)
            .asSequence()
            .filter { fs.isFile(it) && it.name.endsWith(FileNames.MP3_EXTENSION) }
            .filter { nameMatchesStem(it.name, stem) }
            .sortedBy { it.name }
            .firstOrNull() ?: expected
    }

    private fun nameMatchesStem(name: String, stem: String): Boolean {
        if (!name.startsWith(stem)) return false
        val tail = name.substring(stem.length, name.length - FileNames.MP3_EXTENSION.length)
        return tail.isEmpty() || tail.startsWith(" (")
    }

    // ---- Existence checks for re-sync (T-155) --------------------------------

    fun exists(file: File): Boolean = fs.exists(file)

    fun exists(filePath: String): Boolean = filePath.isNotBlank() && fs.exists(File(filePath))

    /** Re-sync guard: a DOWNLOADED song whose file vanished must be re-queued. */
    fun isDownloaded(filePath: String?): Boolean = filePath != null && exists(filePath)

    // ---- Deletion + sidecar cleanup (T-152) ---------------------------------

    data class SongDeletion(
        /** The MP3 existed on disk before the call. */
        val fileExisted: Boolean,
        /** The MP3 is gone after the call (deleted, or already absent). */
        val fileDeleted: Boolean,
        /** The `.spotdl` sidecar is gone after the call. */
        val sidecarDeleted: Boolean,
    ) {
        /** Disk is consistent with a removed song: neither MP3 nor sidecar remains. */
        val cleaned: Boolean get() = fileDeleted && sidecarDeleted
    }

    /** The spotdl sidecar path for a song file (`<path>.spotdl`). */
    fun sidecarOf(file: File): File = File(file.absolutePath + SIDECAR_SUFFIX)

    /**
     * Deletes a song's MP3 and its `.spotdl` sidecar. Defensive: deleting an
     * already-missing file is not an error — the row is removed regardless
     * (re-sync T-065). Never throws on missing/deleted files.
     */
    fun deleteSongFile(file: File): SongDeletion {
        val sidecar = sidecarOf(file)
        val fileExisted = fs.exists(file)
        val fileDeleted = !fileExisted || fs.delete(file)
        val sidecarExisted = fs.exists(sidecar)
        val sidecarDeleted = !sidecarExisted || fs.delete(sidecar)
        return SongDeletion(
            fileExisted = fileExisted,
            fileDeleted = fileDeleted,
            sidecarDeleted = sidecarDeleted,
        )
    }

    fun deleteSongFile(filePath: String): SongDeletion = deleteSongFile(File(filePath))

    // ---- Orphan detection & clear-cache (T-153) ------------------------------

    /**
     * Files on disk that are not owned by any song row: anything whose path
     * (or whose `.spotdl` sidecar path) is not in [dbFilePaths]. Sidecars of
     * known songs are deliberately kept — spotdl uses them to skip
     * already-downloaded tracks.
     */
    fun orphanFiles(dbFilePaths: Collection<String>): List<File> {
        val known = knownPaths(dbFilePaths)
        return fs.listFiles(rootDir)
            .filter { fs.isFile(it) }
            .filterNot { it.absolutePath in known }
            .sortedBy { it.name }
    }

    /**
     * Clear-cache: removes every orphan file (stray MP3s, abandoned `.spotdl`
     * sidecars, temp files) while leaving every file referenced by the DB
     * intact. Returns the files it attempted to remove.
     */
    fun clearOrphans(dbFilePaths: Collection<String>): List<File> {
        val orphans = orphanFiles(dbFilePaths)
        orphans.forEach { fs.delete(it) }
        return orphans
    }

    private fun knownPaths(dbFilePaths: Collection<String>): Set<String> = buildSet {
        for (raw in dbFilePaths) {
            if (raw.isBlank()) continue
            val path = File(raw).absolutePath
            add(path)
            add(path + SIDECAR_SUFFIX)
        }
    }

    // ---- Disk-space reporting (T-156) ---------------------------------------

    data class DiskSpaceReport(
        /** Sum of the store's own file sizes — the space clear-cache can free. */
        val usedBytes: Long,
        /** Free bytes on the underlying volume. */
        val freeBytes: Long,
        /** Total bytes on the underlying volume. */
        val totalBytes: Long,
    ) {
        /** Store usage as a fraction of the volume, 0.0..100.0. */
        val usedPercent: Double
            get() = if (totalBytes > 0L) (usedBytes * 100.0) / totalBytes else 0.0
    }

    /** Size of every regular file currently in the store. */
    fun storeUsedBytes(): Long =
        fs.listFiles(rootDir).filter { fs.isFile(it) }.sumOf { fs.length(it) }

    fun freeBytes(): Long = fs.freeSpace(rootDir)

    fun totalBytes(): Long = fs.totalSpace(rootDir)

    fun diskSpace(): DiskSpaceReport =
        DiskSpaceReport(usedBytes = storeUsedBytes(), freeBytes = freeBytes(), totalBytes = totalBytes())
}
