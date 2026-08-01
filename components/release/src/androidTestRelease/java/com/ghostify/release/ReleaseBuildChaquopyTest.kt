package com.ghostify.release

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ghostify.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-180 — the release build (R8/minify + resource shrink) does not break
 * Chaquopy reflection / spotdl imports.
 *
 * Device-only (instrumentation), and release-build-only: this class lives in
 * the `androidTestRelease` source set, so it is compiled and executed only for
 * the release variant (`./gradlew connectedReleaseAndroidTest`), where
 * minifyEnabled is true. That is the entire point — debug never runs it.
 *
 * What it proves, on a minified APK:
 *  - the interpreter boots and is Python 3.11 (kept for armeabi-v7a ABI
 *    support; drift here silently breaks 32-bit ARM devices),
 *  - the app's own bridge module (ghostify_dl.py) loads and round-trips a call
 *    through Chaquopy's Java↔Python reflection layer,
 *  - spotdl imports and exposes __version__ — the import that would fail if R8
 *    had stripped any Java-side symbol Chaquopy needs.
 *
 * Contract with the python-runtime component: `src/main/python/ghostify_dl.py`
 * must define `boot_probe()` (returns a dict incl. `python_version`),
 * `probe_serial(tag)` (echoes `tag` back in a dict) and `import_spotdl()`
 * (returns a dict with `version`), and spotdl must be bundled via the `pip`
 * block.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseBuildChaquopyTest {

    private lateinit var python: Python
    private lateinit var bridge: com.chaquo.python.PyObject

    @Before
    fun ensureMinifiedReleaseAndInterpreter() {
        assertFalse(
            "T-180 must run against the minified release variant, not debug",
            BuildConfig.DEBUG,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        python = Python.getInstance()
        bridge = python.getModule("ghostify_dl")
    }

    @Test
    fun interpreterBootsOnMinifiedRelease() {
        val probe = bridge.callAttr("boot_probe")
        val version = probe.get("python_version").toString()
        assertTrue("unexpected Python version in release APK: $version", version.startsWith("3.11"))
    }

    @Test
    fun bridgeModuleRoundTripsThroughReflectionLayer() {
        val reply = bridge.callAttr("probe_serial", "release-r8")
        val tag = reply.get("tag").toString()
        assertEquals("release-r8", tag)
    }

    @Test
    fun spotdlImportsOnMinifiedRelease() {
        val result = bridge.callAttr("import_spotdl")
        val version = result.get("version").toString()
        assertTrue("spotdl __version__ is blank", version.isNotBlank())
    }
}
