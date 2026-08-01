package com.ghostify.python

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * T-025 — `ffmpeg` is found on PATH (inside the interpreter) and executes a
 * trivial conversion to MP3.
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeFfmpegTest : PythonRuntimeInstrumentedTest() {

    @Test
    fun t025_ffmpegOnPathAndConverts() = runBlocking {
        val result = PythonRuntime.call("ghostify_dl", "ffmpeg_probe")
        val map = result as Map<*, *>
        assertEquals("ffmpeg must have converted the probe input", true, map["converted"])
        val bytes = (map["bytes"] as? Number)?.toLong() ?: 0L
        assertTrue("converted file must be non-empty, got $bytes bytes", bytes > 0L)
        assertTrue("ffmpeg must be found on PATH", !(map["ffmpeg"] as? String).isNullOrBlank())
    }
}
