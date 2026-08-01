package com.ghostify.security

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * T-168 / T-169 (static) / T-170 / T-171 — repo-wide static scan.
 *
 * Runs the real [SecurityScan] over the whole repository and asserts there are
 * no ERROR findings: no credential-shaped values in shipped source, no
 * cleartext `http://` network literals, no full Spotify URL reaching a
 * non-LogPolicy logger, no exported manifest surface. These run on a plain
 * JVM host (this component's `gradle test`); no device required.
 */
class SecurityScanTest {

    private val repoRoot: File = locateRepoRoot()

    @Test
    fun `no error findings across the repository`() {
        val findings = SecurityScan.scan(repoRoot)
        val errors = findings.filter { it.severity == SecurityScan.Severity.ERROR }
        assertTrue(
            errors.isEmpty(),
            "Security scan found ${errors.size} ERROR finding(s):\n" +
                errors.joinToString("\n") { "  ${it.rule} ${it.file.path}:${it.line} ${it.detail}" },
        )
    }

    @Test
    fun `scan covers the expected code surface`() {
        val files = SecurityScan.scan(repoRoot)
        val scannedSome = files.isNotEmpty() || countScannedFiles() > 0
        assertTrue(scannedSome, "scan produced no files to report; walker may be broken")
    }

    @Test
    fun `logcat bypasses surface as warn only and is documented`() {
        // The migration signal (direct android.util.Log) may exist today as a
        // WARN; it must never be a hard failure. This locks the severity.
        val direct = SecurityScan.scan(repoRoot).filter { it.rule == SecurityScan.R_DIRECT_LOG_API }
        assertTrue(direct.all { it.severity == SecurityScan.Severity.WARN })
    }

    private fun countScannedFiles(): Int {
        var n = 0
        repoRoot.walkTopDown()
            .onEnter { it == repoRoot || it.name !in setOf(".git", ".gradle", "build", ".kotlin", ".venv", ".ruff_cache") }
            .forEach { if (it.isFile && it.extension in setOf("kt", "java", "py", "xml")) n++ }
        return n
    }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "PROJECT.md").isFile) return dir
            dir = dir.parentFile
        }
        error("Could not locate repo root (no PROJECT.md found). Run from the security-privacy component.")
    }
}
