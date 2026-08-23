package xyz.botolog.ghostify.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueItemTest {

    private fun queueItem(
        songId: String = "s1",
        title: String? = "Title",
        artist: String? = "Artist",
        album: String? = "Album",
        durationMs: Long? = 100_000L,
        filePath: String = "/music/song.mp3",
        indexInQueue: Int = 0,
        coverUrl: String? = null,
    ) = QueueItem(
        songId = songId,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        indexInQueue = indexInQueue,
        coverUrl = coverUrl,
    )

    @Test
    fun mediaIdAliasReturnsSongId() {
        val item = queueItem(songId = "test-42")
        assertEquals(item.songId, item.mediaId)
        assertEquals("test-42", item.mediaId)
    }

    @Test
    fun coverUrlDefaultsToNull() {
        val item = queueItem()
        assertNull(item.coverUrl)
    }

    @Test
    fun coverUrlCanBeSet() {
        val item = queueItem(coverUrl = "https://example.com/cover.jpg")
        assertEquals("https://example.com/cover.jpg", item.coverUrl)
    }

    @Test
    fun nullableFieldsCanBeNull() {
        val item = queueItem(
            title = null,
            artist = null,
            album = null,
            durationMs = null,
            coverUrl = null,
        )
        assertNull(item.title)
        assertNull(item.artist)
        assertNull(item.album)
        assertNull(item.durationMs)
        assertNull(item.coverUrl)
    }

    @Test
    fun dataClassEqualityWorks() {
        val a = queueItem(songId = "s1", indexInQueue = 2)
        val b = queueItem(songId = "s1", indexInQueue = 2)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun dataClassCopyPreservesFields() {
        val original = queueItem(songId = "s1", title = "Old", indexInQueue = 3)
        val copied = original.copy(title = "New")
        assertEquals("New", copied.title)
        assertEquals("s1", copied.songId)
        assertEquals(3, copied.indexInQueue)
    }
}
