package com.ghostify.trackdownload

/**
 * Progress callback supplied to [TrackDownloadBridge.downloadBlocking].
 *
 * The listener is handed to Python as a Java object; spotdl's hooks resolve the
 * methods by name (see `ghostify_dl._as_callable`), so the method names below
 * must stay in sync with the Python `_START_NAMES` / `_PROGRESS_NAMES` /
 * `_COMPLETE_NAMES` tuples:
 *
 *  * [onDownloadStart]    → `_START_NAMES`  (`on_download_start` / `onDownloadStart`)
 *  * [onProgress]         → `_PROGRESS_NAMES` (`on_progress` / `onProgress`)
 *  * [onDownloadComplete] → `_COMPLETE_NAMES` (`on_download_complete` / `onDownloadComplete`)
 *
 * Each method has a default no-op body so callers implement only what they need.
 * On Android these fire on the bridge's background worker thread — do not touch
 * the UI from them without dispatching.
 */
interface TrackProgressListener {
    /** Fired before the track is fetched/converted; [track] is the resolved metadata. */
    fun onDownloadStart(track: TrackInfo)

    /** Fired repeatedly while downloading; [percent] is 0..100. */
    fun onProgress(percent: Int, message: String?)

    /** Fired once when the track is finished — [result] is [TrackDownloadResult.Downloaded] or [Skipped]. */
    fun onDownloadComplete(result: TrackDownloadResult)
}
