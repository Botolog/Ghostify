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

    // ── shouldOfferUpdate: version-name precedence ────────────────────

    @Test
    fun shouldOfferUpdate_sameVersion_returnsFalse() {
        assertFalse(shouldOffer(current = "0.4.8", latest = "0.4.8"))
    }

    @Test
    fun shouldOfferUpdate_equalWithVPrefix_returnsFalse() {
        assertFalse(shouldOffer(current = "0.4.8", latest = "v0.4.8"))
    }

    @Test
    fun shouldOfferUpdate_equalPatchBump_returnsTrue() {
        assertTrue(shouldOffer(current = "0.4.8", latest = "0.4.9"))
    }

    @Test
    fun shouldOfferUpdate_latestMinorNewer_returnsTrue() {
        assertTrue(shouldOffer(current = "0.4.8", latest = "0.5.0"))
    }

    @Test
    fun shouldOfferUpdate_latestMajorNewer_returnsTrue() {
        assertTrue(shouldOffer(current = "0.9.9", latest = "1.0.0"))
    }

    @Test
    fun shouldOfferUpdate_latestOlder_returnsFalse() {
        assertFalse(shouldOffer(current = "0.4.8", latest = "0.4.7"))
    }

    @Test
    fun shouldOfferUpdate_latestMuchOlder_returnsFalse() {
        assertFalse(shouldOffer(current = "1.0.0", latest = "0.1.0"))
    }

    @Test
    fun shouldOfferUpdate_shortenedCurrent_returnsFalse() {
        assertFalse(shouldOffer(current = "0.4", latest = "0.4.0"))
    }

    @Test
    fun shouldOfferUpdate_twoSegmentLatest_returnsTrue() {
        assertTrue(shouldOffer(current = "0.4.8", latest = "0.5"))
    }

    @Test
    fun shouldOfferUpdate_ignoresBuildMetadata() {
        assertFalse(shouldOffer(current = "0.4.8+build.7", latest = "0.4.8"))
        assertFalse(shouldOffer(current = "0.4.8", latest = "0.4.8+build.9"))
    }

    @Test
    fun shouldOfferUpdate_multiDigitComponents_compareNumerically() {
        assertTrue(shouldOffer(current = "0.4.9", latest = "0.4.10"))
        assertFalse(shouldOffer(current = "0.4.10", latest = "0.4.9"))
    }

    @Test
    fun shouldOfferUpdate_versionCodeMismatch_doesNotOfferEqualVersion() {
        // Installed code 85 vs release-derived code 408 for the same 0.4.8 name.
        assertFalse(
            UpdateChecker.shouldOfferUpdate(
                currentVersionName = "0.4.8",
                currentVersionCode = 85L,
                latestVersionName = "0.4.8",
                latestVersionCode = 408L,
            ),
        )
    }

    @Test
    fun shouldOfferUpdate_versionCodeMismatch_newerNameStillOffered() {
        assertTrue(
            UpdateChecker.shouldOfferUpdate(
                currentVersionName = "0.4.8",
                currentVersionCode = 408L,
                latestVersionName = "0.4.9",
                latestVersionCode = 409L,
            ),
        )
    }

    // ── shouldOfferUpdate: prerelease precedence ─────────────────────

    @Test
    fun shouldOfferUpdate_stableOutranksItsPrerelease() {
        assertFalse(shouldOffer(current = "0.5.0", latest = "0.5.0-beta"))
    }

    @Test
    fun shouldOfferUpdate_newerPrereleaseOverOlderStable() {
        assertTrue(shouldOffer(current = "0.4.8", latest = "0.5.0-beta"))
    }

    @Test
    fun shouldOfferUpdate_prereleaseChainFollowsSemver() {
        assertTrue(shouldOffer(current = "0.5.0-alpha", latest = "0.5.0-beta"))
        assertFalse(shouldOffer(current = "0.5.0-beta", latest = "0.5.0-alpha"))
        assertTrue(shouldOffer(current = "0.5.0-rc.1", latest = "0.5.0-rc.2"))
        assertTrue(shouldOffer(current = "0.5.0-rc.9", latest = "0.5.0-rc.10"))
        assertTrue(shouldOffer(current = "0.5.0-beta", latest = "0.5.0-rc.1"))
    }

    // ── shouldOfferUpdate: malformed / unknown versions ───────────────

    @Test
    fun shouldOfferUpdate_malformedLatest_returnsFalse() {
        assertFalse(shouldOffer(current = "0.4.8", latest = "nightly"))
        assertFalse(shouldOffer(current = "0.4.8", latest = ""))
        assertFalse(shouldOffer(current = "0.4.8", latest = "1.2.3.4.5.x"))
    }

    @Test
    fun shouldOfferUpdate_malformedCurrent_returnsFalse() {
        assertFalse(shouldOffer(current = "unknown", latest = "0.4.9"))
        assertFalse(shouldOffer(current = null, latest = "0.4.9"))
        assertFalse(shouldOffer(current = "", latest = "0.4.9"))
    }

    @Test
    fun shouldOfferUpdate_bothMalformed_returnsFalse() {
        assertFalse(shouldOffer(current = "nightly", latest = "snapshot"))
    }

    @Test
    fun shouldOfferUpdate_zeroCodeDoesNotForceOffer() {
        assertFalse(
            UpdateChecker.shouldOfferUpdate(
                currentVersionName = "0.4.8",
                currentVersionCode = 0L,
                latestVersionName = "0.4.8",
                latestVersionCode = 0L,
            ),
        )
    }

    private fun shouldOffer(current: String?, latest: String?): Boolean =
        UpdateChecker.shouldOfferUpdate(
            currentVersionName = current,
            currentVersionCode = 0L,
            latestVersionName = latest,
            latestVersionCode = 0L,
        )

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
        val info = select(releasesListJson, current = "0.3.0", includePreReleases = false)
        assertEquals("0.4.0", info?.versionName)
        assertEquals(40L, info?.versionCode)
    }

    @Test
    fun selectRelease_includePreReleases_picksNewestEligible() {
        val info = select(releasesListJson, current = "0.3.0", includePreReleases = true)
        assertEquals("0.5.0-beta", info?.versionName)
        assertEquals(50L, info?.versionCode)
    }

    @Test
    fun selectRelease_nothingNewer_returnsNull() {
        assertNull(select(releasesListJson, current = "0.5.0", includePreReleases = true))
        assertNull(select(releasesListJson, current = "0.4.0", includePreReleases = false))
    }

    @Test
    fun selectRelease_sameAsLatest_returnsNull() {
        assertNull(select(releasesListJson, current = "0.4.0", includePreReleases = false))
        assertNull(select(releasesListJson, current = "0.5.0-beta", includePreReleases = true))
    }

    @Test
    fun selectRelease_newerThanLatestOnGitHub_returnsNull() {
        // A locally built version ahead of every published release must not be
        // offered an "update" back down to the latest published one.
        assertNull(select(releasesListJson, current = "9.9.9", includePreReleases = false))
        assertNull(select(releasesListJson, current = "9.9.9", includePreReleases = true))
    }

    @Test
    fun selectRelease_releaseWithoutApk_isSkipped() {
        val json = """[{"tag_name":"v0.9.0","prerelease":false,"draft":false,"body":"","assets":[]},
            |${releaseJson("v0.4.0", false, false, 40)}]""".trimMargin()
        val info = select(json, current = "0.3.0", includePreReleases = false)
        assertEquals(40L, info?.versionCode)
    }

    private fun select(
        json: String,
        current: String?,
        includePreReleases: Boolean,
    ): UpdateInfo? = UpdateChecker.selectRelease(
        releasesJson = json,
        currentVersionName = current,
        currentVersionCode = 0L,
        includePreReleases = includePreReleases,
    )
}
