/*
 * JVM unit tests for TrackDownloadBridge (T-030..T-041 locally-verifiable portions).
 *
 * These exercise the bridge's Kotlin-side logic — result parsing, typed error
 * mapping, skip handling, progress-hook wiring, expected-path delegation and
 * temp cleanup — against the test-only Chaquopy stub. They do NOT touch a real
 * interpreter or network; the on-device halves of T-030..T-041 live in
 * `tests/androidTest/.../TrackDownloadInstrumentedTest.kt` (see solution.md).
 *
 * Run with:
 *   kotlinc src/main/java/com/ghostify/trackdownload/TrackDownloadConfig.kt \
 *           src/main/java/com/ghostify/trackdownload/TrackDownloadModel.kt \
 *           src/main/java/com/ghostify/trackdownload/TrackProgressListener.kt \
 *           src/main/java/com/ghostify/trackdownload/TrackDownloadBridge.kt \
 *           tests/stub/com/chaquo/python/PythonStub.kt \
 *           tests/TrackDownloadBridgeTest.kt \
 *           -include-runtime -d tests/bridge-test.jar
 *   java -jar tests/bridge-test.jar
 */
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.ghostify.trackdownload.DownloadErrorKind
import com.ghostify.trackdownload.TrackDownloadBridge
import com.ghostify.trackdownload.TrackDownloadConfig
import com.ghostify.trackdownload.TrackDownloadResult
import com.ghostify.trackdownload.TrackInfo
import com.ghostify.trackdownload.TrackProgressListener

private var failures = 0
private var passes = 0

private fun check(name: String, condition: Boolean) {
    if (condition) {
        passes++
        println("  PASS  $name")
    } else {
        failures++
        println("  FAIL  $name")
    }
}

private fun checkEquals(name: String, expected: Any?, actual: Any?) {
    check("$name (expected=$expected, actual=$actual)", expected == actual)
}

/**
 * Installs a stub `ghostify_dl` that simulates spotdl's behaviour for one track:
 * `make_downloader` returns an opaque handle; `download` fires progress hooks on
 * the provided adapter (if any), then returns [result]; `expected_output_path`
 * returns [expectedPath]; `cleanup_temp` returns [orphanCount].
 *
 * Passing `throwDuringDownload` makes `download` raise a PyException whose
 * message carries a real ErrorKind token, to exercise typed-failure mapping.
 */
private fun installDownloaderStub(
    result: Map<String, Any?>,
    expectedPath: String? = null,
    orphanCount: Int = 0,
    throwDuringDownload: String? = null
) {
    Python.reset()
    Python.stubModule("ghostify_dl") { attr, args ->
        when (attr) {
            "make_downloader" -> PyObject("downloader-handle", "ghostify_dl")
            "download" -> {
                if (throwDuringDownload != null) {
                    throw PyException(throwDuringDownload)
                }
                val hook = args.getOrNull(2) as? TrackDownloadBridge.HookAdapter
                if (hook != null) {
                    hook.onDownloadStart(
                        PyObject(
                            mapOf(
                                "url" to args[1],
                                "spotify_id" to "4cOdK2wGLETKBW3PvgPWqT",
                                "title" to "Never Gonna Give You Up",
                                "artists" to "Rick Astley",
                                "album" to "Whenever You Need Somebody",
                                "duration_ms" to 213000L
                            )
                        )
                    )
                    hook.onProgress(PyObject(25), PyObject("Resolving YouTube"))
                    hook.onProgress(PyObject(50), PyObject("Downloading"))
                    hook.onProgress(PyObject(75), PyObject("Converting to MP3"))
                    hook.onProgress(PyObject(100), PyObject("Tagging"))
                    hook.onDownloadComplete(PyObject(result))
                }
                PyObject(result)
            }
            "expected_output_path" -> if (expectedPath == null) null else PyObject(expectedPath)
            "cleanup_temp" -> PyObject(orphanCount)
            else -> throw PyException("unknown attr " + attr)
        }
    }
}

