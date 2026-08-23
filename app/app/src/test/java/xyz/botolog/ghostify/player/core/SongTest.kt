package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTest {

    // ── SongStatus.fromValue ──────────────────────────────────────────────

    @Test
    fun fromValueReturnsMatchingStatusForAllKnownValues() {
        assertEquals(SongStatus.PENDING, SongStatus.fromValue("PENDING"))
        assertEquals(SongStatus.QUEUED, SongStatus.fromValue("QUEUED"))
        assertEquals(SongStatus.DOWNLOADING, SongStatus.fromValue("DOWNLOADING"))
        assertEquals(SongStatus.DOWNLOADED, SongStatus.fromValue("DOWNLOADED"))
        assertEquals(SongStatus.FAILED, SongStatus.fromValue("FAILED"))
        assertEquals(SongStatus.REMOVED, SongStatus.fromValue("REMOVED"))
    }

    @Test
    fun fromValueReturnsPendingForUnknownString() {
        assertEquals(SongStatus.PENDING, SongStatus.fromValue("UNKNOWN"))
        assertEquals(SongStatus.PENDING, SongStatus.fromValue(""))
        assertEquals(SongStatus.PENDING, SongStatus.fromValue("downloaded"))
        assertEquals(SongStatus.PENDING, SongStatus.fromValue("pending"))
    }

    @Test
    fun fromValueIsCaseSensitive() {
        assertEquals(SongStatus.PENDING, SongStatus.fromValue("PENDING"))
        assertEquals(SongStatus.PENDING, SongStatus.fromValue("Pending"))
    }

    @Test
    fun songStatusValuePropertyMatchesName() {
        SongStatus.entries.forEach { status ->
            assertEquals(status.name, status.value)
        }
    }

    // ── Song.isDownloaded ────────────────────────────────────────────────

    private fun song(
        status: SongStatus,
        filePath: String? = null,
    ) = Song(
        id = "1",
        title = "Title",
        artists = "Artist",
        album = "Album",
        durationMs = 1000L,
        filePath = filePath,
        status = status,
    )

    @Test
    fun isDownloadedTrueWhenStatusDownloadedAndValidPath() {
        val s = song(SongStatus.DOWNLOADED, filePath = "/music/song.mp3")
        assertTrue(s.isDownloaded)
    }

    @Test
    fun isDownloadedFalseWhenStatusDownloadedButPathIsNull() {
        val s = song(SongStatus.DOWNLOADED, filePath = null)
        assertFalse(s.isDownloaded)
    }

    @Test
    fun isDownloadedFalseWhenStatusDownloadedButPathIsBlank() {
        assertFalse(song(SongStatus.DOWNLOADED, filePath = "").isDownloaded)
        assertFalse(song(SongStatus.DOWNLOADED, filePath = "   ").isDownloaded)
    }

    @Test
    fun isDownloadedFalseForAllNonDownloadedStatuses() {
        listOf(
            SongStatus.PENDING,
            SongStatus.QUEUED,
            SongStatus.DOWNLOADING,
            SongStatus.FAILED,
            SongStatus.REMOVED,
        ).forEach { status ->
            assertFalse(
                "status=$status should not be downloaded",
                song(status, filePath = "/music/song.mp3").isDownloaded,
            )
        }
    }

    @Test
    fun isDownloadedFalseForNonDownloadedWithNullPath() {
        SongStatus.entries.filter { it != SongStatus.DOWNLOADED }.forEach { status ->
            assertFalse(song(status, filePath = null).isDownloaded)
        }
    }

    // ── Song data class ──────────────────────────────────────────────────

    @Test
    fun coverUrlDefaultsToNull() {
        val s = Song(
            id = "1", title = "T", artists = "A", album = "B",
            durationMs = null, filePath = null, status = SongStatus.PENDING,
        )
        assertEquals(null, s.coverUrl)
    }

    @Test
    fun dataClassEqualityWorks() {
        val a = Song("1", "T", "A", "B", 100L, "/f.mp3", SongStatus.DOWNLOADED, null)
        val b = Song("1", "T", "A", "B", 100L, "/f.mp3", SongStatus.DOWNLOADED, null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun dataClassCopyWorks() {
        val original = Song("1", "T", "A", "B", 100L, "/f.mp3", SongStatus.PENDING)
        val copied = original.copy(status = SongStatus.DOWNLOADED)
        assertEquals(SongStatus.DOWNLOADED, copied.status)
        assertEquals(original.id, copied.id)
    }
}
