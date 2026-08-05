package com.ghostify.security

import java.io.File
import timber.log.Timber

/**
 * Static security scanner for the Ghostify repository. Walks the source tree
 * and reports violations of the security component's contracts. Used by:
 *
 *  - the local JVM test [com.ghostify.security.SecurityScanTest] — ERROR
 *    findings fail `./gradlew test` on a plain CI host,
 *  - the standalone CLI (`scripts/security-scan.sh` → `security_scan.py`),
 *  - the repo-wide manifest audit (T-169 static portion).
 *
 * Rules (stable ids, documented in docs/):
 *
 *  - [R_SECRET_IN_SOURCE]   (T-168) credential-shaped values anywhere in
 *    shipped source (reuses [SecretRedactor], the runtime's source of truth).
 *  - [R_STORED_SECRET_FIELD] (T-168) a Room entity / data class declaring a
 *    credential-named field — i.e. a secret that would be persisted.
 *  - [R_SPOTIFY_URL_IN_LOG] (T-170) a full Spotify URL reaching a logger other
 *    than [LogPolicy] (LogPolicy sanitizes at runtime; this catches bypasses).
 *  - [R_CLEARTEXT_HTTP]     (T-171) `http://` network literal in shipped code.
 *  - [R_CLEARTEXT_MANIFEST] (T-171) `usesCleartextTraffic="true"`.
 *  - [R_EXPORTED_COMPONENT], [R_STORAGE_PERMISSION], [R_DEBUGGABLE],
 *    [R_PROVIDER]           (T-169) manifest export surface.
 *  - [R_DIRECT_LOG_API]     (WARN) `android.util.Log` used directly instead of
 *    LogPolicy — tracked as a migration signal, not a hard failure.
 *
 * Severity: everything above is ERROR (fails CI) except [R_DIRECT_LOG_API]
 * which is WARN. Test/fixture trees are excluded from the shipped-code rules:
 * fixtures legitimately contain fake credentials, cleartext URLs and full
 * Spotify URLs to exercise the redaction/parsing code paths.
 */
object SecurityScan {

    // --- rule ids ---------------------------------------------------------
    const val R_SECRET_IN_SOURCE = "SECRET_IN_SOURCE"
    const val R_STORED_SECRET_FIELD = "STORED_SECRET_FIELD"
    const val R_SPOTIFY_URL_IN_LOG = "SPOTIFY_URL_IN_LOG"
    const val R_CLEARTEXT_HTTP = "CLEARTEXT_HTTP"
    const val R_DIRECT_LOG_API = "DIRECT_LOG_API"
    const val R_EXPORTED_COMPONENT = "EXPORTED_COMPONENT"
    const val R_STORAGE_PERMISSION = "STORAGE_PERMISSION"
    const val R_CLEARTEXT_MANIFEST = "CLEARTEXT_MANIFEST"
    const val R_DEBUGGABLE = "DEBUGGABLE"
    const val R_PROVIDER = "PROVIDER"

    enum class Severity { ERROR, WARN }

    /** A single scan finding. [line] is 1-based. */
    data class Finding(
        val rule: String,
        val file: File,
        val line: Int,
        val detail: String,
    ) {
        val severity: Severity get() = if (rule == R_DIRECT_LOG_API) Severity.WARN else Severity.ERROR
    }

    private val ERROR_RULES = setOf(
        R_SECRET_IN_SOURCE, R_STORED_SECRET_FIELD, R_SPOTIFY_URL_IN_LOG,
        R_CLEARTEXT_HTTP, R_EXPORTED_COMPONENT, R_STORAGE_PERMISSION,
        R_CLEARTEXT_MANIFEST, R_DEBUGGABLE, R_PROVIDER,
    )

    // --- repo walking -----------------------------------------------------
    private val SKIP_DIRS = setOf(
        ".git", ".gradle", "build", ".kotlin", ".venv", ".ruff_cache",
        "node_modules", "out", "captures", ".idea",
    )
    private val EXTENSIONS = setOf(
        "kt", "kts", "java", "py", "xml", "pro", "gradle", "properties",
        "toml", "yml", "yaml", "json", "md", "sh", "txt", "c", "cpp", "h",
    )
    private val TEST_PATH = Regex("""(^|[/\\])(src[/\\](test|androidTest|jvm-tests|instrumentedTest)|tests)[/\\]""")

    private fun isTestFile(file: File) = TEST_PATH.containsMatchIn(file.path.replace('\\', '/'))

    private fun walk(repoRoot: File): List<File> =
        repoRoot.walkTopDown()
            .onEnter { it == repoRoot || it.name !in SKIP_DIRS }
            .filter { it.isFile && it.extension in EXTENSIONS }
            .toList()

