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
            url = "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB",
            spotifyId = "4h9wh7iOZ0GGn8QVp4RAOB",
            title = "I Ain't Worried",
            artists = "OneRepublic",
            album = "I Ain't Worried (Music From The Motion Picture \"Top Gun: Maverick\")",
            durationMs = 148000L,
            coverUrl = "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB"
        )
        assertEquals("https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB", info.url)
        assertEquals("4h9wh7iOZ0GGn8QVp4RAOB", info.spotifyId)
        assertEquals("I Ain't Worried", info.title)
        assertEquals("OneRepublic", info.artists)
        assertEquals("I Ain't Worried (Music From The Motion Picture \"Top Gun: Maverick\")", info.album)
        assertEquals(148000L, info.durationMs)
        assertEquals("https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB", info.coverUrl)
    }

    @Test
    fun trackInfoFromMapParsesAllFields() {
        val map = mapOf(
            "url" to "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b",
            "spotify_id" to "1mea3bSkSGXuIRvnydlB5b",
            "title" to "Viva La Vida",
            "artists" to "Coldplay",
            "album" to "Viva La Vida or Death and All His Friends",
            "duration_ms" to 242000,
            "cover_url" to "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b"
        )
        val info = TrackInfo.fromMap(map)
        assertEquals("https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b", info.url)
        assertEquals("1mea3bSkSGXuIRvnydlB5b", info.spotifyId)
        assertEquals("Viva La Vida", info.title)
        assertEquals("Coldplay", info.artists)
        assertEquals("Viva La Vida or Death and All His Friends", info.album)
        assertEquals(242000L, info.durationMs)
        assertEquals("https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b", info.coverUrl)
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
            "url" to "https://open.spotify.com/track/2lNdc3jjPV4aWEfKH0gvH2",
            "output_path" to "/downloads/G-Eazy, Halsey - Him & I.mp3",
            "file_size" to 5000000,
            "title" to "Him & I",
            "artists" to "G-Eazy, Halsey",
            "album" to "The Beautiful & Damned (Deluxe Edition)",
            "bitrate" to "192",
            "duration_ms" to 268000
        )
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Downloaded)
        val downloaded = result as TrackDownloadResult.Downloaded
        assertEquals("https://open.spotify.com/track/2lNdc3jjPV4aWEfKH0gvH2", downloaded.url)
        assertEquals("/downloads/G-Eazy, Halsey - Him & I.mp3", downloaded.outputPath)
        assertEquals(5000000L, downloaded.fileSize)
        assertEquals("Him & I", downloaded.title)
        assertEquals("G-Eazy, Halsey", downloaded.artists)
        assertEquals("The Beautiful & Damned (Deluxe Edition)", downloaded.album)
        assertEquals("192", downloaded.bitrate)
        assertEquals(268000L, downloaded.durationMs)
    }

    @Test
    fun fromMapParsesSkippedStatus() {
        val map = mapOf(
            "status" to "SKIPPED",
            "url" to "https://open.spotify.com/track/0FDzzruyVECATHXKHFs9eJ",
            "output_path" to "/downloads/Coldplay - A Sky Full of Stars.mp3",
            "title" to "A Sky Full of Stars",
            "artists" to "Coldplay",
            "album" to "Ghost Stories",
            "bitrate" to "192"
        )
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Skipped)
        val skipped = result as TrackDownloadResult.Skipped
        assertEquals("https://open.spotify.com/track/0FDzzruyVECATHXKHFs9eJ", skipped.url)
        assertEquals("/downloads/Coldplay - A Sky Full of Stars.mp3", skipped.outputPath)
        assertEquals("A Sky Full of Stars", skipped.title)
    }

    @Test
    fun fromMapParsesFailedStatus() {
        val map = mapOf(
            "status" to "FAILED",
            "url" to "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB",
            "error_type" to "SEARCH_FAILED",
            "error" to "SEARCH_FAILED: no results found"
        )
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Failure)
        val failure = result as TrackDownloadResult.Failure
        assertEquals("https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB", failure.url)
        assertEquals(DownloadErrorKind.SEARCH_FAILED, failure.error.kind)
        assertEquals("no results found", failure.error.message)
        assertEquals("SEARCH_FAILED", failure.error.wireType)
    }

    @Test
    fun fromMapHandlesNullStatus() {
        val map = mapOf("url" to "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b")
        val result = TrackDownloadResult.fromMap(map)
        assertTrue(result is TrackDownloadResult.Failure)
        val failure = result as TrackDownloadResult.Failure
        assertEquals(DownloadErrorKind.UNKNOWN, failure.error.kind)
    }

    @Test
    fun fromMapHandlesUnrecognisedStatus() {
        val map = mapOf("status" to "PARTIAL", "url" to "https://open.spotify.com/track/2lNdc3jjPV4aWEfKH0gvH2")
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
            "url" to "https://open.spotify.com/track/0FDzzruyVECATHXKHFs9eJ"
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
            "url" to "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB"
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
            "url" to "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b"
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Failure
        assertEquals(DownloadErrorKind.UNKNOWN, result.error.kind)
    }

    // ── TrackDownloadResult sealed class abstract url ─────────────────────

    @Test
    fun downloadedResultHasUrl() {
        val result = TrackDownloadResult.Downloaded(
            url = "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB", outputPath = "p", fileSize = null,
            title = null, artists = null, album = null,
            bitrate = null, durationMs = null
        )
        assertEquals("https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB", result.url)
    }

    @Test
    fun skippedResultHasUrl() {
        val result = TrackDownloadResult.Skipped(
            url = "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b", outputPath = "p",
            title = null, artists = null, album = null, bitrate = null
        )
        assertEquals("https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b", result.url)
    }

    @Test
    fun failureResultHasUrl() {
        val error = DownloadError(DownloadErrorKind.IO, "e")
        val result = TrackDownloadResult.Failure(url = "https://open.spotify.com/track/2lNdc3jjPV4aWEfKH0gvH2", error = error)
        assertEquals("https://open.spotify.com/track/2lNdc3jjPV4aWEfKH0gvH2", result.url)
    }

    // ── Lyrics parsing ───────────────────────────────────────────────────

    @Test
    fun `downloaded with lyrics parses fromMap`() {
        val map = mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB",
            "output_path" to "/path/to/OneRepublic - I Ain't Worried.mp3",
            "title" to "I Ain't Worried",
            "artists" to "OneRepublic",
            "lyrics" to "La la la\nChorus line",
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Downloaded
        assertNotNull(result.lyrics)
        assertEquals("La la la\nChorus line", result.lyrics)
    }

    @Test
    fun `downloaded without lyrics is null fromMap`() {
        val map = mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b",
            "output_path" to "/path/to/Coldplay - Viva La Vida.mp3",
            "title" to "Viva La Vida",
            "artists" to "Coldplay",
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Downloaded
        assertNull(result.lyrics)
    }

    @Test
    fun `skipped with lyrics parses fromMap`() {
        val map = mapOf(
            "status" to "SKIPPED",
            "url" to "https://open.spotify.com/track/2lNdc3jjPV4aWEfKH0gvH2",
            "output_path" to "/path/to/G-Eazy, Halsey - Him & I.mp3",
            "title" to "Him & I",
            "artists" to "G-Eazy, Halsey",
            "lyrics" to "Skipped lyrics",
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Skipped
        assertNotNull(result.lyrics)
        assertEquals("Skipped lyrics", result.lyrics)
    }

    @Test
    fun `skipped without lyrics is null fromMap`() {
        val map = mapOf(
            "status" to "SKIPPED",
            "url" to "https://open.spotify.com/track/0FDzzruyVECATHXKHFs9eJ",
            "output_path" to "/path/to/Coldplay - A Sky Full of Stars.mp3",
            "title" to "A Sky Full of Stars",
            "artists" to "Coldplay",
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Skipped
        assertNull(result.lyrics)
    }

    @Test
    fun `downloaded with explicit null lyrics is null fromMap`() {
        val map = mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://open.spotify.com/track/4h9wh7iOZ0GGn8QVp4RAOB",
            "output_path" to "/path/to/OneRepublic - I Ain't Worried.mp3",
            "title" to "I Ain't Worried",
            "artists" to "OneRepublic",
            "lyrics" to null,
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Downloaded
        assertNull(result.lyrics)
    }

    @Test
    fun `downloaded with empty string lyrics preserves empty string`() {
        val map = mapOf(
            "status" to "DOWNLOADED",
            "url" to "https://open.spotify.com/track/1mea3bSkSGXuIRvnydlB5b",
            "output_path" to "/path/to/Coldplay - Viva La Vida.mp3",
            "title" to "Viva La Vida",
            "artists" to "Coldplay",
            "lyrics" to "",
        )
        val result = TrackDownloadResult.fromMap(map) as TrackDownloadResult.Downloaded
        assertNotNull(result.lyrics)
        assertEquals("", result.lyrics)
    }
}
