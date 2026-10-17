package xyz.botolog.ghostify.player

import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.botolog.ghostify.player.core.QueueItem

/**
 * Mapper must embed artwork bytes into `artworkData` when bytes are supplied, so the
 * MediaSession and the stock notification provider (which reads `artworkData`) can show art
 * for the current item. Callers pass bytes only for the currently-playing item; every other
 * item is built without art.
 */
class MediaItemMapperTest {

    private fun queueItem(id: String = "song-1") = QueueItem(
        songId = id,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 180_000L,
        filePath = "/music/$id.mp3",
        indexInQueue = 0,
    )

    @Test
    fun embedsArtworkDataWhenBytesProvided() {
        val art = byteArrayOf(1, 2, 3, 4)
        val item = MediaItemMapper.toMediaItem(queueItem(), art)
        assertArrayEquals(art, item.mediaMetadata.artworkData)
        assertEquals(MediaMetadata.PICTURE_TYPE_FRONT_COVER, item.mediaMetadata.artworkDataType)
    }

    @Test
    fun leavesArtworkNullWhenBytesMissing() {
        val item = MediaItemMapper.toMediaItem(queueItem(), null)
        assertNull(item.mediaMetadata.artworkData)
    }

    @Test
    fun preservesIdentityAndMetadata() {
        val item = MediaItemMapper.toMediaItem(queueItem("song-9"), byteArrayOf(9))
        assertEquals("song-9", item.mediaId)
        assertEquals("Title", item.mediaMetadata.title.toString())
        assertEquals("Artist", item.mediaMetadata.artist.toString())
        assertEquals("Album", item.mediaMetadata.albumTitle.toString())
    }
}
