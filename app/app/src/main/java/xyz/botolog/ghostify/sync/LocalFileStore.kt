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
     * Checks whether a file exists at the given path.
     *
     * @param filePath Absolute path to the file, or `null`/blank for "not present".
     * @return `true` if a regular file exists at [filePath], `false` otherwise.
     */
    fun exists(filePath: String?): Boolean

    /**
     * Deletes the file at [filePath].
     *
     * Deleting an already-missing file is a no-op success (T-065).
     * Implementations must not throw for blank paths.
     *
     * @param filePath Absolute path to the file to delete.
     */
    fun delete(filePath: String)
}
