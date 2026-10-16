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

/**
 * Resolves a playlist cover with an explicit last-resort track fallback.
 *
 * Precedence (first usable wins):
 *  1. Playlist local file (offline own art).
 *  2. Playlist remote URL (online own art).
 *  3. First-track local file (offline fallback, only when the playlist has no
 *     own artwork at all).
 *  4. First-track remote URL (online fallback).
 *  5. `null` (placeholder).
 *
 * The fallback never shadows the playlist's own art: it is only consulted when
 * both playlist sources are missing/unusable, so a missing save stays visible
 * as a placeholder instead of being masked by a track's cover.
 *
 * @param playlistLocal playlist's persisted local cover path.
 * @param playlistUrl playlist's own remote cover URL.
 * @param fallbackLocal first track's local cover path, or `null` when none.
 * @param fallbackUrl first track's remote cover URL, or `null` when none.
 * @param isLocalAvailable tells whether a local artwork candidate can be read.
 */
internal fun resolvePlaylistCoverModel(
    playlistLocal: String?,
    playlistUrl: String?,
    fallbackLocal: String?,
    fallbackUrl: String?,
    isLocalAvailable: (File) -> Boolean,
): Any? {
    resolveCoverImageModel(playlistLocal, playlistUrl, isLocalAvailable)?.let { return it }
    // Only reached when the playlist has no usable own artwork.
    return resolveCoverImageModel(fallbackLocal, fallbackUrl, isLocalAvailable)
}

/**
 * [resolvePlaylistCoverModel] with the real filesystem check, for Compose callers.
 */
fun playlistCoverModel(
    playlistLocal: String?,
    playlistUrl: String?,
    fallbackLocal: String?,
    fallbackUrl: String?,
): Any? = resolvePlaylistCoverModel(
    playlistLocal,
    playlistUrl,
    fallbackLocal,
    fallbackUrl,
) { it.isFile }
