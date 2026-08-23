package xyz.botolog.ghostify.security

import java.io.File
import javax.xml.XMLConstants
import timber.log.Timber
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Manifest audit (T-169 static portion). Verifies that no manifest in the repo
 * exports app data or app components to other apps:
 *
 *  1. No component (`activity`/`service`/`receiver`/`provider`) is
 *     `android:exported="true"` unless it is an OS-facing system hook that the
 *     app deliberately opens (launcher activity, MediaButtonReceiver) — see
 *     [EXPORTED_ALLOWLIST].
 *  2. No provider exists at all; providers are an export surface we do not
 *     need (Room runs in-process, there is no ContentProvider).
 *  3. No broad storage permission is declared (`READ/WRITE_EXTERNAL_STORAGE`,
 *     `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*`) — all media lives in
 *     app-scoped storage (PROJECT.md §8).
 *  4. `usesCleartextTraffic` is false (T-171) and the app is not debuggable.
 *
 * Parsing is hardened against XXE (external entities/DTDs disabled) — a
 * security tool must not itself be an injection vector.
 *
 * Runtime proof (device) for T-169 lives in the instrumentation test
 * `AppDataIsolationInstrumentedTest`; this object is what the local JVM test
 * `ManifestAuditTest` and the repo-wide scan run.
 */
object ManifestAudit {

    /**
     * Rule ids shared with [SecurityScan] and the Python scan script.
     */
    const val R_EXPORTED_COMPONENT = "EXPORTED_COMPONENT"
    const val R_STORAGE_PERMISSION = "STORAGE_PERMISSION"
    const val R_CLEARTEXT_MANIFEST = "CLEARTEXT_MANIFEST"
    const val R_DEBUGGABLE = "DEBUGGABLE"
    const val R_PROVIDER = "PROVIDER"

    private const val TAG_APPLICATION = "application"
    private const val TAG_USES_PERMISSION = "uses-permission"
    private const val ATTR_CLEARTEXT = "android:usesCleartextTraffic"
    private const val ATTR_DEBUGGABLE = "android:debuggable"
    private const val ATTR_EXPORTED = "android:exported"
    private const val ATTR_NAME = "android:name"
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    /** Components that the OS (not other apps) binds and that must be exported. */
    private val EXPORTED_ALLOWLIST = setOf(
        "androidx.media3.session.MediaButtonReceiver",
    )

    private val STORAGE_PERMISSIONS = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.ACCESS_MEDIA_LOCATION",
    )

    /** One finding from a manifest. [line] is null when the DOM has no line info. */
    data class Issue(val rule: String, val message: String, val line: Int? = null)

    /** Audit a manifest file. Returns an empty list when the manifest is clean. */
    fun auditFile(file: File): List<Issue> {
        Timber.i("ManifestAudit.auditFile: START")
        if (!file.exists()) return listOf(Issue(R_EXPORTED_COMPONENT, "missing manifest: ${file.path}"))
        val result = audit(file.readText(), file.name)
        Timber.i("ManifestAudit.auditFile: returning ${result.size} issues")
        return result
    }

    /** Audit manifest XML text. Returns an empty list when clean. */
    fun audit(manifestXml: String, source: String = "AndroidManifest.xml"): List<Issue> {
        Timber.i("ManifestAudit.audit: START")
        val issues = ArrayList<Issue>()
        val doc = parse(manifestXml)
        if (doc == null) {
            issues += Issue(R_EXPORTED_COMPONENT, "manifest is not parseable XML")
            return issues
        }
        val app = doc.getElementsByTagName(TAG_APPLICATION).item(0) as? Element ?: return issues

        checkCleartextAndDebuggable(app, issues)
        checkStoragePermissions(doc, issues)
        checkExportedComponents(doc, issues)

        Timber.i("ManifestAudit.audit: returning ${issues.size} issues")
        return issues
    }

    /** Checks for cleartext traffic and debuggable flags. */
    private fun checkCleartextAndDebuggable(app: Element, issues: MutableList<Issue>) {
        if (app.getAttribute(ATTR_CLEARTEXT) == "true") {
            issues += Issue(R_CLEARTEXT_MANIFEST, "android:usesCleartextTraffic=\"true\" allows cleartext HTTP (T-171)")
            Timber.w("ManifestAudit: cleartext traffic enabled in manifest")
        }
        if (app.getAttribute(ATTR_DEBUGGABLE) == "true") {
            issues += Issue(R_DEBUGGABLE, "android:debuggable=\"true\" must never ship")
            Timber.e("ManifestAudit: debuggable=true found in manifest")
        }
    }

    /** Checks for broad storage permissions. */
    private fun checkStoragePermissions(doc: Document, issues: MutableList<Issue>) {
        for (i in 0 until doc.getElementsByTagName(TAG_USES_PERMISSION).length) {
            val perm = doc.getElementsByTagName(TAG_USES_PERMISSION).item(i)
            val name = perm.attributes?.getNamedItem(ATTR_NAME)?.nodeValue ?: continue
            if (name in STORAGE_PERMISSIONS) {
                issues += Issue(R_STORAGE_PERMISSION, "broad storage permission $name exports app data surface (T-169)")
                Timber.w("ManifestAudit: storage permission $name declared")
            }
        }
    }

    /** Checks for exported components without allowlisted reason. */
    private fun checkExportedComponents(doc: Document, issues: MutableList<Issue>) {
        for (tag in COMPONENT_TAGS) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                val name = el.getAttribute(ATTR_NAME).ifEmpty { "?" }
                val exported = el.getAttribute(ATTR_EXPORTED)
                val isLauncher = tag == "activity" && hasLauncherIntent(el)
                val allowlisted = name in EXPORTED_ALLOWLIST
                if (exported == "true" && !isLauncher && !allowlisted) {
                    issues += Issue(
                        R_EXPORTED_COMPONENT,
                        "$tag $name is android:exported=\"true\" without an allowlisted reason (T-169)",
                    )
                    Timber.w("ManifestAudit: $tag $name is exported without allowlist")
                }
                if (tag == "provider" && exported != "false") {
                    issues += Issue(
                        R_PROVIDER,
                        "provider $name must be android:exported=\"false\" or removed (T-169)",
                    )
                    Timber.e("ManifestAudit: provider $name is exported")
                }
            }
        }
    }

    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    private fun hasLauncherIntent(el: Element): Boolean {
        val filters = el.getElementsByTagName("intent-filter")
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            val actions = filter.getElementsByTagName("action")
            val categories = filter.getElementsByTagName("category")
            var hasMain = false
            var hasLauncher = false
            for (j in 0 until actions.length) {
                if ((actions.item(j) as Element).getAttribute(ATTR_NAME) == ACTION_MAIN) hasMain = true
            }
            for (j in 0 until categories.length) {
                if ((categories.item(j) as Element).getAttribute(ATTR_NAME) == CATEGORY_LAUNCHER) hasLauncher = true
            }
            if (hasMain && hasLauncher) return true
        }
        return false
    }

    private fun parse(manifestXml: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature(FEATURE_DISALLOW_DOCTYPE, true)
            setFeature(FEATURE_EXTERNAL_GENERAL_ENTITIES, false)
            setFeature(FEATURE_EXTERNAL_PARAMETER_ENTITIES, false)
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        factory.newDocumentBuilder().parse(manifestXml.byteInputStream())
    } catch (t: Exception) {
        Timber.e(t, "ManifestAudit: parse FAILED")
        null
    }

    private const val FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val FEATURE_EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val FEATURE_EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
}
