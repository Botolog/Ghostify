package xyz.botolog.ghostify.update

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Downloads the latest GitHub release APK and verifies it is a valid,
 * installable APK file.
 *
 * Requires network. Run with: ./gradlew :app:testDebugUnitTest --tests "*.ApkValidationTest"
 */
class ApkValidationTest {

    companion object {
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/Botolog/Ghostify/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MIN_APK_SIZE_BYTES = 1_000_000L // 1 MB
        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK..
    }

    @Test
    fun latestReleaseApkIsValid() {
        val tmpFile = File.createTempFile("ghostify-release-test", ".apk")
        try {
            // 1. Fetch release info
            val (tagName, apkUrl) = fetchLatestRelease()

            // 2. Download APK
            downloadFile(apkUrl, tmpFile)

            // 3. Validate
            validateApkFile(tmpFile, tagName)
        } finally {
            tmpFile.delete()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun fetchLatestRelease(): Pair<String, String> {
        val conn = (URL(GITHUB_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "Ghostify-Android-Test")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        try {
            val code = conn.responseCode
            assertTrue("GitHub API returned HTTP $code", code == 200)

            val body = conn.inputStream.bufferedReader().use { it.readText() }

            val tagName = UpdateChecker.extractJsonString(body, "tag_name")
                ?: fail("Missing tag_name") as Nothing

            val assetsStart = body.indexOf("\"assets\"")
            assertTrue("No assets in release JSON", assetsStart != -1)

            val apkUrl = UpdateChecker.findApkUrl(body, assetsStart)
                ?: fail("No APK asset found in release $tagName") as Nothing

            return tagName to apkUrl
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFile(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        try {
            val code = conn.responseCode
            assertTrue("Download returned HTTP $code for $url", code == 200)

            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun validateApkFile(file: File, tagName: String) {
        // Size check
        val size = file.length()
        assertTrue(
            "APK too small: $size bytes (expected >= $MIN_APK_SIZE_BYTES) for $tagName",
            size >= MIN_APK_SIZE_BYTES,
        )

        // ZIP magic bytes check
        val header = file.inputStream().use { it.readNBytes(4) }
        assertTrue(
            "Not a ZIP/APK file — missing PK magic bytes. First bytes: ${header.joinToString { "%02X".format(it) }}",
            header.contentEquals(ZIP_MAGIC),
        )

        // Must be openable as a valid ZIP
        val zip = try {
            ZipFile(file)
        } catch (e: Exception) {
            fail("Cannot open as ZIP: ${e.message}")
            return // unreachable
        }

        try {
            val entries = zip.entries().asSequence().map { it.name }.toSet()

            assertTrue(
                "APK missing AndroidManifest.xml. Entries: ${entries.take(10)}",
                entries.contains("AndroidManifest.xml"),
            )

            assertTrue(
                "APK missing classes.dex. Entries: ${entries.take(10)}",
                entries.any { it.endsWith(".dex") },
            )

            // Must have at least one resource
            assertTrue(
                "APK has no resource entries",
                entries.any { it.startsWith("res/") },
            )
        } finally {
            zip.close()
        }
    }
}
