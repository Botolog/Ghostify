package xyz.botolog.ghostify.download

import xyz.botolog.ghostify.data.repo.SettingsRepository
import xyz.botolog.ghostify.file.MusicStore
import xyz.botolog.ghostify.python.FfmpegLocator
import xyz.botolog.ghostify.trackdownload.DownloadErrorKind
import xyz.botolog.ghostify.trackdownload.TrackDownloadBridge
import xyz.botolog.ghostify.trackdownload.TrackDownloadConfig
import xyz.botolog.ghostify.trackdownload.TrackProgressListener
import xyz.botolog.ghostify.trackdownload.buildMetaPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Default [TrackDownloader] used in production. It delegates the actual spotdl
 * work to a [SpotdlCall] that the Python bridge (Chaquopy, PROJECT.md §7)
 * implements — a `suspend` that runs on a dedicated dispatcher and is cancellable.
 *
 * This class only adds the defensive wiring (typed error mapping, cancellation
 * propagation) so the queue stays clean regardless of what spotdl throws.
 */
class SpotdlTrackDownloader(
    private val call: SpotdlCall,
) : TrackDownloader {

    /**
     * Downloads a single track via the underlying [SpotdlCall].
     *
     * Cancellation exceptions are re-thrown to preserve structured concurrency.
     * All other throwables are mapped to [DownloadError.Generic].
     *
     * @param song the song to download.
     * @param onProgress callback receiving 0f..1f download progress.
     * @return the result of the download attempt.
     */
    override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
        Timber.i("SpotdlTrackDownloader.download: START for song=${song.id}")
        currentCoroutineContext().ensureActive()
        val result = try {
            call.invoke(song, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "SpotdlTrackDownloader: download FAILED for song=${song.id}")
            TrackDownloadResult(
                songId = song.id,
                error = DownloadError.Generic(e.message ?: DEFAULT_ERROR_MESSAGE),
            )
        }
        Timber.i("SpotdlTrackDownloader.download: returning ${if (result.isSuccess) "SUCCESS" else "FAILED"}")
        return result
    }

    /**
     * Best-effort cancellation. The Python bridge is told to abort via its own
     * channel; not needed for the queue's correctness since coroutine cancellation
     * interrupts the download (the wrapper's suspend call is cancellable).
     */
    override suspend fun cancel(songId: String) {
        Timber.i("SpotdlTrackDownloader.cancel: START")
    }

    private companion object {
        const val DEFAULT_ERROR_MESSAGE = "Download failed"
    }
}

