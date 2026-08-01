/*
 * TEST-ONLY stub of the Chaquopy Java API surface used by TrackDownloadBridge.
 *
 * The real Chaquopy jar (`com.chaquo.python:chaquopy:...`) is only available
 * inside the Android build, so JVM unit tests cannot link against it. This
 * minimal stand-in lets TrackDownloadBridge compile and run on the JVM so the
 * bridge's result-parsing and error-mapping logic is verified here; the real
 * interpreter is exercised on-device by the instrumentation tests for
 * T-030..T-041.
 *
 * Surface modelled:
 *   - Python.getInstance() / getModule(name)
 *   - PyObject.callAttr(name, args...) / PyObject.toJava(clazz)
 *   - PyException(message)
 *
 * Behaviour is injectable through Python.stubModule so tests can simulate
 * success results, typed Python failures, and progress-hook callbacks.
 */
package com.chaquo.python

/** Mirrors Chaquopy: an exception thrown from Python to Java. */
class PyException(message: String) : RuntimeException(message)

/**
 * Mirrors Chaquopy's `PyObject`. Holds a value that `toJava` returns as-is;
 * in the real runtime this deep-converts a Python object to its Java form.
 */
class PyObject internal constructor(
    private val value: Any?,
    private val moduleName: String? = null
) {

    fun callAttr(name: String, vararg args: Any?): PyObject =
        Python.invokeModuleCall(moduleName, name, args.asList())

    /**
     * Mirrors Chaquopy's `toJava`: returns the held value for [clazz], or null
     * when the held value is null.
     */
    fun <T> toJava(clazz: Class<T>): Any? = value
}

/** Mirrors Chaquopy's `Python` entry point. */
object Python {

    @Volatile
    private var handlers: MutableMap<String, (name: String, args: List<Any?>) -> PyObject> =
        mutableMapOf()

    fun getInstance(): Python = this

    fun getModule(name: String): PyObject {
        if (!handlers.containsKey(name)) {
            throw IllegalStateException("No stub module installed for $name")
        }
        return PyObject(null, moduleName = name)
    }

    internal fun invokeModuleCall(
        module: String?,
        attr: String,
        args: List<Any?>
    ): PyObject {
        val handler = handlers[module]
            ?: throw IllegalStateException("No stub module installed for $module")
        return handler.invoke(attr, args)
    }

    // --- test plumbing (not part of the real Chaquopy API) ---

    fun stubModule(name: String, handler: (name: String, args: List<Any?>) -> PyObject) {
        handlers[name] = handler
    }

    fun reset() {
        handlers.clear()
    }
}
