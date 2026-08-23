package xyz.botolog.ghostify.trackdownload

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import xyz.botolog.ghostify.python.PyConverters
import timber.log.Timber

/**
 * Kotlin bridge to the `ghostify_dl` Python module (Chaquopy) for single-track
 * downloads (TESTS.md T-030..T-041; PROJECT.md §6.1/§7).
 *
 * Wraps spotdl's programmatic API through `ghostify_dl.{make_downloader,
 * download, expected_output_path, cleanup_temp}`. A single instance holds one
 * Python `TrackDownloader` and serializes every call on [lock] so the Chaquopy
 * interpreter is never touched concurrently — matching the app's contract that
 * one downloader serves one worker (see PROJECT.md §7. "single-threaded
 * dispatcher").
 *
 * Threading: every method blocks on Python + network and **must** be called from
 * a background dispatcher (the WorkManager worker drives it off `Dispatchers.IO`).
 * No main-thread guard is enforced here so the bridge stays JVM-testable;
 * [downloadBlocking] additionally never throws for expected track failures —
 * those surface as [TrackDownloadResult.Failure]. Only a raw infrastructure
 * breakdown that escapes even the PyException catch (extremely unlikely) would
 * propagate, because the bridge classifies Chaquopy failures into [UNKNOWN].
 *
 * Progress hooks: [downloadBlocking] accepts a [TrackProgressListener]; the
 * bridge wraps it in an internal [HookAdapter] and passes it to Python as a
 * single Java object. spotdl's hooks resolve `onDownloadStart` / `onProgress` /
 * `onDownloadComplete` by name (see `ghostify_dl._as_callable`), so on device
 * Chaquopy hands the Python dict/int/str to the adapter as `PyObject` handles,
 * which the adapter reads explicitly via [xyz.botolog.ghostify.python.PyConverters].
 * Hook exceptions on the Python side are swallowed by the bridge (see
 * `ghostify_dl._fire`), so a misbehaving listener can never fail a download.
 *
 * @param moduleName the Python module to invoke (default "ghostify_dl").
 */
