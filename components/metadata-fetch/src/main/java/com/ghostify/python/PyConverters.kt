package com.ghostify.python

import com.chaquo.python.PyObject

/**
 * Hand-rolled conversion of Chaquopy `PyObject` values into plain Java/Kotlin
 * values.
 *
 * Chaquopy's automatic conversion (`PyObject.toJava(...)`) is unreliable for
 * Python container types on Android: deep-converting a dict that contains
 * lists/dicts throws `TypeError: Cannot convert dict object to
 * java.util.map`, and dicts handed to Kotlin `Map`-typed parameters are
 * rejected the same way. These helpers instead walk the `PyObject` tree
 * explicitly using Chaquopy's low-level accessors:
 *
 *  * `asMap()`  — Python dict `[]` access (`Map<PyObject, PyObject>`).
 *  * `asList()` — Python sequence access (`List<PyObject>`).
 *  * `toInt()` / `toLong()` / `toDouble()` / `toBoolean()` / `toString()` —
 *    primitive accessors.
 *  * Python `None` arrives as a **null** `PyObject` on the Java side.
 *
 * Everything is null-safe and defensive: wrong types degrade to `null` or
 * empty containers instead of throwing, so a malformed bridge payload can
 * never crash the app. The Python type of a value is read via `type()` and
 * matched on its built-in name (our bridges only ever produce plain built-ins).
 */
object PyConverters {

    /** The built-in Python type name of [py] ("dict", "list", "str", "bool",
     *  "int", "float", ...), or "" when [py] is null or the type is unreadable. */
    fun kindOf(py: PyObject?): String {
        if (py == null) return ""
        return try {
            py.type().toString().substringAfter("<class '").substringBefore("'>")
        } catch (e: RuntimeException) {
            ""
        }
    }

    /** True when [py] is a Python `dict`; false for null and every other type. */
    fun isDict(py: PyObject?): Boolean = kindOf(py) == "dict"

    /** Python `str` → `String`; None → null. */
    fun string(py: PyObject?): String? =
        if (py == null) null else py.toString()

    /** Python `int` → `Int`; anything else (or None) → null. */
    fun int(py: PyObject?): Int? =
        if (py == null) null else runCatching { py.toInt() }.getOrNull()

    /** Python `int` → `Long`; anything else (or None) → null. */
    fun long(py: PyObject?): Long? =
        if (py == null) null else runCatching { py.toLong() }.getOrNull()

    /** Python `float`/`int` → `Double`; anything else (or None) → null. */
    fun double(py: PyObject?): Double? =
        if (py == null) null else runCatching { py.toDouble() }.getOrNull()

    /** Python `bool` → `Boolean`; anything else (or None) → null. */
    fun boolean(py: PyObject?): Boolean? =
        if (py == null) null else runCatching { py.toBoolean() }.getOrNull()

    /** Container view of [py] as a Python dict (`[]` access). Never throws. */
    fun asMap(py: PyObject?): Map<PyObject, PyObject?> {
        if (py == null) return emptyMap()
        return try {
            py.asMap()
        } catch (e: UnsupportedOperationException) {
            emptyMap()
        }
    }

    /** Container view of [py] as a Python sequence. Never throws. */
    fun asList(py: PyObject?): List<PyObject?> {
        if (py == null) return emptyList()
        return try {
            py.asList()
        } catch (e: UnsupportedOperationException) {
            emptyList()
        }
    }

    /** Deep-converts a whole [py] tree into plain Java/Kotlin values:
     *  dict → `Map<String, Any?>`, list/tuple → `List<Any?>`,
     *  str → `String`, int → `Long`, float → `Double`, bool → `Boolean`,
     *  None → null. */
    fun toJava(py: PyObject?): Any? {
        if (py == null) return null
        return when (kindOf(py)) {
            "dict" -> stringMap(py)
            "list", "tuple" -> asList(py).map { toJava(it) }
            "bool" -> boolean(py)
            "int" -> long(py)
            "float" -> double(py)
            else -> py.toString()
        }
    }

    /** Deep-converts a Python dict into a `Map<String, Any?>` (string keys). */
    fun stringMap(py: PyObject?): Map<String, Any?> {
        if (py == null) return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        for ((key, value) in asMap(py)) {
            result[key.toString()] = toJava(value)
        }
        return result
    }
}
