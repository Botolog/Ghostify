package xyz.botolog.ghostify.download

/**
 * Typed single-track download failure (never a raw crash). Mirrors the error
 * taxonomy PROJECT.md §10 expects so the UI can show a readable message.
 */
sealed interface DownloadError {
    val message: String

    /** Download was canceled by the user. */
    data object Canceled : DownloadError {
        override val message: String = "Download canceled by user"
    }

    /** Device storage is full or insufficient for this track. */
    data class StorageFull(
        override val message: String = "Not enough storage space for this track",
    ) : DownloadError

    /** Network error while downloading (timeouts, DNS, connection refused, etc.). */
    data class Network(
        val detail: String = "Network error while downloading",
    ) : DownloadError {
        override val message: String = detail
    }

    /** No audio source could be found for this track. */
    data class NotFound(
        val detail: String = "No audio source found for this track",
    ) : DownloadError {
        override val message: String = detail
    }

    /** Catch-all for unexpected errors. */
    data class Generic(
        override val message: String,
    ) : DownloadError
}

/**
 * Result of a single-track download attempt.
 *
 * @property songId the song that was downloaded.
 * @property filePath the local file path if successful, `null` otherwise.
 * @property error the error if the download failed, `null` on success.
 */
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
    /**
     * Downloads a single track.
     *
     * @param song the song to download.
     * @param onProgress callback receiving 0f..1f download progress.
     * @return the result of the download attempt.
     */
    suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult

    /** Best-effort cancellation of an in-flight download. */
    suspend fun cancel(songId: String)
}
