package xyz.botolog.ghostify.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Holds information about an available GitHub release.
 *
 * @property versionName Display version string (e.g. `"1.2.3"`).
 * @property versionCode Numeric version code used to compare against the installed build.
 * @property releaseNotes Markdown body from the GitHub release.
 * @property apkDownloadUrl Direct download URL for the APK asset.
 * @property publishedAt ISO-8601 timestamp of the release.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val publishedAt: String,
)

/**
 * Tracks the state of an APK download.
 */
sealed class DownloadState {
    /** No download in progress. */
    data object Idle : DownloadState()

    /** Download is in progress. [progress] is a value in `0.0..1.0`. */
    data class Downloading(val progress: Float) : DownloadState()

    /** Download completed successfully. [file] is the local APK. */
    data class Downloaded(val file: File) : DownloadState()

    /** Download failed. [message] describes the error. */
    data class Error(val message: String) : DownloadState()
}

/**
 * Checks for GitHub releases and downloads / installs APK updates.
 *
 * The checker hits the GitHub Releases API, compares the latest release's version
 * code against the installed build, and can download the APK to the app cache and
 * hand it off to the system package installer.
 *
 * All network I/O runs on [Dispatchers.IO]. No external HTTP or JSON libraries are
 * used — the GitHub release payload is small enough to parse by hand.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    private const val GITHUB_API_URL =
        "https://api.github.com/repos/Botolog/Ghostify/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * Fetches the latest release from GitHub and returns [UpdateInfo] if a newer
     * version is available, or `null` if the app is already up to date.
     *
     * @throws IOException if the network request fails or the response is malformed.
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            val currentVersionCode = try {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
            } catch (_: Exception) {
                0L
            }

            Timber.d(TAG, "Current version code: %d", currentVersionCode)

            val url = URL(GITHUB_API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "Ghostify-Android")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            try {
                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    throw IOException("GitHub API returned HTTP $responseCode")
                }

                val body = conn.inputStream.bufferedReader().use { it.readText() }

                val tagName = extractJsonString(body, "tag_name")
                    ?: throw IOException("Missing tag_name in release JSON")
                val releaseBody = extractJsonString(body, "body") ?: ""
                val publishedAt = extractJsonString(body, "published_at") ?: ""

                val assetsStart = body.indexOf("\"assets\"")
                if (assetsStart == -1) {
                    throw IOException("No assets array found in release JSON")
                }

                val isDebug = try {
                    context.applicationContext.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                } catch (_: Exception) { false }

                val apkUrl = findApkUrl(body, assetsStart, preferDebug = isDebug)
                    ?: throw IOException("No APK asset found in release")
                val assetName = extractApkFileName(body, assetsStart, preferDebug = isDebug)
                val latestVersionCode = extractVersionCodeFromFileName(assetName, tagName)
                    ?: throw IOException("Could not extract versionCode from $assetName")

                Timber.d(
                    TAG,
                    "Latest release: tag=%s versionCode=%d",
                    tagName,
                    latestVersionCode,
                )

                if (!shouldOfferUpdate(currentVersionCode, latestVersionCode)) {
                    Timber.d(TAG, "Already up to date")
                    return@withContext null
                }

                UpdateInfo(
                    versionName = tagName.trimStart('v', 'V'),
                    versionCode = latestVersionCode,
                    releaseNotes = releaseBody,
                    apkDownloadUrl = apkUrl,
                    publishedAt = publishedAt,
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Downloads the APK to the app's cache directory.
     *
     * @param url Direct download URL for the APK asset.
     * @param onProgress Called with a value in `0.0..1.0` as bytes are received.
     * @return The local APK [File].
     * @throws IOException if the download fails.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val destDir = File(context.cacheDir, "updates").also { it.mkdirs() }
        val destFile = File(destDir, "ghostify-update.apk")

        Timber.d(TAG, "Downloading APK from %s", url)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

        try {
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw IOException("Download failed: HTTP $responseCode")
            }

            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }

            Timber.d(TAG, "APK downloaded to %s", destFile.absolutePath)
            destFile
        } catch (e: Exception) {
            Timber.e(TAG, "APK download failed", e)
            destFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Launches the Android package installer for the given APK file.
     *
     * Requires a `<provider>` entry in the manifest for `FileProvider` with the
     * authority `${applicationId}.fileprovider` and an XML resource that grants
     * read access to `cacheDir/updates/`.
     */
    fun installApk(context: Context, apkFile: File) {
        Timber.d(TAG, "Launching installer for %s", apkFile.absolutePath)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    /**
     * Returns `true` if the device allows installing packages from unknown sources.
     * On API 26+ this checks `canRequestPackageInstalls()`; on older versions
     * the permission is always granted.
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    // ── Private JSON helpers ──────────────────────────────────────────────

    /**
     * Returns `true` when [latestVersionCode] is strictly greater than
     * [currentVersionCode].  Equal or lower values never trigger an update
     * so the app never offers a downgrade.
     */
    internal fun shouldOfferUpdate(currentVersionCode: Long, latestVersionCode: Long): Boolean {
        return latestVersionCode > currentVersionCode
    }

    /**
     * Extracts a string value from a flat JSON object by key.
     *
     * This is intentionally simple — it handles escaped quotes and `\\n` but
     * does not attempt full recursive JSON parsing.  Sufficient for the small,
     * well-known GitHub release payload.
     */
    internal fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\""
        val start = json.indexOf(pattern)
        if (start == -1) return null

        val colonStart = json.indexOf(':', start + pattern.length)
        if (colonStart == -1) return null

        val quoteStart = json.indexOf('"', colonStart + 1)
        if (quoteStart == -1) return null

        val quoteEnd = findClosingQuote(json, quoteStart + 1)
        if (quoteEnd == -1) return null

        return json.substring(quoteStart + 1, quoteEnd)
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }

    internal fun findClosingQuote(json: String, from: Int): Int {
        var i = from
        while (i < json.length) {
            if (json[i] == '\\') {
                i += 2
                continue
            }
            if (json[i] == '"') return i
            i++
        }
        return -1
    }

    /**
     * Finds the download URL for an `.apk` asset in the JSON body.
     *
     * When [preferDebug] is set, prefers the matching variant first
     * (debug → `-debug.apk`, release → `-release.apk`), then falls back
     * to any `.apk` asset.
     */
    internal fun findApkUrl(json: String, fromAssets: Int, preferDebug: Boolean? = null): String? {
        for (apkIdx in matchingApkIndices(json, fromAssets, preferDebug)) {
            val url = extractApkUrlAt(json, apkIdx)
            if (url != null) return url
        }
        return null
    }

    /**
     * Extracts the APK asset filename from the `assets` array.
     *
     * When [preferDebug] is set, prefers the matching variant first,
     * then falls back to any `.apk` asset.
     */
    internal fun extractApkFileName(json: String, fromAssets: Int, preferDebug: Boolean? = null): String {
        for (apkIdx in matchingApkIndices(json, fromAssets, preferDebug)) {
            val name = extractApkNameAt(json, apkIdx)
            if (name != null) return name
        }
        return ""
    }

    /**
     * Returns `.apk` indices matching the preferred variant, then all `.apk` indices.
     */
    private fun matchingApkIndices(json: String, fromAssets: Int, preferDebug: Boolean?): Sequence<Int> = sequence {
        val desiredSuffix = when (preferDebug) {
            true -> "-debug.apk"
            false -> "-release.apk"
            null -> null
        }

        // First pass: preferred variant
        if (desiredSuffix != null) {
            var i = fromAssets
            while (i < json.length) {
                val apkIdx = json.indexOf(".apk", i)
                if (apkIdx == -1) break
                val name = extractApkNameAt(json, apkIdx)
                if (name != null && name.endsWith(desiredSuffix)) yield(apkIdx)
                i = apkIdx + 4
            }
        }

        // Second pass: any .apk not already yielded
        var i = fromAssets
        while (i < json.length) {
            val apkIdx = json.indexOf(".apk", i)
            if (apkIdx == -1) break
            val name = extractApkNameAt(json, apkIdx)
            if (name != null) {
                val isDesired = desiredSuffix != null && name.endsWith(desiredSuffix)
                if (!isDesired) yield(apkIdx)
            }
            i = apkIdx + 4
        }
    }

    /**
     * Extracts the `"name"` value for the asset containing [apkIdx].
     */
    private fun extractApkNameAt(json: String, apkIdx: Int): String? {
        val preceding = json.substring(0, apkIdx)
        val nameKey = "\"name\""
        val nameIdx = preceding.lastIndexOf(nameKey)
        if (nameIdx == -1) return null
        val colonIdx = json.indexOf(':', nameIdx + nameKey.length)
        if (colonIdx == -1) return null
        val quoteStart = json.indexOf('"', colonIdx + 1)
        if (quoteStart == -1) return null
        val quoteEnd = findClosingQuote(json, quoteStart + 1)
        if (quoteEnd == -1) return null
        val candidate = json.substring(quoteStart + 1, quoteEnd)
        return if (candidate.endsWith(".apk")) candidate else null
    }

    /**
     * Extracts the `"browser_download_url"` for the asset containing [apkIdx].
     *
     * Finds the asset's `"name"` first, then searches forward from there for
     * `"browser_download_url"` so we don't pick up a different asset's URL.
     */
    private fun extractApkUrlAt(json: String, apkIdx: Int): String? {
        val preceding = json.substring(0, apkIdx)
        val nameKey = "\"name\""
        val nameIdx = preceding.lastIndexOf(nameKey)
        if (nameIdx == -1) return null
        val bduKey = "\"browser_download_url\""
        val bduIdx = json.indexOf(bduKey, nameIdx)
        if (bduIdx == -1) return null
        val colonIdx = json.indexOf(':', bduIdx + bduKey.length)
        if (colonIdx == -1) return null
        val urlStart = json.indexOf('"', colonIdx + 1)
        if (urlStart == -1) return null
        val urlEnd = findClosingQuote(json, urlStart + 1)
        if (urlEnd == -1) return null
        val url = json.substring(urlStart + 1, urlEnd)
        return if (url.endsWith(".apk")) url else null
    }

    /**
     * Extracts a numeric version code from an APK filename or falls back to the
     * tag name.
     *
     * Filename patterns: `anything-vN.apk`, `anything-vN.N.N-debug.apk`, etc.
     * If the tag name itself is a plain number it is used directly.
     */
    internal fun extractVersionCodeFromFileName(fileName: String, tagName: String): Long? {
        // Pattern 1: pure numeric version — ghostify-v59-debug.apk -> 59
        val pureNumRegex = Regex("""[vV](\d+)[-_].*\.apk$""")
        val pureMatch = pureNumRegex.find(fileName)
        if (pureMatch != null) {
            return pureMatch.groupValues[1].toLongOrNull()
        }

        // Pattern 2: semantic version — ghostify-v0.3.0-debug.apk -> 30
        val semverRegex = Regex("""[vV](\d+)\.(\d+)(?:\.(\d+))?.*\.apk$""")
        val semverMatch = semverRegex.find(fileName)
        if (semverMatch != null) {
            val major = semverMatch.groupValues[1].toLongOrNull() ?: 0L
            val minor = semverMatch.groupValues[2].toLongOrNull() ?: 0L
            val patch = semverMatch.groupValues[3].toLongOrNull() ?: 0L
            return major * 10000 + minor * 100 + patch
        }

        // Pattern 3: tag name is a plain number
        val tagNumber = tagName.trimStart('v', 'V').toLongOrNull()
        if (tagNumber != null) return tagNumber

        return null
    }
}
