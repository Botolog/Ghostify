package com.ghostify.python

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * T-028 — a crashing interpreter must never take down the app process.
 *
 * `crash_probe` calls `os.abort()` inside the `:pythond` process. The test
 * asserts that:
 * - the crash surfaces as a typed [PythonError] (crash/unavailable), never a
 *   raw crash of the test process,
 * - the app (test) process pid is unchanged — this is what process isolation
 *   buys us, and it is the only honest way to satisfy "no process death",
 * - the host transparently restarts for the next call.
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeCrashTest : PythonRuntimeInstrumentedTest() {

    @Test
    fun t028_interpreterCrashDoesNotKillTheApp() = runBlocking {
        val pidBefore = Process.myPid()

        val error = try {
            PythonRuntime.call("ghostify_dl", "crash_probe")
            null
        } catch (e: PythonError) {
            e
        }
        assertNotNull("crash_probe must surface a typed error, not a success", error)
        assertTrue(
            "crash must be reported as InterpreterCrashed or InterpreterUnavailable, got $error",
            error is PythonError.InterpreterCrashed || error is PythonError.InterpreterUnavailable,
        )

        assertEquals("app process must survive the interpreter crash", pidBefore, Process.myPid())

        val boot = PythonRuntime.boot()
        assertTrue("host must recover after the crash", boot.interpreterId.isNotBlank())
    }
}
