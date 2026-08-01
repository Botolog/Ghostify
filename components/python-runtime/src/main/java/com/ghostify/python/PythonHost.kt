package com.ghostify.python

import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.util.UUID

/**
 * Owns the single Chaquopy interpreter inside the `:pythond` process.
 *
 * Only ever instantiated in `:pythond` (see [PythondService]); the app process
 * talks to it over AIDL and never loads the interpreter.
 *
 * Responsibilities:
 * - start Python exactly once per process on the main thread (Chaquopy
 *   requirement), with a stable [interpreterId] that lets callers verify reuse
 *   (TEST_PLAN T-024),
 * - make the bundled ffmpeg executable and prepend it to the Python
 *   subprocess PATH (TEST_PLAN T-025),
 * - execute bridge requests and marshal Python exceptions into typed error
 *   envelopes so nothing ever crashes the app.
 */
internal class PythonHost(private val context: Context) {

    private val tag = "PythonHost"
    private val interpreterId = UUID.randomUUID().toString()

    @Volatile private var started = false
    @Volatile private var startedAtRealtime = 0L

    /** Starts the interpreter; must be called on the process main thread. */
    fun ensureStarted(): HostMeta {
        if (started) return currentMeta()
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Python.start() must run on the main thread (Chaquopy requirement)"
        }
        val t0 = SystemClock.elapsedRealtime()
        Python.start(AndroidPlatform(context))
        started = true
        startedAtRealtime = SystemClock.elapsedRealtime()

        val ffmpegDir = FfmpegLocator.ensureExecutable(context)
        Python.getInstance()
            .getModule("ghostify_dl")
            .callAttr("prepend_path", ffmpegDir.absolutePath)

        Log.i(tag, "Python ${pythonVersion()} started in ${SystemClock.elapsedRealtime() - t0} ms on $abi")
        return currentMeta()
    }

    fun currentMeta(): HostMeta = HostMeta(
        interpreterId = interpreterId,
        hostUptimeMs = SystemClock.elapsedRealtime() - startedAtRealtime,
        abi = abi,
    )

    /** Runs one bridge request; never throws — always returns an envelope. */
    fun handleRequest(requestJson: String): String {
        return try {
            if (!started) throw IllegalStateException("interpreter not started")
            val request = HostProtocol.parseRequest(requestJson)
            val resultJson = Python.getInstance()
                .getModule("ghostify_dl")
                .callAttr("dispatch", request.module, request.method, request.argsJson)
                .toString()
            HostProtocol.encodeOkWithMeta(resultJson, currentMeta())
        } catch (e: PyException) {
            HostProtocol.encodeError(
                kind = "PythonException",
                type = "PyException",
                message = e.message ?: "Python exception",
                traceback = e.stackTraceToString(),
            )
        } catch (e: PythonError) {
            HostProtocol.encodeError(
                kind = "HostError",
                type = e.javaClass.simpleName,
                message = e.message ?: "host error",
            )
        } catch (e: Throwable) {
            HostProtocol.encodeError(
                kind = "HostError",
                type = e.javaClass.simpleName,
                message = e.message ?: "unexpected host error",
            )
        }
    }

    private val abi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    private fun pythonVersion(): String = try {
        Python.getInstance().getModule("sys").get("version").toString()
    } catch (e: Throwable) {
        "unknown"
    }
}