class TrackDownloadBridge(
    private val moduleName: String = DEFAULT_MODULE_NAME
) {

    /**
     * Creates a fresh Python `TrackDownloader` for each call. This avoids
     * serializing on a shared lock — each parallel download gets its own
     * independent downloader instance so multiple tracks can be fetched
     * concurrently.
     */
    private fun requireDownloader(config: TrackDownloadConfig): PyObject {
        val module = Python.getInstance().getModule(moduleName)
        return module.callAttr(
            "make_downloader",
            config.outputDir, config.bitrate, config.outputTemplate, config.ffmpeg
        )
    }

    /**
     * Downloads one track, returning a typed result.
     *
     * Never throws for expected failures (T-036/T-039: unavailable, corrupt,
     * interrupted, bad ffmpeg): these surface as [TrackDownloadResult.Failure]
     * with a stable [DownloadError.kind]. The on-device instrumentation tests
     * (T-030/T-031/.../T-041) exercise this against a real Chaquopy interpreter.
     *
     * @param url a Spotify track URL/URI or a bare search query accepted by
     *   spotdl. Playlist URLs are rejected with [DownloadErrorKind.NO_TRACK].
     * @param config downloader configuration; a new downloader is made if the
     *   cached one does not match.
     * @param listener optional progress callback; may be null to ignore progress.
     */
    fun downloadBlocking(
        url: String,
        config: TrackDownloadConfig = TrackDownloadConfig.DEFAULT,
        listener: TrackProgressListener? = null,
        ytId: String? = null,
    ): TrackDownloadResult {
        Timber.i("TrackDownloadBridge.downloadBlocking: START")
        return try {
            val pyResult = invokeDownload(url, config, listener, ytId)
            val result = parseResult(pyResult, url)
            Timber.i("TrackDownloadBridge.downloadBlocking: returning $result")
            result
        } catch (e: PyException) {
            Timber.e(e, "TrackDownloadBridge: download operation FAILED")
            failureFromPy(e, url)
        } catch (e: RuntimeException) {
            Timber.e(e, "TrackDownloadBridge: download operation FAILED")
            TrackDownloadResult.Failure(url, DownloadError.unknown(e.message))
        }
    }

    /**
     * Invokes the Python `download` function with the given parameters.
     * Returns the raw [PyObject] result dict.
     */
    private fun invokeDownload(
        url: String,
        config: TrackDownloadConfig,
        listener: TrackProgressListener?,
        ytId: String?,
    ): PyObject? {
        val downloader = requireDownloader(config)
        val hook = listener?.let { HookAdapter(it) }
        val module = Python.getInstance().getModule(moduleName)
        return module.callAttr("download", downloader, url, hook, hook, hook, ytId)
    }

    /**
     * Path [url] would be written to, resolved without downloading — used for
     * idempotent status checks (T-035 sidecar fast path lives in Python).
     * Returns null when the track can't be resolved locally/offline.
     */
    fun expectedOutputPath(
        url: String,
        config: TrackDownloadConfig = TrackDownloadConfig.DEFAULT,
    ): String? {
        Timber.i("TrackDownloadBridge.expectedOutputPath: START")
        return try {
            val downloader = requireDownloader(config)
            val module = Python.getInstance().getModule(moduleName)
            val py = module.callAttr("expected_output_path", downloader, url)
            val result = PyConverters.string(py)
            Timber.i("TrackDownloadBridge.expectedOutputPath: returning $result")
            result
        } catch (e: PyException) {
            Timber.e(e, "TrackDownloadBridge: expectedOutputPath operation FAILED")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "TrackDownloadBridge: expectedOutputPath operation FAILED")
            null
        }
    }

    /**
     * Deletes orphaned temp files left by interrupted downloads (T-037), using
     * the downloader's configured temp sweep age. Returns the count removed.
     * Failures (e.g. interpreter down) are swallowed → 0.
     */
    fun cleanupTemp(
        config: TrackDownloadConfig = TrackDownloadConfig.DEFAULT,
        ageSeconds: Double? = null,
    ): Int {
        Timber.i("TrackDownloadBridge.cleanupTemp: START")
        return try {
            val downloader = requireDownloader(config)
            val module = Python.getInstance().getModule(moduleName)
            val py = if (ageSeconds == null) {
                module.callAttr("cleanup_temp", downloader)
            } else {
                module.callAttr("cleanup_temp", downloader, ageSeconds)
            }
            val result = PyConverters.int(py) ?: 0
            Timber.i("TrackDownloadBridge.cleanupTemp: returning $result")
            result
        } catch (e: PyException) {
            Timber.e(e, "TrackDownloadBridge: cleanupTemp operation FAILED")
            0
        } catch (e: RuntimeException) {
            Timber.e(e, "TrackDownloadBridge: cleanupTemp operation FAILED")
            0
        }
    }

    // ------------------------------------------------------------------
    // Internal parsing / mapping.
    // ------------------------------------------------------------------

    /**
     * Converts the Python result dict (the return of `ghostify_dl.download`) into
     * a typed [TrackDownloadResult]. Pure Kotlin — no Python, no I/O.
     *
     * Reads the dict explicitly via [PyConverters] because Chaquopy's automatic
     * `toJava(Map)` conversion cannot deep-convert dicts.
     */
    internal fun parseResult(pyResult: PyObject?, url: String): TrackDownloadResult {
        if (!PyConverters.isDict(pyResult)) {
            return TrackDownloadResult.Failure(
                url = url,
                error = DownloadError.unknown("Download returned an unexpected result.")
            )
        }
        return TrackDownloadResult.fromMap(PyConverters.stringMap(pyResult))
    }

    private fun failureFromPy(e: PyException, url: String): TrackDownloadResult.Failure {
        val message = e.message ?: ""
        val kind = DownloadErrorKind.fromMessage(message)
        val wire = if (kind != DownloadErrorKind.UNKNOWN) kind.name else null
        return TrackDownloadResult.Failure(
            url = url,
            error = DownloadError(
                kind = kind,
                message = DownloadErrorKind.humanMessage(message),
                wireType = wire
            )
        )
    }

    // ------------------------------------------------------------------
    // Hook adapter: translates Python's dict/int/str callbacks into the typed
    // TrackProgressListener. Passed to Python as a single Java object whose
    // methods resolve by name in `ghostify_dl._as_callable`.
    // ------------------------------------------------------------------

    internal class HookAdapter(private val listener: TrackProgressListener) {
        /**
         * Mirrors `on_download_start` (called with the `_track_dict` map).
         * Args arrive as `PyObject` handles; they are read explicitly with
         * [PyConverters] because Chaquopy cannot auto-convert a Python dict
         * into a Kotlin `Map`-typed parameter (cf. PlaylistMetadataBridge).
         */
        fun onDownloadStart(track: PyObject?) {
            listener.onDownloadStart(TrackInfo.fromMap(PyConverters.stringMap(track)))
        }

        /** Mirrors `on_progress` (called with percent: int, message: str). */
        fun onProgress(percent: PyObject?, message: PyObject?) {
            listener.onProgress(
                PyConverters.int(percent) ?: 0,
                PyConverters.string(message)
            )
        }

        /**
         * Mirrors `on_download_complete` (called with the result payload map).
         * Same PyObject conversion as `onDownloadStart`.
         */
        fun onDownloadComplete(result: PyObject?) {
            listener.onDownloadComplete(
                TrackDownloadResult.fromMap(PyConverters.stringMap(result))
            )
        }
    }

    companion object {
        const val DEFAULT_MODULE_NAME = "ghostify_dl"
    }
}
