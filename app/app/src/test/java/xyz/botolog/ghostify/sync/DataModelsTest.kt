package xyz.botolog.ghostify.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import xyz.botolog.ghostify.data.model.PlaylistOrigin
import xyz.botolog.ghostify.data.model.PlaylistStatus
import xyz.botolog.ghostify.data.model.SongStatus

class DataModelsTest {

    // ══════════════════════════════════════════════════════════════════════
    // SongStatus
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun songStatusHasSevenValues() {
        assertEquals(7, SongStatus.values().size)
    }

    @Test
    fun songStatusValuesAreDistinct() {
        val values = SongStatus.values().toList()
        assertEquals(values.toSet().size, values.size)
    }

    @Test
    fun songStatusContainsAllExpectedValues() {
        assertEquals(SongStatus.PENDING, SongStatus.valueOf("PENDING"))
        assertEquals(SongStatus.QUEUED, SongStatus.valueOf("QUEUED"))
        assertEquals(SongStatus.DOWNLOADING, SongStatus.valueOf("DOWNLOADING"))
        assertEquals(SongStatus.DOWNLOADED, SongStatus.valueOf("DOWNLOADED"))
        assertEquals(SongStatus.FAILED, SongStatus.valueOf("FAILED"))
        assertEquals(SongStatus.CANCELED, SongStatus.valueOf("CANCELED"))
        assertEquals(SongStatus.REMOVED, SongStatus.valueOf("REMOVED"))
    }

    @Test
    fun songStatusNameMatchesExpected() {
        assertEquals("PENDING", SongStatus.PENDING.name)
        assertEquals("QUEUED", SongStatus.QUEUED.name)
        assertEquals("DOWNLOADING", SongStatus.DOWNLOADING.name)
        assertEquals("DOWNLOADED", SongStatus.DOWNLOADED.name)
        assertEquals("FAILED", SongStatus.FAILED.name)
        assertEquals("CANCELED", SongStatus.CANCELED.name)
        assertEquals("REMOVED", SongStatus.REMOVED.name)
    }

    @Test
    fun songStatusOrdinalMatchesDefinitionOrder() {
        assertEquals(0, SongStatus.PENDING.ordinal)
        assertEquals(1, SongStatus.QUEUED.ordinal)
        assertEquals(2, SongStatus.DOWNLOADING.ordinal)
        assertEquals(3, SongStatus.DOWNLOADED.ordinal)
        assertEquals(4, SongStatus.FAILED.ordinal)
        assertEquals(5, SongStatus.CANCELED.ordinal)
        assertEquals(6, SongStatus.REMOVED.ordinal)
    }

    @Test
    fun songStatusEnumEquality() {
        assertEquals(SongStatus.PENDING, SongStatus.PENDING)
        assertNotEquals(SongStatus.PENDING, SongStatus.QUEUED)
    }

    // ══════════════════════════════════════════════════════════════════════
    // PlaylistStatus
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun playlistStatusHasFourValues() {
        assertEquals(4, PlaylistStatus.values().size)
    }

    @Test
    fun playlistStatusValuesAreDistinct() {
        val values = PlaylistStatus.values().toList()
        assertEquals(values.toSet().size, values.size)
    }

    @Test
    fun playlistStatusContainsAllExpectedValues() {
        assertEquals(PlaylistStatus.NEW, PlaylistStatus.valueOf("NEW"))
        assertEquals(PlaylistStatus.READY, PlaylistStatus.valueOf("READY"))
        assertEquals(PlaylistStatus.DOWNLOADING, PlaylistStatus.valueOf("DOWNLOADING"))
        assertEquals(PlaylistStatus.ERROR, PlaylistStatus.valueOf("ERROR"))
    }

    @Test
    fun playlistStatusNameMatchesExpected() {
        assertEquals("NEW", PlaylistStatus.NEW.name)
        assertEquals("READY", PlaylistStatus.READY.name)
        assertEquals("DOWNLOADING", PlaylistStatus.DOWNLOADING.name)
        assertEquals("ERROR", PlaylistStatus.ERROR.name)
    }

    @Test
    fun playlistStatusOrdinalMatchesDefinitionOrder() {
        assertEquals(0, PlaylistStatus.NEW.ordinal)
        assertEquals(1, PlaylistStatus.READY.ordinal)
        assertEquals(2, PlaylistStatus.DOWNLOADING.ordinal)
        assertEquals(3, PlaylistStatus.ERROR.ordinal)
    }

    @Test
    fun playlistStatusEnumEquality() {
        assertEquals(PlaylistStatus.NEW, PlaylistStatus.NEW)
        assertNotEquals(PlaylistStatus.NEW, PlaylistStatus.READY)
    }

    // ══════════════════════════════════════════════════════════════════════
    // PlaylistOrigin
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun playlistOriginHasTwoValues() {
        assertEquals(2, PlaylistOrigin.values().size)
    }

    @Test
    fun playlistOriginValuesAreDistinct() {
        val values = PlaylistOrigin.values().toList()
        assertEquals(values.toSet().size, values.size)
    }

    @Test
    fun playlistOriginContainsAllExpectedValues() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.valueOf("SPOTIFY"))
        assertEquals(PlaylistOrigin.YOUTUBE, PlaylistOrigin.valueOf("YOUTUBE"))
    }

    @Test
    fun playlistOriginNameMatchesExpected() {
        assertEquals("SPOTIFY", PlaylistOrigin.SPOTIFY.name)
        assertEquals("YOUTUBE", PlaylistOrigin.YOUTUBE.name)
    }

    @Test
    fun playlistOriginOrdinalMatchesDefinitionOrder() {
        assertEquals(0, PlaylistOrigin.SPOTIFY.ordinal)
        assertEquals(1, PlaylistOrigin.YOUTUBE.ordinal)
    }

    @Test
    fun playlistOriginEnumEquality() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.SPOTIFY)
        assertNotEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.YOUTUBE)
    }

    // ── PlaylistOrigin.fromWire ──────────────────────────────────────────

    @Test
    fun fromWireReturnsYoutubeForYoutubeString() {
        assertEquals(PlaylistOrigin.YOUTUBE, PlaylistOrigin.fromWire("YOUTUBE"))
    }

    @Test
    fun fromWireReturnsSpotifyForSpotifyString() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("SPOTIFY"))
    }

    @Test
    fun fromWireReturnsSpotifyForNull() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire(null))
    }

    @Test
    fun fromWireReturnsSpotifyForEmptyString() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire(""))
    }

    @Test
    fun fromWireReturnsSpotifyForUnknownValue() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("TIDAL"))
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("apple_music"))
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("yOutube"))
    }

    @Test
    fun fromWireReturnsSpotifyForLowercaseYoutube() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("youtube"))
    }

    @Test
    fun fromWireIsCaseSensitive() {
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("Youtube"))
        assertEquals(PlaylistOrigin.SPOTIFY, PlaylistOrigin.fromWire("YOUTUBE_2"))
        assertEquals(PlaylistOrigin.YOUTUBE, PlaylistOrigin.fromWire("YOUTUBE"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cross-enum comparison
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun differentEnumsAreNotEqual() {
        assertNotEquals(SongStatus.PENDING as Any, PlaylistStatus.NEW as Any)
        assertNotEquals(SongStatus.DOWNLOADED as Any, PlaylistOrigin.SPOTIFY as Any)
    }

    @Test
    fun enumToStringReturnsName() {
        assertEquals("PENDING", SongStatus.PENDING.toString())
        assertEquals("NEW", PlaylistStatus.NEW.toString())
        assertEquals("SPOTIFY", PlaylistOrigin.SPOTIFY.toString())
    }
}