private fun downloadedResult(outputPath: String, size: Long = 2131234L): Map<String, Any?> =
    mapOf(
        "status" to "DOWNLOADED",
        "url" to "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
        "output_path" to outputPath,
        "file_size" to size,
        "bitrate" to "128k",
        "title" to "Never Gonna Give You Up",
        "artists" to "Rick Astley",
        "album" to "Whenever You Need Somebody",
        "duration_ms" to 213000L
    )

private fun skippedResult(outputPath: String): Map<String, Any?> =
    mapOf(
        "status" to "SKIPPED",
        "url" to "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
        "output_path" to outputPath,
        "title" to "Never Gonna Give You Up",
        "artists" to "Rick Astley",
        "album" to "Whenever You Need Somebody",
        "bitrate" to "192k",
        "duration_ms" to 213000L
    )

private val TRACK_URL = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"

// ---------------------------------------------------------------------------
// T-030 / T-041 — parsed DOWNLOADED result (decodes + non-empty size + not
// truncated). The file itself is written by Python spotdl on device; here we
// pin the bridge's decoding of the result dict.
// ---------------------------------------------------------------------------

private fun testDownloadedResultParsed() {
    println("testDownloadedResultParsed (T-030 / T-041)")
    val path = "/store/Rick Astley - Never Gonna Give You Up.mp3"
    installDownloaderStub(downloadedResult(path, size = 2131234L), expectedPath = path)
    val result = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    check("result is Downloaded", result is TrackDownloadResult.Downloaded)
    val d = result as TrackDownloadResult.Downloaded
    checkEquals("outputPath", path, d.outputPath)
    checkEquals("fileSize", 2131234L, d.fileSize)
    checkEquals("bitrate", "128k", d.bitrate)
    checkEquals("title", "Never Gonna Give You Up", d.title)
    checkEquals("artists", "Rick Astley", d.artists)
    checkEquals("durationMs", 213000L, d.durationMs)
    check("file size non-zero", d.fileSize != null && d.fileSize > 0)
}

// ---------------------------------------------------------------------------
// T-035 — skip: a SKIPPED payload must be classified without re-download.
// ---------------------------------------------------------------------------

private fun testSkippedResultParsed() {
    println("testSkippedResultParsed (T-035)")
    val path = "/store/Rick Astley - Never Gonna Give You Up.mp3"
    installDownloaderStub(skippedResult(path), expectedPath = path)
    val result = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    check("result is Skipped", result is TrackDownloadResult.Skipped)
    val s = result as TrackDownloadResult.Skipped
    checkEquals("outputPath", path, s.outputPath)
    checkEquals("bitrate", "192k", s.bitrate)
}

// ---------------------------------------------------------------------------
// T-036 / T-039 — typed failures from PyException, mapped by ErrorKind token.
// ---------------------------------------------------------------------------

private fun testTypedFailureMapping() {
    println("testTypedFailureMapping (T-036 / T-039)")
    val msg = "ghostify_dl.TrackDownloadError: SEARCH_FAILED: no match on YouTube"
    installDownloaderStub(downloadedResult(""), throwDuringDownload = msg)
    val result = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    check("result is Failure", result is TrackDownloadResult.Failure)
    val f = result as TrackDownloadResult.Failure
    checkEquals("error kind", DownloadErrorKind.SEARCH_FAILED, f.error.kind)
    check("human message trimmed of kind prefix",
        f.error.message == "no match on YouTube")
    checkEquals("wireType", "SEARCH_FAILED", f.error.wireType)
}

