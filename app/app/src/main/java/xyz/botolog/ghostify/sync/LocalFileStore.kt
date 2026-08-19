package xyz.botolog.ghostify.sync

/**
 * The minimal file-manager surface the sync diff needs (PROJECT.md §6.2).
 *
 * Implemented in the app by an adapter over the `MusicStore` from the
 * `file-management` component (`MusicStore.exists` / `MusicStore.deleteSongFile`).
 * All operations are defensive: they never throw for a missing/blank path.
 */
interface LocalFileStore {

    /**
     * True when [filePath] names an existing regular file on disk.
     * Null and blank paths are "not present" (they never cause a crash).
     */
    fun exists(filePath: String?): Boolean

    /**
     * Deletes the file at [filePath]. Deleting an already-missing file is a
     * no-op success (T-065). Implementations must not throw for blank paths.
     */
    fun delete(filePath: String)
}
