package com.ghostify

import android.app.Application
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point, owned by this (release) component.
 *
 * It is the single process-wide init point for:
 *  1. Hilt (the DI component extends this class's lifecycle — it is the
 *     `@HiltAndroidApp` class so exactly one Application exists).
 *  2. The Chaquopy interpreter, started eagerly because PROJECT.md guarantees
 *     Python is always used (metadata fetch, downloads). Starting it here means
 *     the whole app — Library browsing included — never blocks on a lazy first
 *     interpreter boot later.
 *
 * Python startup is wrapped in a fail-closed-but-degraded handler: if the
 * interpreter cannot boot (e.g. an old device, a tampered APK, a Chaquopy
 * packaging defect), the app still opens in a "library only" degraded mode
 * instead of crashing on every launch. Python-backed features then surface
 * their own error UI.
 */
@HiltAndroidApp
class GhostifyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startPythonRuntime()
    }

    private fun startPythonRuntime() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Python runtime failed to start; running in degraded mode.", t)
        }
    }

    private companion object {
        const val TAG = "GhostifyApp"
    }
}
