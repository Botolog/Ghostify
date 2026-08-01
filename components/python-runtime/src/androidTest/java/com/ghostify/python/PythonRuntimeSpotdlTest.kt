package com.ghostify.python

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/**
 * T-026 — the spotdl module imports without error on device. Depends on the
 * pip configuration in `build-config/app-build.gradle.kts` resolving spotdl's
 * dependency tree (validated in the Phase-0 spike, PROJECT.md §9).
 */
@RunWith(AndroidJUnit4::class)
class PythonRuntimeSpotdlTest : PythonRuntimeInstrumentedTest() {

    @Test
    fun t026_spotdlModuleImports() = runBlocking {
        val result = PythonRuntime.call("ghostify_dl", "import_spotdl")
        val map = result as Map<*, *>
        assertEquals("spotdl", map["module"])
        val version = map["version"] as? String
        assertTrue("spotdl version must be reported, got '$version'", !version.isNullOrBlank())
    }
}
