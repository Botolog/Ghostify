package com.ghostify.download

/**
 * Typed single-track download failure (never a raw crash). Mirrors the error
 * taxonomy PROJECT.md §10 expects so the UI can show a readable message.
 */
sealed interface DownloadError {
    val message: String

    data object Canceled : DownloadError {
        override val message: String = "Download canceled by user"
    }

    data class StorageFull(
        override val message: String = "Not enough storage space for this track",
    ) : DownloadError

    data class Network(
        val detail: String = "Network error while downloading",
    ) : DownloadError {
        override val message: String = detail
    }

    data class NotFound(
        val detail: String = "No audio source found for this track",
    ) : DownloadError {
        override val message: String = detail
    }

    data class Generic(
        override val message: String,
    ) : DownloadError
}

data class TrackDownloadResult(
    val songId: String,
    val filePath: String? = null,
    val error: DownloadError? = null,
) {
    val isSuccess: Boolean get() = error == null && filePath != null
}

/**
 * Single-track downloader — the wrapper around spotdl/Chaquopy (PROJECT.md §6.1,
 * §7). `download` must be cancellable so a cancelled run can interrupt an
 * in-flight network download (T-049). [onProgress] receives 0f..1f fractions for
 * the current track and is used to build the per-song progress UI.
 */
interface TrackDownloader {
    suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult

    /** Best-effort cancellation of an in-flight download. */
    suspend fun cancel(songId: String)
}
