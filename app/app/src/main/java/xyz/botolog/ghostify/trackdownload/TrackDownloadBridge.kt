package xyz.botolog.ghostify.trackdownload

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import xyz.botolog.ghostify.perf.DownloadLeakTracker
import xyz.botolog.ghostify.python.PyConverters
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin bridge to the `ghostify_dl` Python module (Chaquopy) for single-track
 * downloads (TESTS.md T-030..T-041; PROJECT.md §6.1/§7).
 *
 * Wraps spotdl's programmatic API through `ghostify_dl.{make_downloader,
 * download, expected_output_path, cleanup_temp}`. Each call creates its own
 * Python `TrackDownloader` so parallel downloads stay independent; the handle is
 * owned by [withDownloader] and released exactly once in a `finally` (see
 * [PyDownloaderLease]) so no native reference is leaked.
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
 * @param leakTracker optional lifecycle tracker used to assert the
 *   "released exactly once per use" contract (T-166); production passes `null`.
 */
class TrackDownloadBridge(
    private val moduleName: String = DEFAULT_MODULE_NAME,
    private val leakTracker: DownloadLeakTracker? = null,
) {

    /**
     * Every downloader currently handed out by [withDownloader]. Used by
     * [cancel] so an explicit cancellation can also release the native handle
     * (idempotently — the owning `finally` remains the primary release path).
     *
     * Keyed by lease identity, which is why the set is built from a
     * [ConcurrentHashMap] (leases use identity equality).
     */
    private val inFlightLeases: MutableSet<PyDownloaderLease<PyObject>> =
        Collections.newSetFromMap(ConcurrentHashMap<PyDownloaderLease<PyObject>, Boolean>())

    /**
     * Creates a fresh Python `TrackDownloader` for each call. This avoids
     * serializing on a shared lock — each parallel download gets its own
     * independent downloader instance so multiple tracks can be fetched
     * concurrently.
     *
     * The returned handle is owned by [withDownloader], which releases it
     * exactly once in a `finally`; callers must never close it themselves.
     */
    private fun requireDownloader(config: TrackDownloadConfig): PyObject {
        val module = Python.getInstance().getModule(moduleName)
        return module.callAttr(
            "make_downloader",
            config.outputDir, config.bitrate, config.outputTemplate, config.ffmpeg,
            null,  // audio_providers
            null,  // lyrics_providers — handled on Python side
        )
    }

    /**
     * Runs [block] with a fresh downloader and releases the handle exactly once
     * afterwards, on success, failure or cancellation. The lease is registered in
     * [inFlightLeases] for the duration so [cancel] can reach it.
     */
    private fun <T> withDownloader(config: TrackDownloadConfig, block: (PyObject) -> T): T =
        withPyDownloaderLease(
            tracker = leakTracker,
            ownerTag = OWNER_TAG,
            onAcquired = { inFlightLeases.add(it) },
            onReleased = { inFlightLeases.remove(it) },
            factory = { requireDownloader(config) },
            block = block,
        )

    /**
     * Best-effort cancellation of every in-flight download.
     *
     * Each lease is released only if its handle has not been taken into use yet;
     * a lease whose Python call is already running defers to its owning `finally`
     * (so a running call can never have its handle closed underneath it). Release
     * is idempotent, so the owning `finally` never double-closes.
     *
     * @return how many handles were released immediately by this call.
     */
    fun cancel(): Int {
        val victims = inFlightLeases.toList()
        var released = 0
        for (lease in victims) {
            inFlightLeases.remove(lease)
            if (!lease.isClosed) {
                lease.cancel()
                if (lease.isClosed) released++
            }
        }
        if (released > 0) {
            Timber.i("TrackDownloadBridge.cancel: released $released in-flight downloader(s)")
        }
        return released
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
     * @param config downloader configuration for the fresh downloader created
     *   for this call.
     * @param listener optional progress callback; may be null to ignore progress.
     * @param ytId resolved YouTube video ID for YouTube-origin tracks.
     * @param meta optional flat metadata map (see [buildMetaPayload]) letting the
     *   Python side skip its per-track Spotify re-fetch; `null` keeps the old
     *   fetch-from-Scraper behavior.
     */
    fun downloadBlocking(
        url: String,
        config: TrackDownloadConfig = TrackDownloadConfig.DEFAULT,
        listener: TrackProgressListener? = null,
        ytId: String? = null,
        meta: Map<String, Any?>? = null,
    ): TrackDownloadResult {
        Timber.i("TrackDownloadBridge.downloadBlocking: START")
        return try {
            val pyResult = invokeDownload(url, config, listener, ytId, meta)
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
     *
     * The top-level [meta] map of primitives (String / Double / List / null)
     * converts natively through Chaquopy, like the scalar arguments already
     * passed here; `null` arrives in Python as `None`.
     *
     * The downloader handle is released by [withDownloader] once the call returns,
     * whether it succeeded or threw.
     */
    private fun invokeDownload(
        url: String,
        config: TrackDownloadConfig,
        listener: TrackProgressListener?,
        ytId: String?,
        meta: Map<String, Any?>?,
    ): PyObject? =
        withDownloader(config) { downloader ->
            val hook = listener?.let { HookAdapter(it) }
            val module = Python.getInstance().getModule(moduleName)
            module.callAttr("download", downloader, url, hook, hook, hook, ytId, meta)
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
            val result = withDownloader(config) { downloader ->
                val module = Python.getInstance().getModule(moduleName)
                PyConverters.string(module.callAttr("expected_output_path", downloader, url))
            }
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
            val result = withDownloader(config) { downloader ->
                val module = Python.getInstance().getModule(moduleName)
                val py = if (ageSeconds == null) {
                    module.callAttr("cleanup_temp", downloader)
                } else {
                    module.callAttr("cleanup_temp", downloader, ageSeconds)
                }
                PyConverters.int(py) ?: 0
            }
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

    /**
     * Fetches synced lyrics for a track without downloading it.
     *
     * @param name track title.
     * @param artists comma-separated artist names.
     * @return the LRC lyrics text, or `null` if no lyrics were found.
     */
    fun fetchLyricsBlocking(name: String, artists: String, provider: String = "synced"): String? {
        Timber.i("TrackDownloadBridge.fetchLyricsBlocking: START name=$name, artists=$artists, provider=$provider")
        return try {
            val module = Python.getInstance().getModule(moduleName)
            val py = module.callAttr("fetch_lyrics", name, artists, provider)
            val result = PyConverters.string(py)
            Timber.i("TrackDownloadBridge.fetchLyricsBlocking: returning ${result?.length ?: 0} chars")
            result
        } catch (e: PyException) {
            Timber.e(e, "TrackDownloadBridge: fetchLyricsBlocking FAILED")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "TrackDownloadBridge: fetchLyricsBlocking FAILED")
            null
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

        /** Leak-tracker owner tag for downloader handles created by this bridge. */
        const val OWNER_TAG = "track-download"
    }
}