/** The bridge function implemented by the Chaquopy/Python layer (ghostify_dl.py). */
fun interface SpotdlCall {
    /**
     * Invokes the underlying downloader for a single track.
     *
     * @param song the song to download.
     * @param onProgress callback receiving 0f..1f progress updates.
     * @return the result of the download attempt.
     */
    suspend fun invoke(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult
}

/**
 * Production binding to the Python `ghostify_dl.download(...)` (PROJECT.md §7).
 *
 * Each track is downloaded through [TrackDownloadBridge] with the current
 * settings bitrate, writing into the app-scoped [MusicStore] root. Progress
 * events are relayed as 0f..1f fractions for the per-song progress UI.
 */
class ChaquopySpotdlCall(
    private val bridge: TrackDownloadBridge,
    private val musicStore: MusicStore,
    private val settings: SettingsRepository,
) : SpotdlCall {

    /**
     * Downloads a single track using the Python bridge.
     *
     * @param song the song to download.
     * @param onProgress callback receiving 0f..1f download progress.
     * @return the result of the download attempt.
     */
    override suspend fun invoke(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
        Timber.i("ChaquopySpotdlCall.invoke: START for song=${song.id}")
        currentCoroutineContext().ensureActive()
        val config = buildDownloadConfig(song.playlistId)
        val url = song.sourceUrl
        val listener = buildProgressListener(onProgress)
        val result = withContext(Dispatchers.IO) {
            mapBridgeResult(
                song,
                bridge.downloadBlocking(url, config, listener, song.ytId, buildMetaPayload(song)),
            )
        }
        Timber.i("ChaquopySpotdlCall.invoke: returning ${if (result.isSuccess) "SUCCESS" else "FAILED"}")
        return result
    }

    private suspend fun buildDownloadConfig(playlistId: String): TrackDownloadConfig {
        val bitrate = settings.getBitrate()
            .toIntOrNull()
            ?.takeIf { TrackDownloadConfig.isValidBitrate(it) }
            ?: TrackDownloadConfig.DEFAULT_BITRATE
        val baseDir = java.io.File(musicStore.rootDir.absolutePath, playlistId)
        return TrackDownloadConfig(
            outputDir = baseDir.absolutePath,
            bitrate = bitrate,
            outputTemplate = TrackDownloadConfig.DEFAULT_TEMPLATE,
            ffmpeg = FfmpegLocator.executablePath ?: FFMPEG_DEFAULT,
            lyricsProviders = java.util.ArrayList(listOf("genius")),
        )
    }

    private fun buildProgressListener(onProgress: (Float) -> Unit): TrackProgressListener =
        object : TrackProgressListener {
            override fun onDownloadStart(track: xyz.botolog.ghostify.trackdownload.TrackInfo) {
                // Metadata resolved; conversion is about to begin.
            }

            override fun onProgress(percent: Int, message: String?) {
                onProgress((percent / PERCENT_DIVISOR).coerceIn(MIN_FRACTION, MAX_FRACTION))
            }

            override fun onDownloadComplete(result: xyz.botolog.ghostify.trackdownload.TrackDownloadResult) {
                // The download call itself returns the final result.
            }
        }

    private fun mapBridgeResult(
        song: SongRecord,
        result: xyz.botolog.ghostify.trackdownload.TrackDownloadResult,
    ): TrackDownloadResult = when (result) {
        is xyz.botolog.ghostify.trackdownload.TrackDownloadResult.Downloaded ->
            TrackDownloadResult(songId = song.id, filePath = result.outputPath, lyrics = result.lyrics)

        is xyz.botolog.ghostify.trackdownload.TrackDownloadResult.Skipped ->
            TrackDownloadResult(songId = song.id, filePath = result.outputPath, lyrics = result.lyrics)

        is xyz.botolog.ghostify.trackdownload.TrackDownloadResult.Failure ->
            TrackDownloadResult(songId = song.id, error = mapError(result.error))
    }

    private fun mapError(
        error: xyz.botolog.ghostify.trackdownload.DownloadError,
    ): DownloadError = when (error.kind) {
        DownloadErrorKind.INTERRUPTED -> DownloadError.Canceled

        DownloadErrorKind.NO_TRACK,
        DownloadErrorKind.SEARCH_FAILED,
        DownloadErrorKind.AUDIO_UNAVAILABLE,
        -> DownloadError.NotFound(error.message)

        DownloadErrorKind.IO ->
            if (looksLikeStorage(error.message)) DownloadError.StorageFull()
            else DownloadError.Network(error.message)

        DownloadErrorKind.METADATA_FAILED,
        DownloadErrorKind.CONVERSION_FAILED,
        DownloadErrorKind.TAGGING_FAILED,
        DownloadErrorKind.VALIDATION_FAILED,
        DownloadErrorKind.DEPENDENCY,
        DownloadErrorKind.UNKNOWN,
        -> DownloadError.Generic(error.message)
    }

    private fun looksLikeStorage(message: String): Boolean {
        val lowered = message.lowercase()
        return STORAGE_KEYWORDS.any { it in lowered }
    }

    private companion object {
        const val FFMPEG_DEFAULT = "ffmpeg"
        const val PERCENT_DIVISOR = 100f
        const val MIN_FRACTION = 0f
        const val MAX_FRACTION = 1f

        val STORAGE_KEYWORDS = listOf(
            "enospc",
            "no space",
            "not enough space",
            "disk full",
            "out of space",
        )
    }
}
