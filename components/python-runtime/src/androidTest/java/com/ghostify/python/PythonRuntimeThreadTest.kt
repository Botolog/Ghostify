package com.ghostify.python

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * T-027 — Python calls never run on the main thread, verified two ways:
 *
 * 1. StrictMode: with network/disk-write detection on the main thread, a
 *    bridge that wrongly did work on the main thread would log violations
 *    (the real app enables penaltyDeath; here penaltyLog keeps the test suite
 *    deterministic).
 * 2. Thread identity: the client dispatches on a dedicated "python-bridge"
 *    thread, and the interpreter executes on a background "python-host" thread
 *    inside the separate `:pythond` process — neither can be the app's main
 *    thread, which Python reports as "MainThread".
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeThreadTest : PythonRuntimeInstrumentedTest() {

    @Test
    fun t027_bridgeNeverRunsOnTheMainThread() = runBlocking {
        val originalPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(originalPolicy)
                .detectNetwork()
                .detectDiskWrites()
                .penaltyLog()
                .build(),
        )
        try {
            repeat(5) { PythonRuntime.call("ghostify_dl", "boot_probe") }
        } finally {
            StrictMode.setThreadPolicy(originalPolicy)
        }

        assertNotEquals(
            "bridge dispatch thread must not be the main thread",
            "main",
            PythonRuntime.dispatchThreadName(),
        )

        val probe = PythonRuntime.call("ghostify_dl", "boot_probe") as Map<*, *>
        assertNotEquals(
            "python execution thread must not be the process main thread",
            "MainThread",
            probe["thread"],
        )
    }
}
