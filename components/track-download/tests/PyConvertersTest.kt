/*
 * JVM unit tests for PyConverters — the hand-rolled Chaquopy PyObject
 * converter used by TrackDownloadBridge (result parsing, expected_output_path,
 * cleanup_temp and the progress HookAdapter), in place of the unreliable
 * `toJava(Map)` auto-conversion that fails with "TypeError: Cannot convert
 * dict object to java.util.map".
 *
 * Runs against the test-only Chaquopy stub, which models the low-level
 * `PyObject` accessors (`type`, `asMap`, `asList`, primitive converters) with
 * plain Java values.
 *
 * Run with:
 *   kotlinc src/main/java/com/ghostify/trackdownload/(all kotlin files) \
 *           tests/stub/com/chaquo/python/(all kotlin files) \
 *           tests/PyConvertersTest.kt \
 *           -include-runtime -d tests/converters-test.jar
 *   java -jar tests/converters-test.jar
 */
import com.chaquo.python.PyObject
import com.ghostify.trackdownload.PyConverters

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

private fun testKindDetection() {
    println("testKindDetection")
    checkEquals("dict kind", "dict", PyConverters.kindOf(PyObject(mapOf<String, Any?>("a" to 1))))
    checkEquals("list kind", "list", PyConverters.kindOf(PyObject(listOf<Any?>(1))))
    checkEquals("str kind", "str", PyConverters.kindOf(PyObject("x")))
    checkEquals("int kind", "int", PyConverters.kindOf(PyObject(42)))
    check("isDict true for dict", PyConverters.isDict(PyObject(mapOf<String, Any?>())))
    check("isDict false for str", !PyConverters.isDict(PyObject("x")))
    check("isDict false for null", !PyConverters.isDict(null))
}

private fun testScalarConversions() {
    println("testScalarConversions")
    checkEquals("string", "/store/Artist - Song.mp3", PyConverters.string(PyObject("/store/Artist - Song.mp3")))
    check("string null for None", PyConverters.string(null) == null)
    checkEquals("int (orphan count)", 3, PyConverters.int(PyObject(3)))
    checkEquals("int from Long", 3, PyConverters.int(PyObject(3L)))
    check("int null for wrong type", PyConverters.int(PyObject("x")) == null)
    check("int null for None", PyConverters.int(null) == null)
    checkEquals("long (file size)", 2131234L, PyConverters.long(PyObject(2131234L)))
    checkEquals("boolean", true, PyConverters.boolean(PyObject(true)))
}

private fun testDeepNestedConversion() {
    println("testDeepNestedConversion")
    // The shape ghostify_dl.download returns: a flat result dict with None leaves.
    val py = PyObject(
        mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
            "output_path" to "/store/Rick Astley - Never Gonna Give You Up.mp3",
            "file_size" to 2131234L,
            "bitrate" to "128k",
            "duration_ms" to 213000L,
            "error_type" to null,
            "error" to null
        )
    )
    val map = PyConverters.stringMap(py)
    checkEquals("status", "DOWNLOADED", map["status"])
    checkEquals("output_path", "/store/Rick Astley - Never Gonna Give You Up.mp3", map["output_path"])
    checkEquals("file_size (int -> Long)", 2131234L, map["file_size"])
    check("error_type is null", map["error_type"] == null)
}

private fun testHookPayloads() {
    println("testHookPayloads")
    // onDownloadStart / on_download_complete receive the _track_dict shape.
    val start = PyConverters.stringMap(
        PyObject(
            mapOf(
                "url" to "https://open.spotify.com/track/x",
                "spotify_id" to "4cOdK2wGLETKBW3PvgPWqT",
                "title" to "Never Gonna Give You Up",
                "artists" to "Rick Astley",
                "duration_ms" to 213000L
            )
        )
    )
    checkEquals("start title", "Never Gonna Give You Up", start["title"])
    checkEquals("start duration", 213000L, start["duration_ms"])
}

private fun testNonDictDegradesToEmpty() {
    println("testNonDictDegradesToEmpty")
    check("stringMap of non-dict empty", PyConverters.stringMap(PyObject("x")).isEmpty())
    check("stringMap of null empty", PyConverters.stringMap(null).isEmpty())
    check("asList of non-list empty", PyConverters.asList(PyObject("x")).isEmpty())
    check("asMap of non-dict empty", PyConverters.asMap(PyObject("x")).isEmpty())
}

fun main() {
    testKindDetection()
    testScalarConversions()
    testDeepNestedConversion()
    testHookPayloads()
    testNonDictDegradesToEmpty()
    println()
    println("$passes passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
