/*
 * JVM unit tests for PyConverters — the hand-rolled Chaquopy PyObject
 * converter used by PlaylistMetadataBridge (in place of the unreliable
 * `toJava(Map)` auto-conversion that fails with "TypeError: Cannot convert
 * dict object to java.util.map").
 *
 * Runs against the test-only Chaquopy stub, which models the low-level
 * `PyObject` accessors (`type`, `asMap`, `asList`, primitive converters) with
 * plain Java values.
 *
 * Run with:
 *   kotlinc src/main/java/com/ghostify/python/(all kotlin files) \
 *           tests/stub/com/chaquo/python/(all kotlin files) \
 *           tests/PyConvertersTest.kt \
 *           -include-runtime -d tests/converters-test.jar
 *   java -jar tests/converters-test.jar
 */
import com.chaquo.python.PyObject
import com.ghostify.python.PyConverters

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
    checkEquals("bool kind", "bool", PyConverters.kindOf(PyObject(true)))
    checkEquals("int kind", "int", PyConverters.kindOf(PyObject(42)))
    checkEquals("float kind", "float", PyConverters.kindOf(PyObject(1.5)))
    checkEquals("null kind", "", PyConverters.kindOf(null))
    check("isDict true for dict", PyConverters.isDict(PyObject(mapOf<String, Any?>())))
    check("isDict false for str", !PyConverters.isDict(PyObject("x")))
    check("isDict false for null", !PyConverters.isDict(null))
}

private fun testScalarConversions() {
    println("testScalarConversions")
    checkEquals("string", "hello", PyConverters.string(PyObject("hello")))
    check("string null for None", PyConverters.string(null) == null)
    checkEquals("int", 42, PyConverters.int(PyObject(42)))
    checkEquals("int from Long", 42, PyConverters.int(PyObject(42L)))
    check("int null for wrong type", PyConverters.int(PyObject("x")) == null)
    check("int null for None", PyConverters.int(null) == null)
    checkEquals("long", 42L, PyConverters.long(PyObject(42)))
    checkEquals("double", 2.5, PyConverters.double(PyObject(2.5)))
    checkEquals("boolean", true, PyConverters.boolean(PyObject(true)))
    check("boolean null for int", PyConverters.boolean(PyObject(1)) == null)
}

private fun testDeepNestedConversion() {
    println("testDeepNestedConversion")
    // The shape fetch_playlist returns: dict -> list -> dicts, with None leaves.
    val py = PyObject(
        mapOf(
            "name" to "Today's Top Hits",
            "track_count" to 2,
            "tracks" to listOf(
                mapOf(
                    "position" to 0,
                    "spotify_id" to "abc123",
                    "duration_ms" to 213000L,
                    "cover_url" to "https://i.scdn.co/image/trackA"
                ),
                mapOf(
                    "position" to 1,
                    "spotify_id" to "def456",
                    "duration_ms" to 180000L,
                    "cover_url" to null
                )
            )
        )
    )
    val map = PyConverters.stringMap(py)
    checkEquals("name", "Today's Top Hits", map["name"])
    checkEquals("track_count (int -> Long)", 2L, map["track_count"])
    val tracks = map["tracks"] as? List<*>
    check("tracks decoded as list", tracks != null && tracks.size == 2)
    if (tracks != null) {
        val first = tracks[0] as Map<*, *>
        checkEquals("t0.spotify_id", "abc123", first["spotify_id"])
        checkEquals("t0.duration_ms", 213000L, first["duration_ms"])
        val second = tracks[1] as Map<*, *>
        check("t1.cover_url is null", second["cover_url"] == null)
    }
}

private fun testFloatAndBoolLeaves() {
    println("testFloatAndBoolLeaves")
    val map = PyConverters.stringMap(
        PyObject(
            mapOf(
                "rating" to 4.5,
                "resolve_yt" to true,
                "off" to false
            )
        )
    )
    checkEquals("float leaf", 4.5, map["rating"])
    checkEquals("true leaf", true, map["resolve_yt"])
    checkEquals("false leaf", false, map["off"])
}

private fun testNonDictDegradesToEmpty() {
    println("testNonDictDegradesToEmpty")
    check("stringMap of non-dict empty", PyConverters.stringMap(PyObject("x")).isEmpty())
    check("stringMap of null empty", PyConverters.stringMap(null).isEmpty())
    check("stringMap of list empty", PyConverters.stringMap(PyObject(listOf(1, 2))).isEmpty())
    check("asList of non-list empty", PyConverters.asList(PyObject("x")).isEmpty())
    check("asMap of non-dict empty", PyConverters.asMap(PyObject("x")).isEmpty())
}

fun main() {
    testKindDetection()
    testScalarConversions()
    testDeepNestedConversion()
    testFloatAndBoolLeaves()
    testNonDictDegradesToEmpty()
    println()
    println("$passes passed, $failures failed")
    if (failures > 0) kotlin.system.exitProcess(1)
}