private fun testFailureMatrix() {
    println("testFailureMatrix (T-036 / T-039 kind mapping)")
    val cases = listOf(
        "ghostify_dl.TrackDownloadError: NO_TRACK: not found" to DownloadErrorKind.NO_TRACK,
        "ghostify_dl.TrackDownloadError: METADATA_FAILED: spotify down" to DownloadErrorKind.METADATA_FAILED,
        "ghostify_dl.TrackDownloadError: AUDIO_UNAVAILABLE: region-blocked" to DownloadErrorKind.AUDIO_UNAVAILABLE,
        "ghostify_dl.TrackDownloadError: CONVERSION_FAILED: ffmpeg error" to DownloadErrorKind.CONVERSION_FAILED,
        "ghostify_dl.TrackDownloadError: TAGGING_FAILED: bad tag" to DownloadErrorKind.TAGGING_FAILED,
        "ghostify_dl.TrackDownloadError: VALIDATION_FAILED: truncated" to DownloadErrorKind.VALIDATION_FAILED,
        "ghostify_dl.TrackDownloadError: IO: write failed" to DownloadErrorKind.IO,
        "ghostify_dl.TrackDownloadError: INTERRUPTED: killed" to DownloadErrorKind.INTERRUPTED,
        "ghostify_dl.TrackDownloadError: DEPENDENCY: ffmpeg missing" to DownloadErrorKind.DEPENDENCY
    )
    for ((message, expected) in cases) {
        installDownloaderStub(downloadedResult(""), throwDuringDownload = message)
        val r = TrackDownloadBridge().downloadBlocking(TRACK_URL)
        val f = r as? TrackDownloadResult.Failure
            ?: throw AssertionError("Expected Failure for " + expected + ", got " + r)
        checkEquals("kind for " + expected.name, expected, f.error.kind)
    }
}

private fun testRawExceptionMapsToUnknown() {
    println("testRawExceptionMapsToUnknown (T-036 fallback)")
    // A non-TrackDownloadError Python exception: no ErrorKind token -> UNKNOWN.
    installDownloaderStub(downloadedResult(""), throwDuringDownload = "KeyError: 'ownerV2'")
    val r = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    val f = r as? TrackDownloadResult.Failure ?: throw AssertionError("Expected Failure")
    checkEquals("raw error -> UNKNOWN", DownloadErrorKind.UNKNOWN, f.error.kind)
    check("raw error message preserved", f.error.message.contains("ownerV2"))
}

private fun testBridgeNeverThrowsOnInfrastructureFailure() {
    println("testBridgeNeverThrowsOnInfrastructureFailure")
    // No stub module installed -> Python.getInstance/getModule throw
    // IllegalStateException (a RuntimeException); the bridge must map it to a
    // Failure, not crash.
    Python.reset()
    val r = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    check("infra failure -> Failure", r is TrackDownloadResult.Failure)
    checkEquals("infra failure kind", DownloadErrorKind.UNKNOWN,
        (r as TrackDownloadResult.Failure).error.kind)
}

// ---------------------------------------------------------------------------
// T-040 — progress hooks fire start + progress + complete.
// ---------------------------------------------------------------------------

private class RecordingListener : TrackProgressListener {
    val starts = mutableListOf<TrackInfo>()
    val progresses = mutableListOf<Pair<Int, String?>>()
    val completes = mutableListOf<TrackDownloadResult>()

    override fun onDownloadStart(track: TrackInfo) { starts.add(track) }
    override fun onProgress(percent: Int, message: String?) { progresses.add(percent to message) }
    override fun onDownloadComplete(result: TrackDownloadResult) { completes.add(result) }
}

private fun testProgressHooksFire() {
    println("testProgressHooksFire (T-040)")
    val path = "/store/Rick Astley - Never Gonna Give You Up.mp3"
    installDownloaderStub(downloadedResult(path))
    val listener = RecordingListener()
    val result = TrackDownloadBridge().downloadBlocking(TRACK_URL, listener = listener)
    check("start fired once", listener.starts.size == 1)
    checkEquals("start title", "Never Gonna Give You Up", listener.starts[0].title)
    checkEquals("start artists", "Rick Astley", listener.starts[0].artists)
    check("progress fired", listener.progresses.isNotEmpty())
    check("progress reached 100", listener.progresses.any { it.first == 100 })
    check("complete fired once", listener.completes.size == 1)
    check("complete is Downloaded", listener.completes[0] is TrackDownloadResult.Downloaded)
    check("returned result is Downloaded", result is TrackDownloadResult.Downloaded)
}

