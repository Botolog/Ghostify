package com.ghostify.security

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-169 (static portion) — no exported data. Two halves:
 *
 *  1. `repoManifestsAreClean`: audit every AndroidManifest.xml in the repo
 *     (the shipped app + the per-component manifests) for export surface.
 *  2. The checker itself is proven on hand-written good/bad manifests so a
 *     passing run means something (not a vacuous pass).
 *
 * The runtime half of T-169 (device, PackageManager) is
 * `AppDataIsolationInstrumentedTest` under src/androidTest.
 */
class ManifestAuditTest {

    @Test
    fun repoManifestsAreClean() {
        val repo = locateRepoRoot()
        val manifests = repo.walkTopDown()
            .onEnter { it == repo || it.name !in setOf(".git", ".gradle", "build", ".kotlin") }
            .filter { it.isFile && it.name == "AndroidManifest.xml" }
            .toList()
        assertTrue(manifests.isNotEmpty(), "no AndroidManifest.xml found to audit")
        for (m in manifests) {
            val issues = ManifestAudit.auditFile(m)
            assertTrue(
                issues.isEmpty(),
                "manifest ${m.path} has security issues:\n" + issues.joinToString("\n") { "  ${it.rule}: ${it.message}" },
            )
        }
    }

    @Test
    fun flagsExportedComponentsWithoutAllowlist() {
        val bad = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <service android:name=".LeakyService" android:exported="true" />
              </application>
            </manifest>
        """.trimIndent()
        val issues = ManifestAudit.audit(bad)
        assertTrue(issues.any { it.rule == ManifestAudit.R_EXPORTED_COMPONENT })
    }

    @Test
    fun allowsLauncherAndMediaButtonReceiver() {
        val ok = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application android:usesCleartextTraffic="false">
                <activity android:name=".ui.MainActivity" android:exported="true">
                  <intent-filter>
                    <action android:name="android.intent.action.MAIN" />
                    <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
                </activity>
                <service android:name=".player.MediaSessionService" android:exported="false" />
                <receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true" />
              </application>
            </manifest>
        """.trimIndent()
        val issues = ManifestAudit.audit(ok)
        assertEquals(emptyList<ManifestAudit.Issue>(), issues)
    }

    @Test
    fun flagsBroadStoragePermissions() {
        val bad = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
              <application android:usesCleartextTraffic="false" />
            </manifest>
        """.trimIndent()
        val issues = ManifestAudit.audit(bad)
        assertTrue(issues.any { it.rule == ManifestAudit.R_STORAGE_PERMISSION })
    }

    @Test
    fun flagsCleartextAndDebuggable() {
        val bad = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application android:usesCleartextTraffic="true" android:debuggable="true" />
            </manifest>
        """.trimIndent()
        val issues = ManifestAudit.audit(bad)
        assertTrue(issues.any { it.rule == ManifestAudit.R_CLEARTEXT_MANIFEST })
        assertTrue(issues.any { it.rule == ManifestAudit.R_DEBUGGABLE })
    }

    @Test
    fun rejectsExportedProvider() {
        val bad = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <provider android:name=".DataProvider" android:exported="true" android:authorities="com.ghostify.data" />
              </application>
            </manifest>
        """.trimIndent()
        val issues = ManifestAudit.audit(bad)
        assertTrue(issues.any { it.rule == ManifestAudit.R_PROVIDER })
    }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "PROJECT.md").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root")
    }
}
