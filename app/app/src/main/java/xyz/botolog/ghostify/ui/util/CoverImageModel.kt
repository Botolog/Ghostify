package xyz.botolog.ghostify.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil.request.ImageRequest
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

/**
 * A Coil [ImageRequest] for a track/playlist cover, decoded at exactly [size] instead of the
 * source resolution.
 *
 * The local-vs-remote precedence is resolved inside [remember], and the filesystem existence
 * check it performs (`File.isFile`) therefore only runs when one of the sources actually changes
 * — never on every recomposition. A cover that asks for a 40-56 dp thumbnail consequently
 * decodes a ~40-56 dp bitmap rather than the full embedded JPEG (up to 1280 px), which is what
 * keeps large allocations and GC pauses off the main thread.
 *
 * @param localPath absolute path of the extracted local artwork, if any.
 * @param remoteUrl URL of the remote artwork, if any.
 * @param size the square edge length the cover is displayed at.
 * @return a sized [ImageRequest] for the best available source, or `null` when neither exists.
 */
@Composable
fun rememberCoverImageRequest(
    localPath: String?,
    remoteUrl: String?,
    size: Dp,
): ImageRequest? {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    return remember(localPath, remoteUrl, sizePx, context) {
        coverImageModel(localPath, remoteUrl)?.let { model ->
            ImageRequest.Builder(context).data(model).size(sizePx).build()
        }
    }
}

/**
 * [rememberCoverImageRequest] for a playlist cover with the first-track fallback of
 * [playlistCoverModel], decoded at exactly [size].
 *
 * @param playlistLocal playlist's persisted local cover path.
 * @param playlistUrl playlist's own remote cover URL.
 * @param fallbackLocal first track's local cover path, or `null` when none.
 * @param fallbackUrl first track's remote cover URL, or `null` when none.
 * @param size the square edge length the cover is displayed at.
 */
@Composable
fun rememberPlaylistCoverImageRequest(
    playlistLocal: String?,
    playlistUrl: String?,
    fallbackLocal: String?,
    fallbackUrl: String?,
    size: Dp,
): ImageRequest? {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    return remember(playlistLocal, playlistUrl, fallbackLocal, fallbackUrl, sizePx, context) {
        playlistCoverModel(playlistLocal, playlistUrl, fallbackLocal, fallbackUrl)?.let { model ->
            ImageRequest.Builder(context).data(model).size(sizePx).build()
        }
    }
}
