package com.ghostify.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Static release-readiness gates, runnable in plain CI (no device).
 *
 * Verifies the pieces of this component that must not drift: the manifest's
 * permission/service surface (prerequisite for T-173/T-181), the release-safe
 * application flags (RTL/cleartext/backup/locale), the dark-theme resource
 * (T-175), and the presence of the Chaquopy R8 keeps (T-180).
 */
class ReleaseContractTest {

    private val moduleRoot: File = locateModuleRoot()

    @Test
    fun manifestDeclaresReleaseCriticalPermissions() {
        val doc = parseXml(File(moduleRoot, "src/main/AndroidManifest.xml"))
        val declared = doc.getElementsByTagName("uses-permission")
            .toList()
            .mapNotNull { it.attributes.getNamedItem("android:name")?.nodeValue }
            .toSet()
        for (permission in listOf(
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.WAKE_LOCK",
        )) {
            assertTrue("manifest missing required permission: $permission", permission in declared)
        }
    }

    @Test
    fun applicationFlagsAreReleaseSafe() {
        val app = manifestApplication()
        assertEquals("android:supportsRtl must be true (T-176 RTL)", "true", app.attr("android:supportsRtl"))
        assertEquals("android:usesCleartextTraffic must be false (T-171)", "false", app.attr("android:usesCleartextTraffic"))
        assertNotNull("android:dataExtractionRules required (API 31+ backup)", app.attr("android:dataExtractionRules"))
        assertNotNull("android:fullBackupContent required (API < 31 backup)", app.attr("android:fullBackupContent"))
        assertNotNull("android:localeConfig required (per-app language)", app.attr("android:localeConfig"))
    }

    @Test
    fun launcherActivityIsExported() {
        val doc = parseXml(manifest())
        val activities = doc.getElementsByTagName("activity").toList().map { it as Element }
        val main = activities.firstOrNull { it.attr("android:name") == ".ui.MainActivity" }
        assertNotNull("MainActivity must be declared", main)
        assertEquals("launcher activity must be exported=true", "true", main!!.attr("android:exported"))
        val hasLauncherIntent = main!!.getElementsByTagName("intent-filter")
            .toList()
            .map { it as Element }
            .any { filter ->
                val actions = filter.getElementsByTagName("action").toList().map { (it as Element).attr("android:name") }
                val categories = filter.getElementsByTagName("category").toList().map { (it as Element).attr("android:name") }
                "android.intent.action.MAIN" in actions && "android.intent.category.LAUNCHER" in categories
            }
        assertTrue("MainActivity must have MAIN/LAUNCHER intent filter", hasLauncherIntent)
    }

    @Test
    fun playbackServiceDeclaresForegroundType() {
        val doc = parseXml(manifest())
        val service = doc.getElementsByTagName("service").toList()
            .map { it as Element }
            .firstOrNull { it.attr("android:name") == ".player.MediaSessionService" }
        assertNotNull("MediaSessionService must be declared", service)
        val fgType = service!!.attr("android:foregroundServiceType")
        assertTrue("foregroundServiceType must include mediaPlayback, was: $fgType", fgType!!.split(',').any { it.trim() == "mediaPlayback" })
    }

    @Test
    fun proguardRulesKeepChaquopyBridge() {
        val rules = listOf("proguard-rules.pro", "proguard-chaquopy.pro")
            .associateWith { File(moduleRoot, "src/main/$it").also { f ->
                assertTrue("missing ${f.path}", f.exists())
            }.readText() }
        // The app build wires BOTH files into the release proguardFiles list, so
        // the effective keep-set is the union. Assert the union covers the two
        // reflection targets R8 must not strip...
        val union = rules.values.joinToString("\n")
        assertTrue("release rules must keep the Chaquopy interpreter", union.contains("com.chaquo.python"))
        assertTrue("release rules must keep the Python bridge package", union.contains("com.ghostify.python"))
        // ...and that the Chaquopy-specific rules live in the dedicated file
        // (proguard-rules.pro deliberately delegates to it — see its header).
        assertTrue("proguard-chaquopy.pro must keep the interpreter", rules["proguard-chaquopy.pro"]!!.contains("com.chaquo.python"))
        assertTrue("proguard-rules.pro must keep the Python bridge package", rules["proguard-rules.pro"]!!.contains("com.ghostify.python"))
    }

    @Test
    fun darkThemeResourcesExist() {
        assertTrue(File(moduleRoot, "src/main/res/values-night/themes.xml").exists())
        assertTrue(File(moduleRoot, "src/main/res/values/themes.xml").exists())
        assertTrue(File(moduleRoot, "src/main/res/values/strings.xml").exists())
    }

    // --- helpers ------------------------------------------------------------

    private fun manifest() = File(moduleRoot, "src/main/AndroidManifest.xml")

    private fun manifestApplication(): Element {
        val doc = parseXml(manifest())
        return doc.getElementsByTagName("application").item(0) as Element
    }

    private fun parseXml(file: File): Document {
        assertTrue("missing $file", file.exists())
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isNamespaceAware = false
        }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun Element.attr(name: String): String? = getAttribute(name).ifEmpty { null }

    private fun locateModuleRoot(): File {
        val cwd = File(".").absoluteFile
        if (File(cwd, "src/main").isDirectory) return cwd
        System.getProperty("ghostify.srcRoot")?.let { root ->
            val dir = File(root)
            if (File(dir, "src/main").isDirectory) return dir
        }
        error("Could not locate module root; run from the app module or pass -Dghostify.srcRoot=<app module>.")
    }
}

private fun org.w3c.dom.NodeList.toList(): List<org.w3c.dom.Node> = (0 until length).map { item(it) }
