package com.ghostify.trackdownload

import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python

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
 * Chaquopy marshals the Python dict/int/str straight into the adapter's typed
 * methods. Hook exceptions on the Python side are swallowed by the bridge (see
 * `ghostify_dl._fire`), so a misbehaving listener can never fail a download.
 */
class TrackDownloadBridge(
    private val moduleName: String = DEFAULT_MODULE_NAME
) {

    private val lock = Any()

    /** The cached Python `TrackDownloader` (created lazily / per config). */
    @Volatile private var downloader: PyObject? = null

    /** Config the cached downloader was built with (for cheap reuse checks). */
    @Volatile private var config: TrackDownloadConfig? = null

    /**
     * Builds (or rebuilds) the Python downloader for [config]. Reuses the cached
     * downloader when [config] is unchanged. Caller must hold [lock].
     */
    private fun requireDownloader(config: TrackDownloadConfig): PyObject {
        val current = downloader
        val cached = this.config
        if (current != null && cached == config) return current
        val module = Python.getInstance().getModule(moduleName)
        // Positional args: output_dir, bitrate, output_template, ffmpeg — these
        // map 1:1 to `ghostify_dl.make_downloader`'s explicit parameters so no
        // Chaquopy keyword-argument marshalling is needed (the most robust
        // cross-version contract).
        val py = module.callAttr(
            "make_downloader",
            config.outputDir, config.bitrate, config.outputTemplate, config.ffmpeg
        )
        downloader = py
        this.config = config
        return py
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
        listener: TrackProgressListener? = null
    ): TrackDownloadResult = synchronized(lock) {
        try {
            val downloader = requireDownloader(config)
            val hook = if (listener == null) null else HookAdapter(listener)
            val module = Python.getInstance().getModule(moduleName)
            val pyResult = module.callAttr(
                "download", downloader, url, hook, hook, hook
            )
            parseResult(pyResult, url)
        } catch (e: PyException) {
            // A typed track/infrastructure failure surfaced from Python.
            failureFromPy(e, url)
        } catch (e: RuntimeException) {
            // Interpreter not started, module missing, malformed result, etc.
            TrackDownloadResult.Failure(url, DownloadError.unknown(e.message))
        }
    }

    /**
     * Path [url] would be written to, resolved without downloading — used for
     * idempotent status checks (T-035 sidecar fast path lives in Python).
     * Returns null when the track can't be resolved locally/offline.
     */
    fun expectedOutputPath(
        url: String,
        config: TrackDownloadConfig = TrackDownloadConfig.DEFAULT
    ): String? = synchronized(lock) {
        try {
            val downloader = requireDownloader(config)
            val module = Python.getInstance().getModule(moduleName)
            val py = module.callAttr("expected_output_path", downloader, url)
            val value = py.toJava(String::class.java)
            value as? String
        } catch (e: PyException) {
            null
        } catch (e: RuntimeException) {
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
        ageSeconds: Double? = null
    ): Int = synchronized(lock) {
        try {
            val downloader = requireDownloader(config)
            val module = Python.getInstance().getModule(moduleName)
            val py = if (ageSeconds == null) {
                module.callAttr("cleanup_temp", downloader)
            } else {
                module.callAttr("cleanup_temp", downloader, ageSeconds)
            }
            (py.toJava(Number::class.java) as? Number)?.toInt() ?: 0
        } catch (e: PyException) {
            0
        } catch (e: RuntimeException) {
            0
        }
    }

    // ------------------------------------------------------------------
    // Internal parsing / mapping.
    // ------------------------------------------------------------------

    /**
     * Converts the Python result dict (the return of `ghostify_dl.download`) into
     * a typed [TrackDownloadResult]. Pure Kotlin — no Python, no I/O.
     */
    internal fun parseResult(pyResult: PyObject, url: String): TrackDownloadResult {
        val raw = pyResult.toJava(Map::class.java)
        val map = raw as? Map<*, *>
        return if (map != null) {
            TrackDownloadResult.fromMap(map)
        } else {
            TrackDownloadResult.Failure(
                url = url,
                error = DownloadError.unknown("Download returned an unexpected result.")
            )
        }
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
         * Mirrors `on_download_start` (called with the `_track_dict` map). Chaquopy
         * cannot auto-convert a Python dict into a Kotlin Map-typed parameter, so
         * we accept PyObject and convert explicitly (cf. PlaylistMetadataBridge).
         */
        fun onDownloadStart(track: PyObject) {
            val map = track.toJava(Map::class.java) as? Map<*, *>
            if (map != null) listener.onDownloadStart(TrackInfo.fromMap(map))
        }

        /** Mirrors `on_progress` (called with percent: int, message: str). */
        fun onProgress(percent: Int, message: String?) {
            listener.onProgress(percent, message)
        }

        /**
         * Mirrors `on_download_complete` (called with the result payload map). Same
         * PyObject conversion as `onDownloadStart`.
         */
        fun onDownloadComplete(result: PyObject) {
            val map = result.toJava(Map::class.java) as? Map<*, *>
            if (map != null) listener.onDownloadComplete(TrackDownloadResult.fromMap(map))
        }
    }

    companion object {
        const val DEFAULT_MODULE_NAME = "ghostify_dl"
    }
}
