package xyz.botolog.ghostify.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // ── Real GitHub release JSON (minified, single-line) ──────────────

    private val realMinifiedJson = """
        {"url":"https://api.github.com/repos/Botolog/Ghostify/releases/390576596",
        "tag_name":"v0.3.0",
        "name":"Ghostify v0.3.0",
        "body":"player an lyrics are cool",
        "published_at":"2026-09-17T09:20:03Z",
        "assets":[
        {"name":"ghostify-v0.3.0-debug.apk","browser_download_url":"https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-debug.apk"},
        {"name":"ghostify-v0.3.0-release.apk","browser_download_url":"https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-release.apk"}
        ]}
    """.trimIndent().replace("\n", "")

    private val prettyJson = """
        {
          "tag_name": "v0.3.0",
          "name": "Ghostify v0.3.0",
          "body": "player an lyrics are cool",
          "published_at": "2026-09-17T09:20:03Z",
          "assets": [
            {
              "name": "ghostify-v0.3.0-debug.apk",
              "browser_download_url": "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-debug.apk"
            },
            {
              "name": "ghostify-v0.3.0-release.apk",
              "browser_download_url": "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-release.apk"
            }
          ]
        }
    """.trimIndent()

    // ── extractJsonString ─────────────────────────────────────────────

    @Test
    fun extractJsonString_tagName_minified() {
        assertEquals("v0.3.0", UpdateChecker.extractJsonString(realMinifiedJson, "tag_name"))
    }

    @Test
    fun extractJsonString_tagName_pretty() {
        assertEquals("v0.3.0", UpdateChecker.extractJsonString(prettyJson, "tag_name"))
    }

    @Test
    fun extractJsonString_name() {
        assertEquals("Ghostify v0.3.0", UpdateChecker.extractJsonString(realMinifiedJson, "name"))
    }

    @Test
    fun extractJsonString_body() {
        assertEquals("player an lyrics are cool", UpdateChecker.extractJsonString(realMinifiedJson, "body"))
    }

    @Test
    fun extractJsonString_missingKey_returnsNull() {
        assertNull(UpdateChecker.extractJsonString(realMinifiedJson, "nonexistent"))
    }

    // ── findApkUrl ───────────────────────────────────────────────────

    @Test
    fun findApkUrl_minifiedJson() {
        val assetsStart = realMinifiedJson.indexOf("\"assets\"")
        val url = UpdateChecker.findApkUrl(realMinifiedJson, assetsStart)
        assertEquals(
            "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-debug.apk",
            url,
        )
    }

    @Test
    fun findApkUrl_prettyJson() {
        val assetsStart = prettyJson.indexOf("\"assets\"")
        val url = UpdateChecker.findApkUrl(prettyJson, assetsStart)
        assertEquals(
            "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-debug.apk",
            url,
        )
    }

    @Test
    fun findApkUrl_noAssets_returnsNull() {
        val json = """{"tag_name":"v1.0"}"""
        assertNull(UpdateChecker.findApkUrl(json, 0))
    }

    @Test
    fun findApkUrl_preferDebug_returnsDebugApk() {
        val assetsStart = realMinifiedJson.indexOf("\"assets\"")
        val url = UpdateChecker.findApkUrl(realMinifiedJson, assetsStart, preferDebug = true)
        assertEquals(
            "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-debug.apk",
            url,
        )
    }

    @Test
    fun findApkUrl_preferRelease_returnsReleaseApk() {
        val assetsStart = realMinifiedJson.indexOf("\"assets\"")
        val url = UpdateChecker.findApkUrl(realMinifiedJson, assetsStart, preferDebug = false)
        assertEquals(
            "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-release.apk",
            url,
        )
    }

    @Test
    fun findApkUrl_preferDebug_fallsBackToAnyApk() {
        // JSON with only a release APK — preferDebug should fall back
        val json = """
            {"tag_name":"v1.0","assets":[{"name":"app-v1.0-release.apk","browser_download_url":"https://example.com/app-v1.0-release.apk"}]}
        """.trimIndent().replace("\n", "")
        val assetsStart = json.indexOf("\"assets\"")
        val url = UpdateChecker.findApkUrl(json, assetsStart, preferDebug = true)
        assertEquals("https://example.com/app-v1.0-release.apk", url)
    }

    @Test
    fun findApkUrl_preferRelease_fallsBackToAnyApk() {
        // JSON with only a debug APK — preferRelease should fall back
        val json = """
            {"tag_name":"v1.0","assets":[{"name":"app-v1.0-debug.apk","browser_download_url":"https://example.com/app-v1.0-debug.apk"}]}
        """.trimIndent().replace("\n", "")
        val assetsStart = json.indexOf("\"assets\"")
        val url = UpdateChecker.findApkUrl(json, assetsStart, preferDebug = false)
        assertEquals("https://example.com/app-v1.0-debug.apk", url)
    }

    // ── extractApkFileName ───────────────────────────────────────────

    @Test
    fun extractApkFileName_minifiedJson() {
        val assetsStart = realMinifiedJson.indexOf("\"assets\"")
        assertEquals(
            "ghostify-v0.3.0-debug.apk",
            UpdateChecker.extractApkFileName(realMinifiedJson, assetsStart),
        )
    }

    @Test
    fun extractApkFileName_prettyJson() {
        val assetsStart = prettyJson.indexOf("\"assets\"")
        assertEquals(
            "ghostify-v0.3.0-debug.apk",
            UpdateChecker.extractApkFileName(prettyJson, assetsStart),
        )
    }

    @Test
    fun extractApkFileName_noAssets_returnsEmpty() {
        val json = """{"tag_name":"v1.0"}"""
        assertEquals("", UpdateChecker.extractApkFileName(json, 0))
    }

    @Test
    fun extractApkFileName_preferDebug_returnsDebugName() {
        val assetsStart = realMinifiedJson.indexOf("\"assets\"")
        assertEquals(
            "ghostify-v0.3.0-debug.apk",
            UpdateChecker.extractApkFileName(realMinifiedJson, assetsStart, preferDebug = true),
        )
    }

    @Test
    fun extractApkFileName_preferRelease_returnsReleaseName() {
        val assetsStart = realMinifiedJson.indexOf("\"assets\"")
        assertEquals(
            "ghostify-v0.3.0-release.apk",
            UpdateChecker.extractApkFileName(realMinifiedJson, assetsStart, preferDebug = false),
        )
    }

    // ── extractVersionCodeFromFileName ────────────────────────────────

    @Test
    fun extractVersionCode_semver() {
        // v0.3.0 -> 0*10000 + 3*100 + 0 = 300
        assertEquals(
            300L,
            UpdateChecker.extractVersionCodeFromFileName("ghostify-v0.3.0-debug.apk", "v0.3.0"),
        )
    }

    @Test
    fun extractVersionCode_pureNumeric() {
        assertEquals(
            59L,
            UpdateChecker.extractVersionCodeFromFileName("ghostify-v59-debug.apk", "v59"),
        )
    }

    @Test
    fun extractVersionCode_tagIsPlainNumber() {
        assertEquals(
            42L,
            UpdateChecker.extractVersionCodeFromFileName("app.apk", "42"),
        )
    }

    @Test
    fun extractVersionCode_noMatch_returnsNull() {
        assertNull(UpdateChecker.extractVersionCodeFromFileName("app.apk", "release"))
    }

    // ── Full pipeline: minified JSON → version code ───────────────────

    @Test
    fun fullPipeline_minifiedJson_extractsVersionCode() {
        val body = realMinifiedJson
        val tagName = UpdateChecker.extractJsonString(body, "tag_name")!!
        val assetsStart = body.indexOf("\"assets\"")
        val apkUrl = UpdateChecker.findApkUrl(body, assetsStart)!!
        val assetName = UpdateChecker.extractApkFileName(body, assetsStart)
        val versionCode = UpdateChecker.extractVersionCodeFromFileName(assetName, tagName)

        assertEquals("v0.3.0", tagName)
        assertEquals("ghostify-v0.3.0-debug.apk", assetName)
        assertEquals(
            "https://github.com/Botolog/Ghostify/releases/download/v0.3.0/ghostify-v0.3.0-debug.apk",
            apkUrl,
        )
        assertEquals(300L, versionCode)
    }

    @Test
    fun fullPipeline_prettyJson_extractsVersionCode() {
        val body = prettyJson
        val tagName = UpdateChecker.extractJsonString(body, "tag_name")!!
        val assetsStart = body.indexOf("\"assets\"")
        val apkUrl = UpdateChecker.findApkUrl(body, assetsStart)!!
        val assetName = UpdateChecker.extractApkFileName(body, assetsStart)
        val versionCode = UpdateChecker.extractVersionCodeFromFileName(assetName, tagName)

        assertEquals("v0.3.0", tagName)
        assertEquals("ghostify-v0.3.0-debug.apk", assetName)
        assertEquals(300L, versionCode)
    }

    // ── shouldOfferUpdate: anti-downgrade ─────────────────────────────

    @Test
    fun shouldOfferUpdate_latestHigher_returnsTrue() {
        assertTrue(UpdateChecker.shouldOfferUpdate(currentVersionCode = 59, latestVersionCode = 60))
    }

    @Test
    fun shouldOfferUpdate_sameVersion_returnsFalse() {
        assertFalse(UpdateChecker.shouldOfferUpdate(currentVersionCode = 59, latestVersionCode = 59))
    }

    @Test
    fun shouldOfferUpdate_latestLower_returnsFalse() {
        assertFalse(UpdateChecker.shouldOfferUpdate(currentVersionCode = 59, latestVersionCode = 58))
    }

    @Test
    fun shouldOfferUpdate_latestMuchLower_returnsFalse() {
        assertFalse(UpdateChecker.shouldOfferUpdate(currentVersionCode = 300, latestVersionCode = 1))
    }

    @Test
    fun shouldOfferUpdate_currentZero_returnsTrue() {
        assertTrue(UpdateChecker.shouldOfferUpdate(currentVersionCode = 0, latestVersionCode = 1))
    }

    @Test
    fun shouldOfferUpdate_bothZero_returnsFalse() {
        assertFalse(UpdateChecker.shouldOfferUpdate(currentVersionCode = 0, latestVersionCode = 0))
    }

    @Test
    fun shouldOfferUpdate_currentHigherByOne_returnsFalse() {
        assertFalse(UpdateChecker.shouldOfferUpdate(currentVersionCode = 60, latestVersionCode = 59))
    }

    @Test
    fun shouldOfferUpdate_latestHigherByOne_returnsTrue() {
        assertTrue(UpdateChecker.shouldOfferUpdate(currentVersionCode = 59, latestVersionCode = 60))
    }

    @Test
    fun shouldOfferUpdate_largeGap_downgradeBlocked() {
        assertFalse(UpdateChecker.shouldOfferUpdate(currentVersionCode = 999, latestVersionCode = 1))
    }

    @Test
    fun shouldOfferUpdate_largeGap_upgradeAllowed() {
        assertTrue(UpdateChecker.shouldOfferUpdate(currentVersionCode = 1, latestVersionCode = 999))
    }

    // ── extractJsonBoolean ────────────────────────────────────────────

    @Test
    fun extractJsonBoolean_parsesTrueAndFalse() {
        val json = """{"prerelease": true, "draft":false}"""
        assertTrue(UpdateChecker.extractJsonBoolean(json, "prerelease") == true)
        assertTrue(UpdateChecker.extractJsonBoolean(json, "draft") == false)
    }

    @Test
    fun extractJsonBoolean_missingOrNonBoolean_returnsNull() {
        val json = """{"prerelease": "yes", "tag_name": "v1"}"""
        assertNull(UpdateChecker.extractJsonBoolean(json, "prerelease"))
        assertNull(UpdateChecker.extractJsonBoolean(json, "draft"))
    }

    // ── splitTopLevelJsonObjects ──────────────────────────────────────

    @Test
    fun splitTopLevelJsonObjects_extractsReleaseObjects() {
        val json = """[{"tag_name":"v2","body":"a { tricky } \"quoted\""},{"tag_name":"v1"}]"""
        val objects = UpdateChecker.splitTopLevelJsonObjects(json)
        assertEquals(2, objects.size)
        assertEquals("v2", UpdateChecker.extractJsonString(objects[0], "tag_name"))
        assertEquals("v1", UpdateChecker.extractJsonString(objects[1], "tag_name"))
    }

    @Test
    fun splitTopLevelJsonObjects_emptyArray_returnsEmpty() {
        assertTrue(UpdateChecker.splitTopLevelJsonObjects("[]").isEmpty())
    }

    // ── selectRelease ─────────────────────────────────────────────────

    private fun releaseJson(tag: String, pre: Boolean, draft: Boolean, version: Int): String {
        return """{"tag_name":"$tag","prerelease":$pre,"draft":$draft,"body":"","published_at":"",
            |"assets":[{"name":"ghostify-v$version-release.apk",
            |"browser_download_url":"https://example.com/ghostify-v$version-release.apk"}]}""".trimMargin()
    }

    private val releasesListJson: String
        get() = "[${releaseJson("v0.5.0-beta", true, false, 50)}," +
            "${releaseJson("v0.4.0", false, false, 40)}," +
            "${releaseJson("v0.6.0-draft", false, true, 60)}]"

    @Test
    fun selectRelease_stableOnly_skipsPreReleaseAndDraft() {
        val info = UpdateChecker.selectRelease(releasesListJson, 30, includePreReleases = false)
        assertEquals("0.4.0", info?.versionName)
        assertEquals(40L, info?.versionCode)
    }

    @Test
    fun selectRelease_includePreReleases_picksNewestEligible() {
        val info = UpdateChecker.selectRelease(releasesListJson, 30, includePreReleases = true)
        assertEquals("0.5.0-beta", info?.versionName)
        assertEquals(50L, info?.versionCode)
    }

    @Test
    fun selectRelease_nothingNewer_returnsNull() {
        assertNull(UpdateChecker.selectRelease(releasesListJson, 50, includePreReleases = true))
        assertNull(UpdateChecker.selectRelease(releasesListJson, 40, includePreReleases = false))
    }

    @Test
    fun selectRelease_releaseWithoutApk_isSkipped() {
        val json = """[{"tag_name":"v0.9.0","prerelease":false,"draft":false,"body":"","assets":[]},
            |${releaseJson("v0.4.0", false, false, 40)}]""".trimMargin()
        val info = UpdateChecker.selectRelease(json, 30, includePreReleases = false)
        assertEquals(40L, info?.versionCode)
    }
}
