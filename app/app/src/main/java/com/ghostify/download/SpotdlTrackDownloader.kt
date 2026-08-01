package com.ghostify.download

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * Default [TrackDownloader] used in production. It delegates the actual spotdl
 * work to a [SpotdlCall] that the Python bridge (Chaquopy, PROJECT.md §7)
 * implements — a `suspend` that runs on a dedicated dispatcher and is cancellable.
 *
 * This class only adds the defensive wiring (typed error mapping, cancellation
 * propagation) so the queue stays clean regardless of what spotdl throws.
 */
class SpotdlTrackDownloader(
    private val call: SpotdlCall = ChaquopySpotdlCall(),
) : TrackDownloader {

    override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
        currentCoroutineContext().ensureActive()
        return try {
            call.invoke(song, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            TrackDownloadResult(
                songId = song.id,
                error = DownloadError.Generic(e.message ?: "Download failed"),
            )
        }
    }

    override suspend fun cancel(songId: String) {
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
 * The Chaquopy module lives in the `python/` package; until it is wired, this
 * placeholder reports a typed failure instead of crashing the queue.
 */
class ChaquopySpotdlCall : SpotdlCall {
    override suspend fun invoke(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult =
        TrackDownloadResult(
            songId = song.id,
            error = DownloadError.Generic("Python bridge not yet wired (see PROJECT.md §7)"),
        )
}
