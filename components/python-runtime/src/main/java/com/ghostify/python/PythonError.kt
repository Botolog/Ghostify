package com.ghostify.python

/**
 * Typed failures raised by [PythonRuntime]. Nothing in the bridge ever crashes
 * the app with a raw exception: every Python error, host failure and transport
 * failure is mapped to one of these subtypes so callers can react to it.
 *
 * Kept free of Android dependencies so it is usable (and testable) on the JVM.
 */
sealed class PythonError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** [PythonRuntime.init] has not been called (or was called in a host process). */
    class NotInitialized(cause: Throwable? = null) :
        PythonError("PythonRuntime is not initialized; call PythonRuntime.init(context) first", cause)

    /** A bridge call was attempted on the main thread. */
    class MainThreadCall(cause: Throwable? = null) :
        PythonError("Python bridge calls must not run on the main thread", cause)

    /** The interpreter could not be reached (bind failure, interpreter start failure). */
    class InterpreterUnavailable(detail: String, cause: Throwable? = null) :
        PythonError("Python interpreter is unavailable: $detail", cause)

    /** The interpreter process died (crash / OOM kill / stopService). */
    class InterpreterCrashed(cause: Throwable? = null) :
        PythonError("The Python interpreter process terminated unexpectedly", cause)

    /** Connecting to the host process took longer than the configured limit. */
    class BindTimeout(timeoutMs: Long) :
        PythonError("Timed out connecting to the Python host after $timeoutMs ms")

    /** A call did not complete within the configured per-call limit. */
    class CallTimeout(timeoutMs: Long) :
        PythonError("Python call timed out after $timeoutMs ms")

    /** The Python code raised an exception; fields carry the Python-side details. */
    class PythonExceptionInfo(
        val pythonType: String,
        val pythonMessage: String,
        val pythonTraceback: String,
    ) : PythonError("Python $pythonType: $pythonMessage")

    /** The host process failed while executing the request (not a Python exception). */
    class HostErrorInfo(
        val kind: String,
        val hostType: String,
        val hostMessage: String,
    ) : PythonError("Host $hostType: $hostMessage")

    /** A request/response envelope could not be parsed or was malformed. */
    class InvalidRequest(detail: String, cause: Throwable? = null) :
        PythonError("Invalid bridge request: $detail", cause)
}
