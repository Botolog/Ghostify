package com.ghostify.release

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * T-176 (automated slice) — no hardcoded user-visible strings.
 *
 * Runs in plain CI (no device, no Android framework). Scans the module's
 * src/main for the two ways a hardcoded string sneaks into user-facing text:
 *  1. Compose calls that take a string literal: Text("..."), text = "...",
 *     contentDescription = "...", label = "...", placeholder = "...".
 *  2. XML attributes that must always be resource references:
 *     android:label/android:text/android:hint/android:contentDescription.
 *
 * This is a pragmatic tripwire; Android Lint's HardcodedText (run by
 * `lintRelease` in CI) remains the authoritative check and also covers
 * androidTest/unit-test sources, which this deliberately does not (tests may
 * use literals). Every flagged literal must move to res/values/strings.xml.
 */
class LocalizationCheckTest {

    @Test
    fun composeAndXmlHaveNoHardcodedUserVisibleStrings() {
        val violations = mutableListOf<String>()
        scanSourceFiles { path, line, sample ->
            violations += "$path:$line  $sample"
        }
        assertTrue(
            "Hardcoded user-visible strings found — move them to res/values/strings.xml:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun scanSourceFiles(report: (path: String, line: Int, sample: String) -> Unit) {
        val main = locateMainSourceDir()
        Files.walk(main).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val name = file.fileName.toString()
                when {
                    name.endsWith(".kt") || name.endsWith(".java") ->
                        scanLines(file) { lineNo, line -> KOTLIN_HARDCODED.findAll(line).forEach { report(file.toString(), lineNo, it.value.trim()) } }
                    name.endsWith(".xml") ->
                        scanLines(file) { lineNo, line -> XML_HARDCODED.findAll(line).forEach { report(file.toString(), lineNo, it.value.trim()) } }
                }
            }
        }
    }

    private fun scanLines(file: Path, report: (lineNo: Int, line: String) -> Unit) {
        Files.readAllLines(file).forEachIndexed { index, line ->
            report(index + 1, line)
        }
    }

    /**
     * Locates the module's src/main directory. Unit tests run with the working
     * directory set to the Gradle module dir; an explicit override is honoured
     * for unusual CI layouts.
     */
    private fun locateMainSourceDir(): Path {
        val cwd = File(".").absoluteFile
        val direct = cwd.resolve("src/main")
        if (direct.isDirectory) return direct.toPath()
        System.getProperty("ghostify.srcRoot")?.let { root ->
            val fromRoot = File(root).resolve("src/main")
            if (fromRoot.isDirectory) return fromRoot.toPath()
        }
        error(
            "Could not locate src/main (looked in ${cwd.path} and -Dghostify.srcRoot). " +
                "Run from the app module or pass the module path via -Dghostify.srcRoot=<app module>.",
        )
    }

    private companion object {
        /**
         * Matches string literals passed as user-visible text to Compose.
         * Refuses `"`-then-`@` (a malformed resource ref) and `"`-then-`$`
         * alone is still flagged (interpolated literal). Empty strings are not
         * flagged ("" is layout noise, not user-visible text).
         */
        val KOTLIN_HARDCODED = Regex(
            """(Text\(\s*(?:text\s*=\s*)?"[^"@]|""" +
                """text\s*=\s*"[^"@]|""" +
                """contentDescription\s*=\s*"[^"@]|""" +
                """label\s*=\s*"[^"@]|""" +
                """placeholder\s*=\s*"[^"@])""",
        )

        /** XML attributes that must always reference @string resources. */
        val XML_HARDCODED = Regex(
            """android:(label|text|hint|contentDescription|summary)="[^@/]""",
        )
    }
}
