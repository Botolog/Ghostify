package com.ghostify.python

/**
 * Value types crossing the bridge. Kept free of Android dependencies so the
 * codec and the bridge's concurrency primitives stay JVM-testable.
 */

/**
 * A value returned from Python after JSON normalization:
 * `null`, [Boolean], [Long]/[Int]/[Double], [String], or `Map<String, Any?>` /
 * `List<Any?>` recursively. Empty dicts become empty maps, empty lists become
 * empty lists.
 */
typealias PyValue = Any?

/** Metadata attached to every host response. */
data class HostMeta(
    /** Stable per-interpreter identifier; changes only when the host restarts. */
    val interpreterId: String,
    /** Milliseconds the host process has been up (from interpreter start). */
    val hostUptimeMs: Long,
    /** First entry of Build.SUPPORTED_ABIS in the host process. */
    val abi: String,
)

/** Parsed result of one bridge call. */
internal data class HostResponse(
    val ok: PyValue,
    val meta: HostMeta?,
)

/** Parsed bridge request as sent by the client. */
internal data class Request(
    val module: String,
    val method: String,
    /** JSON array text that Python deserializes into the positional args. */
    val argsJson: String,
)

/** Result of [PythonRuntime.boot]. */
data class BootResult(
    val interpreterId: String,
    val abi: String,
    val pythonVersion: String,
    /** Wall-clock time of the whole cold-start round trip (bind + interpreter start + probe). */
    val loadMs: Long,
    val hostUptimeMs: Long,
    /** The raw `boot_probe` dict returned by Python. */
    val probe: Map<*, *>,
)

/** Observable lifecycle/transport events emitted by [PythonRuntime.eventFlow]. */
sealed class PythonEvent {
    /** Transport to the host process is established (binder connected). */
    object HostConnected : PythonEvent()

    /** The host process died (crash, OOM kill or explicit reset). */
    object HostDied : PythonEvent()

    /** A fresh interpreter came up in place of a previous one. */
    data class HostRestarted(val interpreterId: String) : PythonEvent()

    /** Arbitrary JSON pushed from Python (progress hooks, diagnostics). */
    data class PythonEmitted(val json: String) : PythonEvent()
}
