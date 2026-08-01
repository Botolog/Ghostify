package com.ghostify

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ghostify.crash.CrashMarker
import com.ghostify.crash.FileLogCrashReporter
import com.ghostify.crash.GhostifyCrashHandler
import com.ghostify.crash.PrefsCrashMarker
import com.ghostify.data.db.AppDatabase
import com.ghostify.python.FfmpegLocator
import com.ghostify.recovery.KilledProcessRecovery
import com.ghostify.recovery.RecoveryGate
import com.ghostify.recovery.RoomRecoveryDao
import com.ghostify.recovery.StartupBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application entry point, owned by this (release) component.
 *
 * It is the single process-wide init point for:
 *  1. The Chaquopy interpreter, started eagerly because PROJECT.md guarantees
 *     Python is always used (metadata fetch, downloads). Starting it here means
 *     the whole app — Library browsing included — never blocks on a lazy first
 *     interpreter boot later.
 *  2. The bundled static ffmpeg binary: extracted to `filesDir/ffmpeg` and put
 *     on `PATH` so spotdl can exec it.
 *  3. The crash handler + killed-process recovery, installed before any network
 *     work so nothing can be left stuck mid-download (T-159).
 *
 * Python startup is wrapped in a fail-closed-but-degraded handler: if the
 * interpreter cannot boot (e.g. an old device, a tampered APK, a Chaquopy
 * packaging defect), the app still opens in a "library only" degraded mode
 * instead of crashing on every launch. Python-backed features then surface
 * their own error UI.
 *
 * Dependency injection is manual (PROJECT.md §7.1): this class owns the
 * process-wide singletons and exposes them through [container].
 */
class GhostifyApplication : Application() {

    /** Process-wide composition root, built lazily on first access. */
    val container: GhostifyContainer by lazy { GhostifyContainer(this) }

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val crashMarker = PrefsCrashMarker(this)
        startPythonRuntime()
        StartupBootstrap(
            crashHandler = GhostifyCrashHandler(FileLogCrashReporter(this, crashMarker)),
            recovery = KilledProcessRecovery(AppDatabase.get(this).recoveryDao()),
            recoveryGate = RecoveryGate(),
            scope = scope,
        ).onColdStart()
        crashMarker.markCleanStartup()
    }

    private fun startPythonRuntime() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            val ffmpegDir = FfmpegLocator.ensureExecutable(this)
            Python.getInstance()
                .getModule("ghostify_dl")
                .callAttr("prepend_path", ffmpegDir.absolutePath)
            Log.i(TAG, "Python + ffmpeg ready")
        } catch (t: Throwable) {
            Log.e(TAG, "Python runtime failed to start; running in degraded mode.", t)
        }
    }

    private companion object {
        const val TAG = "GhostifyApp"
    }
}

/** Placeholder for the app-level composition root; populated with the UI shell. */
class GhostifyContainer(private val context: Application) {
    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val crashMarker: CrashMarker by lazy { PrefsCrashMarker(context) }
    val recoveryDao: RoomRecoveryDao by lazy { database.recoveryDao() }
}