private fun testProgressNullSafeWhenNoListener() {
    println("testProgressNullSafeWhenNoListener (T-040 null listener)")
    installDownloaderStub(downloadedResult("/store/x.mp3"))
    val result = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    check("no listener -> still Downloaded", result is TrackDownloadResult.Downloaded)
}

// ---------------------------------------------------------------------------
// T-035 / T-037 delegation — expected_output_path + cleanup_temp.
// ---------------------------------------------------------------------------

private fun testExpectedOutputPathAndCleanup() {
    println("testExpectedOutputPathAndCleanup (T-035 / T-037 delegation)")
    val path = "/store/Rick Astley - Never Gonna Give You Up.mp3"
    installDownloaderStub(downloadedResult(path), expectedPath = path, orphanCount = 3)
    val bridge = TrackDownloadBridge()
    checkEquals("expected output path", path,
        bridge.expectedOutputPath(TRACK_URL))
    checkEquals("cleanup_temp orphan count", 3, bridge.cleanupTemp())
    // Unresolvable track -> null (Python best-effort), never a crash.
    installDownloaderStub(downloadedResult(path), expectedPath = null)
    check("unresolvable -> null", bridge.expectedOutputPath("https://open.spotify.com/xxx") == null)
}

// ---------------------------------------------------------------------------
// T-038 — the bridge forwards a sanitized filename from Python verbatim; no
// invalid characters can reach the caller. (Sanitization itself is pinned in
// the Python test_sanitize + T-038 e2e; here we assert the Kotlin path is a
// clean passthrough.)
// ---------------------------------------------------------------------------

private fun testOutputPathHasNoInvalidChars() {
    println("testOutputPathHasNoInvalidChars (T-038 passthrough)")
    val cleanPath = "/store/Artist A, Artist B - Song Title.mp3"
    installDownloaderStub(downloadedResult(cleanPath), expectedPath = cleanPath)
    val result = TrackDownloadBridge().downloadBlocking(TRACK_URL)
    val p = (result as TrackDownloadResult.Downloaded).outputPath
    for (c in arrayOf("\\", ":", "*", "\"", "<", ">", "|", "?")) {
        check("no invalid char '" + c + "' (except /) in outputPath", !p.contains(c))
    }
}

// ---------------------------------------------------------------------------
// Config validation (Kotlin-side guard, T-034 bitrate domain).
// ---------------------------------------------------------------------------

private fun testConfigBitrateValidation() {
    println("testConfigBitrateValidation (T-034 domain)")
    check("128 allowed", TrackDownloadConfig.isValidBitrate(128))
    check("192 allowed", TrackDownloadConfig.isValidBitrate(192))
    check("320 allowed", TrackDownloadConfig.isValidBitrate(320))
    check("256 not allowed", !TrackDownloadConfig.isValidBitrate(256))
    checkEquals("allowed bitrates", listOf(128, 192, 320),
        TrackDownloadConfig.ALLOWED_BITRATES.toList())
}

fun main() {
    Python.reset()
    testDownloadedResultParsed()
    testSkippedResultParsed()
    testTypedFailureMapping()
    testFailureMatrix()
    testRawExceptionMapsToUnknown()
    testBridgeNeverThrowsOnInfrastructureFailure()
    testProgressHooksFire()
    testProgressNullSafeWhenNoListener()
    testExpectedOutputPathAndCleanup()
    testOutputPathHasNoInvalidChars()
    testConfigBitrateValidation()
    println()
    println("$passes passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
