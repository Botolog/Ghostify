package xyz.botolog.ghostify.file

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import timber.log.Timber
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

/** Constructs a [MusicStore] using a named subfolder under app-scoped storage. */
fun musicStore(context: Context, subfolder: String, fs: FileSystem = JvmFileSystem): MusicStore {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val dir = File(base, subfolder)
    return MusicStore(root = dir, fs = fs).also { it.ensureRoot() }
}

/** Resolves the filesystem path for a named subfolder under app-scoped storage. */
fun resolveStorageDir(context: Context, subfolder: String): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, subfolder)
}

/**
 * Resolves a SAF tree URI to a real filesystem path.
 *
 * Handles primary storage (`/storage/emulated/0/...`) and removable SD cards
 * (`/storage/XXXX-XXXX/...`) by parsing the tree document ID and looking up
 * the volume mount point.
 *
 * @param context Android context.
 * @param treeUri The tree URI returned by the SAF picker.
 * @return The resolved filesystem path, or `null` if resolution fails.
 */
fun resolveTreeUriToPath(context: Context, treeUri: Uri): String? {
    try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)

        // Primary storage (emulated) — docId is "primary" or "primary:path/within"
        if (docId.startsWith("primary:", ignoreCase = true) || docId.equals("primary", ignoreCase = true)) {
            val subPath = if (docId.contains(":")) docId.substringAfter(":") else ""
            val basePath = Environment.getExternalStorageDirectory().absolutePath
            val path = if (subPath.isNotBlank()) "$basePath/$subPath/" else "$basePath/"
            Timber.i("resolveTreeUriToPath: primary -> $path")
            return path
        }

        // Removable storage (SD card) - document ID is "XXXX-XXXX:/path"
        if (docId.contains(":")) {
            val volumeId = docId.substringBefore(":")
            val subPath = docId.substringAfter(":")

            val mountPoint = findVolumeMountPoint(context, volumeId)
            if (mountPoint != null) {
                val path = "$mountPoint$subPath/"
                Timber.i("resolveTreeUriToPath: volume $volumeId -> $path")
                return path
            }
        }

        // Fallback: try to extract path from the URI itself
        val path = treeUri.path
        if (path != null) {
            Timber.i("resolveTreeUriToPath: fallback path -> $path")
            return path
        }

        Timber.w("resolveTreeUriToPath: could not resolve URI: $treeUri")
        return null
    } catch (e: Exception) {
        Timber.e(e, "resolveTreeUriToPath: failed for URI: $treeUri")
        return null
    }
}

/**
 * Finds the mount point for a storage volume by its ID.
 *
 * Scans `/storage/` for directories matching the volume ID pattern.
 */
private fun findVolumeMountPoint(context: Context, volumeId: String): String? {
    // Check well-known paths first
    val storageRoot = File("/storage")
    if (storageRoot.exists()) {
        for (dir in storageRoot.listFiles() ?: emptyArray()) {
            if (dir.isDirectory && dir.name.equals(volumeId, ignoreCase = true)) {
                return dir.absolutePath
            }
        }
    }

    // Also check /mnt/ for older devices
    val mntRoot = File("/mnt")
    if (mntRoot.exists()) {
        for (dir in mntRoot.listFiles() ?: emptyArray()) {
            if (dir.isDirectory && dir.name.equals(volumeId, ignoreCase = true)) {
                return dir.absolutePath
            }
        }
    }

    return null
}
