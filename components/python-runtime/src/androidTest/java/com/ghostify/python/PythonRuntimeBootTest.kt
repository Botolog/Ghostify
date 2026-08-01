package com.ghostify.python

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * T-022 — interpreter boots on the current ABI.
 * T-023 — cold-start load time < 10 s.
 * T-024 — second call reuses the interpreter (no reload).
 *
 * The three ABIs (arm64-v8a, armeabi-v7a, x86_64) are covered by running this
 * suite on one device/emulator per ABI; the build config (ndk.abiFilters) is
 * what makes all three ship in the APK.
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeBootTest : PythonRuntimeInstrumentedTest() {

    @Test
    fun t022_interpreterBootsOnThisAbi() = runBlocking {
        val boot = PythonRuntime.boot()
        assertTrue("boot_probe must return a result map", boot.probe.isNotEmpty())
        assertTrue("python version must be reported", boot.pythonVersion.isNotBlank())
        assertEquals("host abi must match the device abi", Build.SUPPORTED_ABIS.first(), boot.abi)
    }

    @Test
    fun t023_coldStartLoadTimeUnder10Seconds() = runBlocking {
        PythonRuntime.reset() // force a true cold start: stop the host, start fresh
        val startedAt = SystemClock.elapsedRealtime()
        val boot = PythonRuntime.boot()
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        assertTrue("cold start took ${elapsed} ms (limit 10000 ms)", elapsed < 10_000L)
        assertTrue("interpreter id must be present", boot.interpreterId.isNotBlank())
    }

    @Test
    fun t024_secondCallReusesInterpreter() = runBlocking {
        val first = PythonRuntime.boot()
        val second = PythonRuntime.boot()
        assertEquals("second call must reuse the same interpreter", first.interpreterId, second.interpreterId)
        assertTrue("host uptime must grow between calls", second.hostUptimeMs >= first.hostUptimeMs)
    }
}
