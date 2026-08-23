package xyz.botolog.ghostify.trackdownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDownloadModelsTest {

    // ── TrackDownloadConfig ───────────────────────────────────────────────

    @Test
    fun configCreationWithAllArgs() {
        val config = TrackDownloadConfig(
            outputDir = "/downloads",
            bitrate = 320,
            outputTemplate = "{title}",
            ffmpeg = "/usr/bin/ffmpeg"
        )
        assertEquals("/downloads", config.outputDir)
        assertEquals(320, config.bitrate)
        assertEquals("{title}", config.outputTemplate)
        assertEquals("/usr/bin/ffmpeg", config.ffmpeg)
    }

    @Test
    fun configDefaults() {
        val config = TrackDownloadConfig(outputDir = "/out")
        assertEquals(192, config.bitrate)
        assertEquals("{artists} - {title}", config.outputTemplate)
        assertEquals("ffmpeg", config.ffmpeg)
    }

    @Test
    fun configDefaultBitrateIs192() {
        assertEquals(192, TrackDownloadConfig.DEFAULT_BITRATE)
    }

    @Test
    fun configDefaultTemplateIsArtistsTitle() {
        assertEquals("{artists} - {title}", TrackDownloadConfig.DEFAULT_TEMPLATE)
    }

    @Test
    fun configAllowedBitratesAre128192320() {
        assertEquals(
            intArrayOf(128, 192, 320).toList(),
            TrackDownloadConfig.ALLOWED_BITRATES.toList()
        )
    }

    @Test
    fun isValidBitrateReturnsTrueForAllowed() {
        assertTrue(TrackDownloadConfig.isValidBitrate(128))
        assertTrue(TrackDownloadConfig.isValidBitrate(192))
        assertTrue(TrackDownloadConfig.isValidBitrate(320))
    }

    @Test
    fun isValidBitrateReturnsFalseForDisallowed() {
        assertFalse(TrackDownloadConfig.isValidBitrate(64))
        assertFalse(TrackDownloadConfig.isValidBitrate(256))
        assertFalse(TrackDownloadConfig.isValidBitrate(0))
        assertFalse(TrackDownloadConfig.isValidBitrate(-1))
    }

    @Test
    fun configDefaultInstanceHasEmptyOutputDir() {
        val def = TrackDownloadConfig.DEFAULT
        assertEquals("", def.outputDir)
        assertEquals(TrackDownloadConfig.DEFAULT_BITRATE, def.bitrate)
    }

    @Test
    fun configDataClassEquality() {
        val a = TrackDownloadConfig(outputDir = "/a", bitrate = 128)
        val b = TrackDownloadConfig(outputDir = "/a", bitrate = 128)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun configDataClassCopy() {
        val original = TrackDownloadConfig(outputDir = "/orig")
        val copied = original.copy(bitrate = 320)
        assertEquals(320, copied.bitrate)
        assertEquals("/orig", copied.outputDir)
    }

    // ── DownloadErrorKind ─────────────────────────────────────────────────

    @Test
    fun fromWireReturnsMatchingKind() {
        assertEquals(DownloadErrorKind.NO_TRACK, DownloadErrorKind.fromWire("NO_TRACK"))
        assertEquals(
            DownloadErrorKind.METADATA_FAILED,
            DownloadErrorKind.fromWire("METADATA_FAILED")
        )
        assertEquals(DownloadErrorKind.SEARCH_FAILED, DownloadErrorKind.fromWire("SEARCH_FAILED"))
        assertEquals(
            DownloadErrorKind.AUDIO_UNAVAILABLE,
            DownloadErrorKind.fromWire("AUDIO_UNAVAILABLE")
        )
        assertEquals(
            DownloadErrorKind.CONVERSION_FAILED,
            DownloadErrorKind.fromWire("CONVERSION_FAILED")
        )
        assertEquals(
            DownloadErrorKind.TAGGING_FAILED,
            DownloadErrorKind.fromWire("TAGGING_FAILED")
        )
        assertEquals(
            DownloadErrorKind.VALIDATION_FAILED,
            DownloadErrorKind.fromWire("VALIDATION_FAILED")
        )
        assertEquals(DownloadErrorKind.IO, DownloadErrorKind.fromWire("IO"))
        assertEquals(DownloadErrorKind.INTERRUPTED, DownloadErrorKind.fromWire("INTERRUPTED"))
        assertEquals(DownloadErrorKind.DEPENDENCY, DownloadErrorKind.fromWire("DEPENDENCY"))
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromWire("UNKNOWN"))
    }

    @Test
    fun fromWireReturnsUnknownForUnrecognised() {
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromWire("BOGUS"))
    }

    @Test
    fun fromWireReturnsUnknownForNull() {
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromWire(null))
    }

    @Test
    fun fromWireReturnsUnknownForBlank() {
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromWire(""))
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromWire("   "))
    }

    @Test
    fun fromWireIsCaseSensitive() {
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromWire("no_track"))
    }

    @Test
    fun allExpectedEnumValuesExist() {
        val expected = setOf(
            "NO_TRACK", "METADATA_FAILED", "SEARCH_FAILED", "AUDIO_UNAVAILABLE",
            "CONVERSION_FAILED", "TAGGING_FAILED", "VALIDATION_FAILED",
            "IO", "INTERRUPTED", "DEPENDENCY", "UNKNOWN"
        )
        assertEquals(expected, DownloadErrorKind.entries.map { it.name }.toSet())
    }

    @Test
    fun enumValuesCountIsEleven() {
        assertEquals(11, DownloadErrorKind.entries.size)
    }

    // ── DownloadErrorKind.fromMessage ─────────────────────────────────────

    @Test
    fun fromMessageExtractsTokenFromPrefixedMessage() {
        assertEquals(
            DownloadErrorKind.NO_TRACK,
            DownloadErrorKind.fromMessage("TrackDownloadError: NO_TRACK: track not found")
        )
    }

    @Test
    fun fromMessageExtractsTokenFromBareMessage() {
        assertEquals(
            DownloadErrorKind.CONVERSION_FAILED,
            DownloadErrorKind.fromMessage("CONVERSION_FAILED happened")
        )
    }

    @Test
    fun fromMessageReturnsUnknownForNoToken() {
        assertEquals(
            DownloadErrorKind.UNKNOWN,
            DownloadErrorKind.fromMessage("something went wrong")
        )
    }

    @Test
    fun fromMessageReturnsUnknownForNull() {
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromMessage(null))
    }

    @Test
    fun fromMessageReturnsUnknownForEmpty() {
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadErrorKind.fromMessage(""))
    }

    // ── DownloadErrorKind.humanMessage ────────────────────────────────────

    @Test
    fun humanMessageExtractsAfterToken() {
        assertEquals(
            "track not found",
            DownloadErrorKind.humanMessage("NO_TRACK: track not found")
        )
    }

    @Test
    fun humanMessageExtractsFromPrefixedMessage() {
        assertEquals(
            "something broke",
            DownloadErrorKind.humanMessage("TrackDownloadError: IO: something broke")
        )
    }

    @Test
    fun humanMessageReturnsFullMessageWhenNoToken() {
        assertEquals(
            "something went wrong",
            DownloadErrorKind.humanMessage("something went wrong")
        )
    }

    @Test
    fun humanMessageReturnsFallbackForNull() {
        assertEquals("Download failed.", DownloadErrorKind.humanMessage(null))
    }

    @Test
    fun humanMessageReturnsFallbackForEmpty() {
        assertEquals("Download failed.", DownloadErrorKind.humanMessage(""))
    }

    @Test
    fun humanMessageReturnsFullMessageWhenTokenAtEndNoSeparator() {
        assertEquals(
            "IO",
            DownloadErrorKind.humanMessage("IO")
        )
    }

    // ── DownloadError ─────────────────────────────────────────────────────

    @Test
    fun downloadErrorCreation() {
        val error = DownloadError(
            kind = DownloadErrorKind.IO,
            message = "Disk full",
            wireType = "IO"
        )
        assertEquals(DownloadErrorKind.IO, error.kind)
        assertEquals("Disk full", error.message)
        assertEquals("IO", error.wireType)
    }

    @Test
    fun downloadErrorDefaultsWireTypeToNull() {
        val error = DownloadError(kind = DownloadErrorKind.IO, message = "e")
        assertNull(error.wireType)
    }

    @Test
    fun downloadErrorEquality() {
        val a = DownloadError(DownloadErrorKind.IO, "msg", "IO")
        val b = DownloadError(DownloadErrorKind.IO, "msg", "IO")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun downloadErrorUnknownFactoryWithMessage() {
        val error = DownloadError.unknown("raw message")
        assertEquals(DownloadErrorKind.UNKNOWN, error.kind)
        assertEquals("raw message", error.message)
    }

    @Test
    fun downloadErrorUnknownFactoryWithNull() {
        val error = DownloadError.unknown(null)
        assertEquals(DownloadErrorKind.UNKNOWN, error.kind)
        assertEquals("The track could not be downloaded.", error.message)
    }

    @Test
    fun downloadErrorUnknownFactoryWithBlank() {
        val error = DownloadError.unknown("   ")
        assertEquals(DownloadErrorKind.UNKNOWN, error.kind)
        assertEquals("The track could not be downloaded.", error.message)
    }

    @Test
    fun downloadErrorUnknownFactoryWithEmpty() {
        val error = DownloadError.unknown("")
        assertEquals(DownloadErrorKind.UNKNOWN, error.kind)
        assertEquals("The track could not be downloaded.", error.message)
    }

    // ── TrackInfo ─────────────────────────────────────────────────────────

    @Test
    fun trackInfoCreationWithAllArgs() {
        val info = TrackInfo(
            url = "https://example.com/track",
            spotifyId = "abc123",
            title = "Song",
            artists = "Artist",
            album = "Album",
            durationMs = 200000L,
            coverUrl = "https://example.com/cover.jpg"
        )
        assertEquals("https://example.com/track", info.url)
        assertEquals("abc123", info.spotifyId)
        assertEquals("Song", info.title)
        assertEquals("Artist", info.artists)
        assertEquals("Album", info.album)
        assertEquals(200000L, info.durationMs)
        assertEquals("https://example.com/cover.jpg", info.coverUrl)
    }

    @Test
    fun trackInfoFromMapParsesAllFields() {
        val map = mapOf(
            "url" to "https://example.com",
            "spotify_id" to "id1",
            "title" to "T",
            "artists" to "A",
            "album" to "Al",
            "duration_ms" to 300000,
            "cover_url" to "https://example.com/cover"
        )
        val info = TrackInfo.fromMap(map)
        assertEquals("https://example.com", info.url)
        assertEquals("id1", info.spotifyId)
        assertEquals("T", info.title)
        assertEquals("A", info.artists)
        assertEquals("Al", info.album)
        assertEquals(300000L, info.durationMs)
        assertEquals("https://example.com/cover", info.coverUrl)
    }

    @Test
    fun trackInfoFromMapDefaultsMissingFields() {
        val info = TrackInfo.fromMap(emptyMap<String, Any?>())
        assertEquals("", info.url)
        assertNull(info.spotifyId)
        assertNull(info.title)
        assertNull(info.artists)
        assertNull(info.album)
        assertNull(info.durationMs)
        assertNull(info.coverUrl)
    }

    @Test
    fun trackInfoFromMapHandlesWrongTypes() {
        val map = mapOf(
            "url" to 123,
            "duration_ms" to "not a number"
        )
        val info = TrackInfo.fromMap(map)
        assertEquals("", info.url)
        assertNull(info.durationMs)
    }

    // ── TrackDownloadResult.fromMap ───────────────────────────────────────

    @Test
    fun fromMapParsesDownloadedStatus() {
        val map = mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://example.com/track",
            "output_path" to "/downloads/song.mp3",
            "file_size" to 5000000,
            "title" to "Song",
            "artists" to "Artist",
            "album" to "Album",
            "bitrate" to "192",
            "duration_ms" to 180000
        )
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Downloaded)
        val downloaded = result as TrackDownloadResult.Downloaded
        assertEquals("https://example.com/track", downloaded.url)
        assertEquals("/downloads/song.mp3", downloaded.outputPath)
        assertEquals(5000000L, downloaded.fileSize)
        assertEquals("Song", downloaded.title)
        assertEquals("Artist", downloaded.artists)
        assertEquals("Album", downloaded.album)
        assertEquals("192", downloaded.bitrate)
        assertEquals(180000L, downloaded.durationMs)
    }

    @Test
    fun fromMapParsesSkippedStatus() {
        val map = mapOf(
            "status" to "SKIPPED",
            "url" to "https://example.com/track",
            "output_path" to "/downloads/song.mp3",
            "title" to "Song",
            "artists" to "Artist",
            "album" to "Album",
            "bitrate" to "192"
        )
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Skipped)
        val skipped = result as TrackDownloadResult.Skipped
        assertEquals("https://example.com/track", skipped.url)
        assertEquals("/downloads/song.mp3", skipped.outputPath)
        assertEquals("Song", skipped.title)
    }

    @Test
    fun fromMapParsesFailedStatus() {
        val map = mapOf(
            "status" to "FAILED",
            "url" to "https://example.com/track",
            "error_type" to "SEARCH_FAILED",
            "error" to "SEARCH_FAILED: no results found"
        )
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Failure)
        val failure = result as TrackDownloadResult.Failure
        assertEquals("https://example.com/track", failure.url)
        assertEquals(DownloadErrorKind.SEARCH_FAILED, failure.error.kind)
        assertEquals("no results found", failure.error.message)
        assertEquals("SEARCH_FAILED", failure.error.wireType)
    }

    @Test
    fun fromMapHandlesNullStatus() {
        val map = mapOf("url" to "https://example.com")
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Failure)
        val failure = result as TrackDownloadResult.Failure
        assertEquals(DownloadErrorKind.UNKNOWN, failure.error.kind)
    }

    @Test
    fun fromMapHandlesUnrecognisedStatus() {
        val map = mapOf("status" to "PARTIAL", "url" to "https://example.com")
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Failure)
        val failure = result as TrackDownloadResult.Failure
        assertEquals(DownloadErrorKind.UNKNOWN, failure.error.kind)
    }

    @Test
    fun fromMapHandlesMissingUrl() {
        val map = mapOf("status" to "DOWNLOADED")
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Downloaded)
        assertEquals("", (result as TrackDownloadResult.Downloaded).url)
    }

    @Test
    fun fromMapDownloadedWithMissingMetadata() {
        val map = mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://example.com"
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Downloaded
        assertEquals("", result.outputPath)
        assertNull(result.fileSize)
        assertNull(result.title)
        assertNull(result.artists)
        assertNull(result.album)
        assertNull(result.bitrate)
        assertNull(result.durationMs)
    }

    @Test
    fun fromMapSkippedWithMissingMetadata() {
        val map = mapOf(
            "status" to "SKIPPED",
            "url" to "https://example.com"
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Skipped
        assertEquals("", result.outputPath)
        assertNull(result.title)
        assertNull(result.artists)
        assertNull(result.album)
        assertNull(result.bitrate)
    }

    @Test
    fun fromMapFailedWithMissingErrorFields() {
        val map = mapOf(
            "status" to "FAILED",
            "url" to "https://example.com"
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Failure
        assertEquals(DownloadErrorKind.UNKNOWN, result.error.kind)
    }

    // ── TrackDownloadResult sealed class abstract url ─────────────────────

    @Test
    fun downloadedResultHasUrl() {
        val result = TrackDownloadResult.Downloaded(
            url = "url1", outputPath = "p", fileSize = null,
            title = null, artists = null, album = null,
            bitrate = null, durationMs = null
        )
        assertEquals("url1", result.url)
    }

    @Test
    fun skippedResultHasUrl() {
        val result = TrackDownloadResult.Skipped(
            url = "url2", outputPath = "p",
            title = null, artists = null, album = null, bitrate = null
        )
        assertEquals("url2", result.url)
    }

    @Test
    fun failureResultHasUrl() {
        val error = DownloadError(DownloadErrorKind.IO, "e")
        val result = TrackDownloadResult.Failure(url = "url3", error = error)
        assertEquals("url3", result.url)
    }
}
