package xyz.botolog.ghostify.ui.util

import java.io.File

/**
 * Resolves the model Coil should load a cover image from.
 *
 * Every cover in the app (playlist header, playlist rows, track rows) is stored the same
 * way: an optional locally extracted artwork file plus the remote URL it came from. The
 * local file is preferred because it is the only source that works offline, but it is not
 * guaranteed to still be there — it lives in the app's `filesDir` and is removed when the
 * song it was extracted from is deleted, so a stale path must never shadow a perfectly
 * good remote URL.
 *
 * @param localPath absolute path of the extracted artwork file, if any.
 * @param remoteUrl URL of the remote artwork, if any.
 * @return the local [File] when it exists, the remote URL when it is non-blank, or `null`
 *   when neither source is usable.
 */
fun coverImageModel(localPath: String?, remoteUrl: String?): Any? =
    resolveCoverImageModel(localPath, remoteUrl) { it.isFile }

/**
 * [coverImageModel] with the local-existence check injected, so the precedence rules stay
 * unit-testable on the JVM (where `File.isFile` is stubbed out).
 *
 * @param localPath absolute path of the extracted artwork file, if any.
 * @param remoteUrl URL of the remote artwork, if any.
 * @param isLocalAvailable tells whether a local artwork candidate can actually be read.
 * @return the local [File], the remote URL, or `null`.
 */
internal fun resolveCoverImageModel(
    localPath: String?,
    remoteUrl: String?,
    isLocalAvailable: (File) -> Boolean,
): Any? {
    val local = localPath?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
    if (local != null && isLocalAvailable(local)) {
        return local
    }
    return remoteUrl?.trim()?.takeIf { it.isNotEmpty() }
}
