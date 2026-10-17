package xyz.botolog.ghostify.debug

import android.os.Build
import android.os.StrictMode
import android.util.Log
import timber.log.Timber
import java.util.concurrent.Executor
import xyz.botolog.ghostify.BuildConfig

/**
 * Debug-only StrictMode installation.
 *
 * Enables the full `detectAll()` thread and VM policies with *log-only* penalties:
 * violations are written through [Timber] (so they reach `filesDir/logs/`) and to
 * logcat. `penaltyDialog()`/`penaltyDeath()` are deliberately never used so debug
 * builds can't be crashed or blocked by a policy hit.
 *
 * Release safety: [installIfDebug] is a no-op unless `BuildConfig.DEBUG` is true.
 */
object StrictModeSetup {

    private const val TAG = "StrictModeSetup"

    /**
     * Violation callbacks run on this executor. Logging is cheap and non-blocking
     * ([xyz.botolog.ghostify.logging.FileLoggingTree] queues to its own thread), so a
     * direct executor is fine and keeps the report on the offending thread.
     */
    private val directExecutor = Executor { command -> command.run() }

    /** Installs StrictMode in debug builds only. */
    fun installIfDebug() {
        if (!BuildConfig.DEBUG) return
        try {
            StrictMode.setThreadPolicy(threadPolicy())
            StrictMode.setVmPolicy(vmPolicy())
            Timber.tag(TAG).i("StrictMode enabled (detectAll, log-only)")
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "StrictMode setup failed")
        }
    }

    private fun threadPolicy(): StrictMode.ThreadPolicy {
        val builder = StrictMode.ThreadPolicy.Builder()
            .detectAll()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.penaltyListener(directExecutor) { violation ->
                Timber.tag(TAG).w(violation, "StrictMode thread violation")
                Log.w(TAG, "StrictMode thread violation", violation)
            }
        }
        return builder.build()
    }

    private fun vmPolicy(): StrictMode.VmPolicy {
        val builder = StrictMode.VmPolicy.Builder()
            .detectAll()
            .penaltyLog()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.penaltyListener(directExecutor) { violation ->
                Timber.tag(TAG).w(violation, "StrictMode VM violation")
                Log.w(TAG, "StrictMode VM violation", violation)
            }
        }
        return builder.build()
    }
}
