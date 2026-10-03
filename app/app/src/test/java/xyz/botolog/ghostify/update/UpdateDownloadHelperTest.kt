package xyz.botolog.ghostify.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDownloadHelperTest {

    @Test
    fun progress_halfBytes_returnsHalf() {
        assertEquals(0.5f, UpdateDownloadHelper.progress(500L, 1000L), 0.001f)
    }

    @Test
    fun progress_zeroTotal_returnsZero() {
        assertEquals(0f, UpdateDownloadHelper.progress(500L, 0L), 0.001f)
    }

    @Test
    fun progress_nullTotal_returnsZero() {
        assertEquals(0f, UpdateDownloadHelper.progress(500L, null), 0.001f)
    }

    @Test
    fun progress_overTotal_clampedToOne() {
        assertEquals(1f, UpdateDownloadHelper.progress(1500L, 1000L), 0.001f)
    }

    @Test
    fun shouldResume_partialWithTotal_returnsTrue() {
        assertTrue(UpdateDownloadHelper.shouldResume(400L, 1000L))
    }

    @Test
    fun shouldResume_empty_returnsFalse() {
        assertFalse(UpdateDownloadHelper.shouldResume(0L, 1000L))
    }

    @Test
    fun shouldResume_complete_returnsFalse() {
        assertFalse(UpdateDownloadHelper.shouldResume(1000L, 1000L))
    }

    @Test
    fun shouldResume_overComplete_returnsFalse() {
        assertFalse(UpdateDownloadHelper.shouldResume(1200L, 1000L))
    }

    @Test
    fun shouldResume_unknownTotalWithBytes_returnsTrue() {
        assertTrue(UpdateDownloadHelper.shouldResume(400L, null))
    }

    @Test
    fun isComplete_matchingBytes_returnsTrue() {
        assertTrue(UpdateDownloadHelper.isComplete(1000L, 1000L))
    }

    @Test
    fun isComplete_partial_returnsFalse() {
        assertFalse(UpdateDownloadHelper.isComplete(400L, 1000L))
    }

    @Test
    fun isComplete_unknownTotal_returnsFalse() {
        assertFalse(UpdateDownloadHelper.isComplete(400L, null))
    }

    @Test
    fun isDuplicate_sameUrlActive_returnsTrue() {
        assertTrue(UpdateDownloadHelper.isDuplicate("https://example.com/a.apk", true, "https://example.com/a.apk"))
    }

    @Test
    fun isDuplicate_differentUrl_returnsFalse() {
        assertFalse(UpdateDownloadHelper.isDuplicate("https://example.com/a.apk", true, "https://example.com/b.apk"))
    }

    @Test
    fun isDuplicate_inactive_returnsFalse() {
        assertFalse(UpdateDownloadHelper.isDuplicate("https://example.com/a.apk", false, "https://example.com/a.apk"))
    }

    @Test
    fun rangeHeader_formatsCorrectly() {
        assertEquals("bytes=400-", UpdateDownloadHelper.rangeHeader(400L))
    }

    @Test
    fun parseTotalFromContentRange_parsesTotal() {
        assertEquals(1000L, UpdateDownloadHelper.parseTotalFromContentRange("bytes 400-999/1000", null))
    }

    @Test
    fun parseTotalFromContentRange_wildcard_returnsFallback() {
        assertEquals(800L, UpdateDownloadHelper.parseTotalFromContentRange("bytes */800", 800L))
    }

    @Test
    fun parseTotalFromContentRange_null_returnsFallback() {
        assertEquals(800L, UpdateDownloadHelper.parseTotalFromContentRange(null, 800L))
    }

    @Test
    fun resolveTotal_partialAddsOffset() {
        assertEquals(1000L, UpdateDownloadHelper.resolveTotal(400L, 600L, "bytes 400-999/1000"))
    }

    @Test
    fun resolveTotal_fullUsesContentLength() {
        assertEquals(1000L, UpdateDownloadHelper.resolveTotal(0L, 1000L, null))
    }

    @Test
    fun isZipMagic_validHeader_returnsTrue() {
        assertTrue(UpdateDownloadHelper.isZipMagic(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun isZipMagic_invalidHeader_returnsFalse() {
        assertFalse(UpdateDownloadHelper.isZipMagic(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun meta_roundTrip_preservesValues() {
        val raw = UpdateDownloadHelper.encodeMeta("https://example.com/a.apk", "1.2.3", 1000L)
        val meta = UpdateDownloadHelper.decodeMeta(raw)
        assertEquals("https://example.com/a.apk", meta?.url)
        assertEquals("1.2.3", meta?.versionName)
        assertEquals(1000L, meta?.totalBytes)
    }

    @Test
    fun meta_decodeEmpty_returnsNull() {
        assertEquals(null, UpdateDownloadHelper.decodeMeta(""))
        assertEquals(null, UpdateDownloadHelper.decodeMeta(null))
    }

    @Test
    fun metaMatches_sameUrlAndVersion_returnsTrue() {
        val meta = UpdateDownloadHelper.Meta("https://example.com/a.apk", "1.2.3", 1000L)
        assertTrue(UpdateDownloadHelper.metaMatches(meta, "https://example.com/a.apk", "1.2.3"))
    }

    @Test
    fun metaMatches_differentUrl_returnsFalse() {
        val meta = UpdateDownloadHelper.Meta("https://example.com/a.apk", "1.2.3", 1000L)
        assertFalse(UpdateDownloadHelper.metaMatches(meta, "https://example.com/b.apk", "1.2.3"))
    }

    @Test
    fun metaMatches_differentVersion_returnsFalse() {
        val meta = UpdateDownloadHelper.Meta("https://example.com/a.apk", "1.2.3", 1000L)
        assertFalse(UpdateDownloadHelper.metaMatches(meta, "https://example.com/a.apk", "1.2.4"))
    }
}
