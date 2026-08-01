/*
 * JVM unit tests for PlaylistMetadataBridge (T-021) and the typed metadata
 * model, run against the test-only Chaquopy stub.
 *
 * T-021: "Python bridge exceptions propagate to Kotlin as typed failures,
 * never as raw crashes." These tests simulate Python raising `GhostifyError`
 * across the Chaquopy boundary (message prefixed with the Python exception
 * type name, exactly as the real runtime formats it) and assert the bridge
 * returns typed failures instead of throwing.
 *
 * Run with:
 *   kotlinc src/main/java/com/ghostify/python/(all kotlin files) \
 *           tests/stub/com/chaquo/python/(all kotlin files) \
 *           tests/PlaylistMetadataBridgeTest.kt \
 *           -include-runtime -d tests/bridge-test.jar
 *   java -jar tests/bridge-test.jar
 *
 * Real Chaquopy integration (actual interpreter on device) is covered
 * separately by T-010..T-020.
 */
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.ghostify.python.PlaylistFetchError
import com.ghostify.python.PlaylistFetchErrorCode
import com.ghostify.python.PlaylistFetchResult
import com.ghostify.python.PlaylistMetadata
import com.ghostify.python.PlaylistMetadataBridge
import com.ghostify.python.PlaylistTrack

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

private fun installFailureModule(message: String) {
    Python.stubModule("ghostify_dl") { _, _ ->
        throw PyException(message)
    }
}

private fun installSuccessModule(map: Map<String, Any?>) {
    Python.stubModule("ghostify_dl") { _, _ ->
        PyObject(map)
    }
}

private fun assertFailure(result: PlaylistFetchResult, code: PlaylistFetchErrorCode): PlaylistFetchError {
    val error = (result as? PlaylistFetchResult.Failure)?.error
        ?: throw AssertionError("Expected Failure, got $result")
    checkEquals("error code for $code", code, error.code)
    return error
}

private fun assertSuccess(result: PlaylistFetchResult): PlaylistMetadata {
    val metadata = (result as? PlaylistFetchResult.Success)?.metadata
        ?: throw AssertionError("Expected Success, got $result")
    return metadata
}

// ---------------------------------------------------------------------------
// T-021 — typed error propagation (never raw crashes).
// ---------------------------------------------------------------------------

private fun testTypedErrorMapping() {
    println("testTypedErrorMapping (T-021)")

    installFailureModule("ghostify_dl.GhostifyError: NO_NETWORK: Network unavailable. Check your connection and try again.")
    val e1 = assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.NO_NETWORK)
    check("NO_NETWORK retry hint present", e1.retryHint != null)

    installFailureModule("ghostify_dl.GhostifyError: RATE_LIMITED: Spotify is rate-limiting requests. Wait a moment and try again.")
    val e2 = assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.RATE_LIMITED)
    check("RATE_LIMITED retry hint present", e2.retryHint != null)

    installFailureModule("ghostify_dl.GhostifyError: PRIVATE: This playlist is private. Only public playlists are supported.")
    assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.PRIVATE)

    installFailureModule("ghostify_dl.GhostifyError: TIMEOUT: Playlist fetch timed out after 60s.")
    assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.TIMEOUT)

    installFailureModule("ghostify_dl.GhostifyError: NOT_FOUND: Playlist not found. It may have been deleted or the id is invalid.")
    assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.NOT_FOUND)
}

private fun testRawPythonErrorMapsToUnknown() {
    println("testRawPythonErrorMapsToUnknown (T-021 fallback)")

    // An arbitrary, non-GhostifyError exception must map to UNKNOWN, not crash.
    installFailureModule("KeyError: 'ownerV2'")
    val error = assertFailure(
        PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.UNKNOWN
    )
    check("raw error message preserved", error.message.contains("ownerV2"))

    installFailureModule("")
    assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.UNKNOWN)

    // Code token appears AFTER a long type prefix (exactly how Chaquopy formats it).
    installFailureModule("com.ghostify.python.bridge.ghostify_dl.GhostifyError: NOT_FOUND: x")
    assertFailure(PlaylistMetadataBridge().fetchPlaylistBlocking("id"), PlaylistFetchErrorCode.NOT_FOUND)
}

private fun testBridgeNeverThrowsOnInfrastructureFailures() {
    println("testBridgeNeverThrowsOnInfrastructureFailures (T-021)")

    // Python not started / module missing -> typed UNKNOWN, not a crash.
    Python.reset()
    val result = PlaylistMetadataBridge().fetchPlaylistBlocking("id")
    check("missing module maps to Failure", result is PlaylistFetchResult.Failure)
    check(
        "missing module maps to UNKNOWN",
        (result as PlaylistFetchResult.Failure).error.code == PlaylistFetchErrorCode.UNKNOWN
    )

    // Module returns a non-map (e.g. a Python string) -> typed failure.
    Python.stubModule("ghostify_dl") { _, _ -> PyObject("not a dict") }
    val bad = PlaylistMetadataBridge().fetchPlaylistBlocking("id")
    check("non-map result maps to Failure", bad is PlaylistFetchResult.Failure)
}

