/*
 * TEST-ONLY stub of the Chaquopy Java API surface used by TrackDownloadBridge.
 *
 * The real Chaquopy jar (`com.chaquo.python:chaquopy:...`) is only available
 * inside the Android build, so JVM unit tests cannot link against it. This
 * minimal stand-in lets the bridge + PyConverters compile and run on the JVM
 * so result-parsing, conversion and error-mapping logic are verified here; the
 * real interpreter is exercised on-device by the instrumentation tests for
 * T-030..T-041.
 *
 * Surface modelled:
 *   - Python.getInstance() / getModule(name)
 *   - PyObject.callAttr(name, args...)
 *   - PyObject.type() / asList() / asMap() / toBoolean() / toInt() /
 *     toLong() / toDouble() / toString() (the low-level accessors our
 *     hand-rolled conversion relies on, in place of `toJava(Map)`)
 *   - PyException(message)
 *
 * Python value model: a `PyObject` wraps a plain Java value — a `Map`
 * (Python dict), a `List` (Python list), or a String/Number/Boolean leaf.
 * Python `None` is represented by a **null** `PyObject` (Chaquopy does the
 * same), so `callAttr` and the container views can yield null.
 *
 * Behaviour is injectable through Python.stubModule so tests can simulate
 * success results, typed Python failures, and progress-hook callbacks.
 */
package com.chaquo.python

/** Mirrors Chaquopy: an exception thrown from Python to Java. */
class PyException(message: String) : RuntimeException(message)

/**
 * Mirrors Chaquopy's `PyObject`. Holds a plain Java value and exposes the
 * low-level accessors that `PyConverters` uses to read Python trees without
 * relying on Chaquopy's (unreliable) automatic `toJava` conversion.
 */
class PyObject internal constructor(
    private val value: Any?,
    private val moduleName: String? = null
) {

    fun callAttr(name: String, vararg args: Any?): PyObject? {
        val receiver = value as? MutableMap<String, Any?>
        if (name == "__setitem__" && receiver != null) {
            // Mirrors `builtins.dict.__setitem__` (used by toPyOptions).
            receiver[args[0].toString()] = args[1]
            return null
        }
        return Python.invokeModuleCall(moduleName, name, args.asList())
    }

    /** Returns the wrapped value for [clazz], as the old auto-conversion did. */
    fun <T> toJava(clazz: Class<T>): Any? = value

    /** Mirrors `PyObject.type()`: the Python type, read via its `str()` form. */
    fun type(): PyObject = PyObject(kindString(value))

    /** Container access: a Python sequence → `List<PyObject>` (None → null). */
    fun asList(): List<PyObject?> {
        val list = value as? List<*>
            ?: throw UnsupportedOperationException(
                "Not a Python sequence: ${value?.javaClass?.name}"
            )
        return list.map { if (it == null) null else PyObject(it) }
    }

    /** Container access: a Python dict → `Map<PyObject, PyObject>` (None → null). */
    fun asMap(): Map<PyObject, PyObject?> {
        val map = value as? Map<*, *>
            ?: throw UnsupportedOperationException(
                "Not a Python dict: ${value?.javaClass?.name}"
            )
        val out = LinkedHashMap<PyObject, PyObject?>()
        for ((key, element) in map) {
            out[PyObject(key.toString())] = if (element == null) null else PyObject(element)
        }
        return out
    }

    fun toBoolean(): Boolean = (value as? Boolean) ?: throw ClassCastException("not a bool")
    fun toInt(): Int = (value as? Number)?.toInt() ?: throw ClassCastException("not a number")
    fun toLong(): Long = (value as? Number)?.toLong() ?: throw ClassCastException("not a number")
    fun toDouble(): Double = (value as? Number)?.toDouble() ?: throw ClassCastException("not a number")

    override fun toString(): String = value?.toString() ?: "None"

    private fun kindString(v: Any?): String = when (v) {
        is Map<*, *> -> "<class 'dict'>"
        is List<*> -> "<class 'list'>"
        is Boolean -> "<class 'bool'>"
        is Double, is Float -> "<class 'float'>"
        is Number -> "<class 'int'>"
        is String -> "<class 'str'>"
        else -> "<class 'NoneType'>"
    }
}

/** Mirrors Chaquopy's `Python` entry point. */
object Python {

    @Volatile
    private var handlers: MutableMap<String, (name: String, args: List<Any?>) -> PyObject?> =
        mutableMapOf()

    fun getInstance(): Python = this

    fun getModule(name: String): PyObject {
        if (!handlers.containsKey(name)) {
            if (name == "builtins") {
                // Synthetic module backing `toPyOptions` (`dict` / `__setitem__`).
                return PyObject(LinkedHashMap<String, Any?>(), moduleName = name)
            }
            throw IllegalStateException("No stub module installed for $name")
        }
        return PyObject(null, moduleName = name)
    }

    internal fun invokeModuleCall(
        module: String?,
        attr: String,
        args: List<Any?>
    ): PyObject? {
        val handler = handlers[module]
        if (handler != null) return handler.invoke(attr, args)
        if (module == "builtins" && attr == "dict") {
            return PyObject(LinkedHashMap<String, Any?>())
        }
        throw IllegalStateException("No stub module installed for $module")
    }

    // --- test plumbing (not part of the real Chaquopy API) ---

    fun stubModule(name: String, handler: (name: String, args: List<Any?>) -> PyObject?) {
        handlers[name] = handler
    }

    fun reset() {
        handlers.clear()
    }
}
