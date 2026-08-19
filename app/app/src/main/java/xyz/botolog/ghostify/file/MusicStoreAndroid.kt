package xyz.botolog.ghostify.file

import android.content.Context
import java.io.File

/**
 * Android wiring for [MusicStore]. Lives outside the JVM test module
 * (`android/` folder) because it imports `android.content.Context`.
 *
 * The store root is the app-scoped external directory, so:
 *  - no storage permission is needed (T-151), and
 *  - files are removed automatically if the app is uninstalled.
 *
 * `getExternalFilesDir(null)` already creates the directory; `filesDir` is a
 * safe fallback for exotic devices where it unexpectedly returns null.
 */
fun appStorageDir(context: Context): AppStorageDir =
    AppStorageDir { context.getExternalFilesDir(null) ?: context.filesDir }

/** Convenience for constructing a [MusicStore] bound to app-scoped storage. */
fun musicStore(context: Context, fs: FileSystem = JvmFileSystem): MusicStore {
    val dir = appStorageDir(context).dir()
    return MusicStore(root = dir, fs = fs).also { it.ensureRoot() }
}
