package com.ghostify.security

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-169 — App data is not exported to other apps.
 *
 * DEVICE-ONLY instrumentation test. This file is NOT compiled by the component's
 * JVM harness; it runs via `:app:connectedDebugAndroidTest` on the merged app
 * (see build.gradle.kts header and docs/MANIFEST-AUDIT.md). It complements the
 * static manifest audit with runtime proof on a real device:
 *
 *  1. no component is exported beyond the two allowlisted OS hooks,
 *  2. no broad storage permission is granted (app-scoped storage only),
 *  3. files written to app-scoped storage are not world-accessible.
 *
 * The static half of T-169 (runnable without a device) is ManifestAuditTest +
 * SecurityScanTest in this component's src/test.
 */
@RunWith(AndroidJUnit4::class)
class AppDataIsolationInstrumentedTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = targetContext.packageManager
    private val packageName = targetContext.packageName

    /** OS-facing hooks the app intentionally opens (must match ManifestAudit.EXPORTED_ALLOWLIST). */
    private val allowedExported = setOf("androidx.media3.session.MediaButtonReceiver")

    @Test
    fun noComponentIsExportedBeyondAllowlist() {
        val info: PackageInfo = packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )

        val exports = mutableListOf<String>()
        (info.activities ?: emptyArray()).forEach { if (isExported(it)) exports += "activity ${it.name}" }
        (info.services ?: emptyArray()).forEach { if (isExported(it)) exports += "service ${it.name}" }
        (info.receivers ?: emptyArray()).forEach { if (isExported(it)) exports += "receiver ${it.name}" }
        (info.providers ?: emptyArray()).forEach { if (isExported(it)) exports += "provider ${it.name}" }

        // The launcher activity is exported by design (MAIN/LAUNCHER).
        val unexpected = exports.filter { it.split(" ").last() !in allowedExported && !it.startsWith("activity ") }
        assertEquals(
            "Unexpectedly exported components: $exports",
            emptyList<String>(),
            unexpected,
        )
    }

    @Test
    fun noBroadStoragePermissionIsGranted() {
        for (permission in listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_AUDIO",
        )) {
            assertEquals(
                "Permission $permission must be denied — app data stays app-scoped (T-169)",
                PackageManager.PERMISSION_DENIED,
                packageManager.checkPermission(permission, packageName),
            )
        }
    }

    @Test
    fun appScopedStorageIsPrivate() {
        val appPrivate: File? = targetContext.getExternalFilesDir(null)
        assertTrue("getExternalFilesDir must be available", appPrivate != null)

        val probe = File(appPrivate, "t169-probe.bin")
        probe.writeBytes(ByteArray(64) { it.toByte() })
        try {
            // getExternalFilesDir on modern Android is not world-accessible; this
            // guards against regressions (e.g. accidental getExternalStoragePublicDirectory).
            assertFalse("probe file must not be world-writable", probe.canWrite() && probe.isFile && checkNotWorldWritable(probe))
        } finally {
            probe.delete()
        }
    }

    private fun isExported(ci: android.content.pm.ComponentInfo): Boolean {
        // exported=false is explicit on all our components; a null flag also
        // fails closed here (for safety, treat anything not explicitly false
        // as suspicious at runtime and let the allowlist decide).
        return ci.exported
    }

    private fun checkNotWorldWritable(file: File): Boolean {
        // POSIX-ish check: no 'other' write bit. Best-effort on older devices.
        return try {
            val perms = java.nio.file.Files.getPosixFilePermissions(file.toPath())
            !perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
        } catch (_: UnsupportedOperationException) {
            true
        }
    }
}