    // --- regexes ----------------------------------------------------------
    private val COMMENT_LINE = Regex("""^\s*(//|/\*|\*|#|<!--|;)""")
    private val HTTP_INSECURE = Regex("""(?i)\bhttp://""")
    private val HTTP_ALLOWED = Regex(
        """(?i)http://(?:schemas\.android\.com|www\.w3\.org|w3\.org|apache\.org|www\.apache\.org|xmlns\.com|www\.gnu\.org|gnu\.org|localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|www\.example\.com|example\.com)(?:/|"|'|\s|$)""",
    )
    private val FULL_SPOTIFY_URL = Regex("""(?i)https?://open\.spotify\.com/""")
    private val LOGCAT_CALL = Regex("""\b(?:android\.util\.)?Log\.(?:v|d|i|w|e|wtf)\(|(?:logger|logging)\.(?:v|d|i|w|e|log|info|debug|warning|error|warn)\(""")
    private val PRINT_SINK = Regex("""\b(?:System\.(?:out|err))\.(?:print|println)|(?i)\bprint(?:\s*\(|\s+[f"]|f")""")
    private val LOGPOLICY_CALL = Regex("""LogPolicy\.(?:log|v|d|i|w|e|debugTrackRef)\(""")
    private val DIRECT_LOG_API = Regex("""android\.util\.Log\b|(?:^|[^a-zA-Z0-9_.])Log\.(?:v|d|i|w|e|wtf)\(""")
    private val DATA_CLASS_MARKER = Regex("""(data class|@Entity)""")
    private val SECRET_FIELD = Regex(
        """(?i)\b(?:val|var)\s+(?:password|client_secret|clientSecret|client_id|clientId|api_key|apiKey|access_token|accessToken|refresh_token|refreshToken|auth_token|authToken|private_key|privateKey|secret)\s*(?::|=)""",
    )

    private val isSecurityComponent = { file: File ->
        file.path.replace('\\', '/').contains("/components/security-privacy/")
    }

    // --- public API -------------------------------------------------------
    /**
     * Scan the whole repository under [repoRoot]. Findings are sorted by
     * (path, line) for stable, reviewable output.
     */
    fun scan(repoRoot: File): List<Finding> {
        Timber.i("SecurityScan.scan: START")
        val findings = ArrayList<Finding>()
        for (file in walk(repoRoot)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            findings += scanFile(file, text)
        }
        val result = findings.sortedWith(compareBy({ it.file.path }, { it.line }, { it.rule }))
        Timber.i("SecurityScan.scan: returning ${result.size} findings")
        return result
    }

    /** Run from the CLI: `SecurityScan.main(<repo-root>)`. Exit 1 on ERROR. */
    @JvmStatic
    fun main(args: Array<String>) {
        Timber.i("SecurityScan.main: START")
        val root = File(args.firstOrNull() ?: locateRepoRoot())
        val findings = scan(root)
        for (f in findings) {
            println("${f.severity.name}\t${f.rule}\t${f.file.path}:${f.line}\t${f.detail}")
        }
        val errors = findings.count { it.severity == Severity.ERROR }
        println("security-scan: ${findings.size} finding(s), $errors error(s) across ${walk(root).size} file(s)")
        if (errors > 0) kotlin.system.exitProcess(1)
    }

    private fun scanFile(file: File, text: String): List<Finding> {
        val out = ArrayList<Finding>()
        val inTest = isTestFile(file)
        val isManifest = file.name == "AndroidManifest.xml"

        if (isManifest) {
            for (issue in ManifestAudit.auditFile(file)) {
                out += Finding(issue.rule, file, issue.line ?: 1, issue.message)
            }
        }

        // Secret-shaped values anywhere in shipped source (T-168).
        if (!inTest) {
            for (m in SecretRedactor.find(text)) {
                out += Finding(R_SECRET_IN_SOURCE, file, lineOf(text, m.start), "possible secret '${m.key}'")
            }
        }

        val lines = text.lines()
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            val lineNo = idx + 1
            if (line.isEmpty() || COMMENT_LINE.containsMatchIn(line)) continue
            if (inTest) continue

            if (!isSecurityComponent(file)) {
                // T-171: cleartext network literal in shipped code.
                if (HTTP_INSECURE.containsMatchIn(stripAllowed(line))) {
                    out += Finding(R_CLEARTEXT_HTTP, file, lineNo, "cleartext http:// URL; use https://")
                }
                // T-168: persisted secret field.
                if (file.extension == "kt" && DATA_CLASS_MARKER.containsMatchIn(text) && SECRET_FIELD.containsMatchIn(line)) {
                    out += Finding(R_STORED_SECRET_FIELD, file, lineNo, "credential-named field would be persisted: $line")
                }
                // T-170: full Spotify URL reaching a logger other than LogPolicy.
                if (FULL_SPOTIFY_URL.containsMatchIn(line) && !LOGPOLICY_CALL.containsMatchIn(line) &&
                    (LOGCAT_CALL.containsMatchIn(line) || PRINT_SINK.containsMatchIn(line))
                ) {
                    out += Finding(R_SPOTIFY_URL_IN_LOG, file, lineNo, "full Spotify URL passed to a logger; route through LogPolicy")
                }
                // WARN: direct android.util.Log instead of LogPolicy.
                if (DIRECT_LOG_API.containsMatchIn(line)) {
                    out += Finding(R_DIRECT_LOG_API, file, lineNo, "direct android.util.Log call; log through LogPolicy")
                }
            }
        }
        return out
    }

    private fun stripAllowed(line: String): String {
        var s = line
        while (true) {
            val n = s.replace(HTTP_ALLOWED, "")
            if (n == s) return s
            s = n
        }
    }

    private fun lineOf(text: String, charIndex: Int): Int = text.substring(0, charIndex).count { it == '\n' } + 1

    /** Find the repo root by walking up to PROJECT.md from CWD. */
    fun locateRepoRoot(): String {
        Timber.i("SecurityScan.locateRepoRoot: START")
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "PROJECT.md").isFile) {
                Timber.i("SecurityScan.locateRepoRoot: returning ${dir.path}")
                return dir.path
            }
            dir = dir.parentFile
        }
        Timber.i("SecurityScan.locateRepoRoot: returning .")
        return "."
    }
}
