package com.ghostify.download

import com.ghostify.data.repo.SettingsRepository
import com.ghostify.file.MusicStore
import com.ghostify.python.FfmpegLocator
import com.ghostify.trackdownload.DownloadErrorKind
import com.ghostify.trackdownload.TrackDownloadBridge
import com.ghostify.trackdownload.TrackDownloadConfig
import com.ghostify.trackdownload.TrackProgressListener
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
                error = DownloadError.Generic(e.message ?: "Download failed"),
            )
        }
        Timber.i("SpotdlTrackDownloader.download: returning ${if (result.isSuccess) "SUCCESS" else "FAILED"}")
        return result
    }

    override suspend fun cancel(songId: String) {
        Timber.i("SpotdlTrackDownloader.cancel: START")
        // The Python bridge is told to abort via its own channel; not needed for
        // the queue's correctness since coroutine cancellation interrupts the
        // download (the wrapper's suspend call is cancellable).
    }
}

/** The bridge function implemented by the Chaquopy/Python layer (ghostify_dl.py). */
fun interface SpotdlCall {
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

    override suspend fun invoke(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
        Timber.i("ChaquopySpotdlCall.invoke: START for song=${song.id}")
        currentCoroutineContext().ensureActive()
        val bitrate = settings.getBitrate()
            .toIntOrNull()
            ?.takeIf { TrackDownloadConfig.isValidBitrate(it) }
            ?: TrackDownloadConfig.DEFAULT_BITRATE
        val config = TrackDownloadConfig(
            outputDir = musicStore.rootDir.absolutePath,
            bitrate = bitrate,
            outputTemplate = TrackDownloadConfig.DEFAULT_TEMPLATE,
            ffmpeg = FfmpegLocator.executablePath ?: "ffmpeg",
        )
        val url = if (song.ytId != null) {
            "https://www.youtube.com/watch?v=${song.ytId}"
        } else {
            "spotify:track:${song.spotifyId}"
        }
        val listener = object : TrackProgressListener {
            override fun onDownloadStart(track: com.ghostify.trackdownload.TrackInfo) {
                // Metadata resolved; conversion is about to begin.
            }

            override fun onProgress(percent: Int, message: String?) {
                onProgress((percent / 100f).coerceIn(0f, 1f))
            }

            override fun onDownloadComplete(result: com.ghostify.trackdownload.TrackDownloadResult) {
                // The download call itself returns the final result.
            }
        }
        val result = withContext(Dispatchers.IO) {
            when (val result = bridge.downloadBlocking(url, config, listener)) {
                is com.ghostify.trackdownload.TrackDownloadResult.Downloaded ->
                    TrackDownloadResult(songId = song.id, filePath = result.outputPath)

                is com.ghostify.trackdownload.TrackDownloadResult.Skipped ->
                    TrackDownloadResult(songId = song.id, filePath = result.outputPath)

                is com.ghostify.trackdownload.TrackDownloadResult.Failure ->
                    TrackDownloadResult(songId = song.id, error = mapError(result.error))
            }
        }
        Timber.i("ChaquopySpotdlCall.invoke: returning ${if (result.isSuccess) "SUCCESS" else "FAILED"}")
        return result
    }

    private fun mapError(
        error: com.ghostify.trackdownload.DownloadError,
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
        val m = message.lowercase()
        return "enospc" in m || "no space" in m || "not enough space" in m ||
            "disk full" in m || "out of space" in m
    }
}
