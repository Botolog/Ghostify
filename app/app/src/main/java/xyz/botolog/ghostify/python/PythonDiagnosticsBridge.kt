package xyz.botolog.ghostify.python

import com.chaquo.python.PyException
import com.chaquo.python.Python
import timber.log.Timber

/** One row of the read-only report: the version loaded here plus its file. */
data class PythonLibraryInfo(
    val name: String,
    val version: String,
    val source: String = "",
    val error: String? = null,
) {
    val isAvailable: Boolean get() = error == null
}

/** The running interpreter plus every library row, or a readable failure. */
data class PythonDiagnostics(
    val pythonVersion: String? = null,
    val implementation: String? = null,
    val libraries: List<PythonLibraryInfo> = emptyList(),
    val error: String? = null,
) {
    val isFailure: Boolean get() = error != null
}

/**
 * Read-only bridge to `ghostify_dl.library_diagnostics_report`; never throws and
 * must be called off the main thread.
 */
class PythonDiagnosticsBridge(
    private val moduleName: String = DEFAULT_MODULE_NAME,
) {

    fun reportBlocking(): PythonDiagnostics = try {
        val module = Python.getInstance().getModule(moduleName)
        val parsed = parseReport(PyConverters.toJava(module.callAttr(FN_LIBRARY_DIAGNOSTICS)))
        Timber.i("PythonDiagnosticsBridge: reporting ${parsed.libraries.size} libraries")
        parsed
    } catch (e: PyException) {
        Timber.e(e, "PythonDiagnosticsBridge: diagnostics FAILED")
        PythonDiagnostics(error = e.message ?: UNKNOWN_ERROR)
    } catch (e: RuntimeException) {
        Timber.e(e, "PythonDiagnosticsBridge: diagnostics FAILED")
        PythonDiagnostics(error = e.message ?: UNKNOWN_ERROR)
    }

    companion object {
        const val DEFAULT_MODULE_NAME = "ghostify_dl"
        private const val FN_LIBRARY_DIAGNOSTICS = "library_diagnostics_report"
        private const val UNKNOWN_ERROR = "The Python diagnostics are unavailable."
        internal const val UNEXPECTED_RESULT = "The bridge returned an unexpected result."
        internal const val UNKNOWN_VERSION = "unknown"
    }
}

internal fun parseReport(raw: Any?): PythonDiagnostics {
    if (raw !is Map<*, *>) {
        return PythonDiagnostics(error = PythonDiagnosticsBridge.UNEXPECTED_RESULT)
    }
    return PythonDiagnostics(
        pythonVersion = diagnosticText(raw["python_version"]),
        implementation = diagnosticText(raw["implementation"]),
        libraries = parseLibraries(raw["libraries"]),
        error = diagnosticText(raw["error"]),
    )
}

internal fun parseLibraries(raw: Any?): List<PythonLibraryInfo> {
    if (raw !is List<*>) return emptyList()
    return raw.mapNotNull { entry ->
        if (entry is Map<*, *>) libraryFromMap(entry) else null
    }
}

private fun libraryFromMap(map: Map<*, *>): PythonLibraryInfo? {
    val name = diagnosticText(map["name"]) ?: return null
    return PythonLibraryInfo(
        name = name,
        version = diagnosticText(map["version"]) ?: PythonDiagnosticsBridge.UNKNOWN_VERSION,
        source = diagnosticText(map["source"]).orEmpty(),
        error = diagnosticText(map["error"]),
    )
}

private fun diagnosticText(value: Any?): String? =
    value?.toString()?.trim()?.ifEmpty { null }