// ---------------------------------------------------------------------------
// Success parsing.
// ---------------------------------------------------------------------------

private fun sampleMap(): Map<String, Any?> = mapOf(
    "name" to "Today's Top Hits",
    "owner" to "Spotify",
    "cover_url" to "https://i.scdn.co/image/abc",
    "description" to "The hottest 50",
    "track_count" to 2,
    "tracks" to listOf(
        mapOf(
            "position" to 0,
            "spotify_id" to "abc123",
            "title" to "Song A",
            "artists" to "Artist A, Artist B",
            "album" to "Album A",
            "duration_ms" to 213000,
            "cover_url" to "https://i.scdn.co/image/trackA",
            "yt_id" to "ytAA"
        ),
        mapOf(
            "position" to 1,
            "spotify_id" to "def456",
            "title" to "Song B",
            "artists" to "Artist C",
            "album" to "Album B",
            "duration_ms" to 180000,
            "cover_url" to null,
            "yt_id" to null
        )
    )
)

private fun testSuccessParsing() {
    println("testSuccessParsing")
    installSuccessModule(sampleMap())
    val metadata = assertSuccess(PlaylistMetadataBridge().fetchPlaylistBlocking("some-id"))

    checkEquals("name", "Today's Top Hits", metadata.name)
    checkEquals("owner", "Spotify", metadata.owner)
    checkEquals("cover_url", "https://i.scdn.co/image/abc", metadata.coverUrl)
    checkEquals("track_count", 2, metadata.trackCount)
    checkEquals("tracks.size", 2, metadata.tracks.size)

    val first = metadata.tracks[0]
    checkEquals("t0.position", 0, first.position)
    checkEquals("t0.spotify_id", "abc123", first.spotifyId)
    checkEquals("t0.title", "Song A", first.title)
    checkEquals("t0.artists", "Artist A, Artist B", first.artists)
    checkEquals("t0.album", "Album A", first.album)
    checkEquals("t0.duration_ms", 213000L, first.durationMs)
    checkEquals("t0.cover_url", "https://i.scdn.co/image/trackA", first.coverUrl)
    checkEquals("t0.yt_id", "ytAA", first.ytId)

    val second = metadata.tracks[1]
    check("t1.cover_url null", second.coverUrl == null)
    check("t1.yt_id null (T-020 shape)", second.ytId == null)
}

private fun testDefensiveParsing() {
    println("testDefensiveParsing")

    // A track missing the authoritative spotify_id is skipped, not fatal.
    installSuccessModule(
        mapOf(
            "name" to "Mixed",
            "owner" to "Owner",
            "tracks" to listOf(
                mapOf("title" to "NoId"),  // skipped
                mapOf("position" to 5, "spotify_id" to "ok1")  // partial
            )
        )
    )
    val metadata = assertSuccess(PlaylistMetadataBridge().fetchPlaylistBlocking("id"))
    checkEquals("malformed track skipped", 1, metadata.tracks.size)
    checkEquals("partial track parsed", "ok1", metadata.tracks[0].spotifyId)
    checkEquals("track_count fallback", 1, metadata.trackCount)

    // Completely wrong types must not crash the parser.
    installSuccessModule(mapOf("name" to 42, "tracks" to "nope", "owner" to null))
    val safe = assertSuccess(PlaylistMetadataBridge().fetchPlaylistBlocking("id"))
    checkEquals("name fallback", "", safe.name)
    check("tracks fallback empty", safe.tracks.isEmpty())
}

private fun testEmptyPlaylistShape() {
    println("testEmptyPlaylistShape (T-014)")
    installSuccessModule(mapOf("name" to "Empty", "owner" to "O", "track_count" to 0, "tracks" to emptyList<Any>()))
    val metadata = assertSuccess(PlaylistMetadataBridge().fetchPlaylistBlocking("id"))
    checkEquals("empty playlist tracks", 0, metadata.tracks.size)
    checkEquals("empty playlist count", 0, metadata.trackCount)
}

fun main() {
    Python.reset()
    testTypedErrorMapping()
    testRawPythonErrorMapsToUnknown()
    testBridgeNeverThrowsOnInfrastructureFailures()
    testSuccessParsing()
    testDefensiveParsing()
    testEmptyPlaylistShape()
    println()
    println("$passes passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
