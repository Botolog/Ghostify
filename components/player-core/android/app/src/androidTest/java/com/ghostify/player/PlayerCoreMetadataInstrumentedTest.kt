package com.ghostify.player

import com.ghostify.player.core.SongStatus
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T-090 / T-091 — now-playing metadata and album art extracted from file tags.
 */
class PlayerCoreMetadataInstrumentedTest : PlayerCoreInstrumentedTest() {

    private lateinit var songWithArtwork: File

    @Before
    fun setUp() {
        val jpeg = TestAudioFactory.jpeg(context)
        songWithArtwork = TestAudioFactory.mp3WithArtwork(
            context = context,
            name = "tagged.mp3",
            title = "Ghost Of You",
            artist = "The Instruments",
            album = "Apparitions",
            artworkJpeg = jpeg,
        )
        createController()
    }

    @Test
    fun `T-090 now-playing metadata shows title, artist and album`() {
        play(
            listOf(
                SongFixture.downloaded("m1", songWithArtwork, "Ghost Of You", "The Instruments", "Apparitions"),
            ),
        )

        val state = awaitState { it.isPlaying && it.currentItem?.title != null }
        assertEquals("Ghost Of You", state.currentItem?.title)
        assertEquals("The Instruments", state.currentItem?.artist)
        assertEquals("Apparitions", state.currentItem?.album)
        assertEquals("m1", state.currentItem?.mediaId)
    }

    @Test
    fun `T-091 album art embedded in the mp3 tags is extracted and exposed`() {
        play(listOf(SongFixture.downloaded("m1", songWithArtwork, "Ghost Of You", "The Instruments", "Apparitions")))

        val state = awaitState { it.isPlaying && it.currentItem?.artworkBytes != null }
        val art = state.currentItem?.artworkBytes
        assertNotNull(art)
        assertTrue("artwork should not be empty", art!!.isNotEmpty())
        // JPEG magic bytes: FF D8 FF.
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            art.copyOf(3),
        )
    }
}

/** Small fixture helpers so Song construction stays in one place for this file. */
private object SongFixture {
    fun downloaded(
        id: String,
        file: File,
        title: String,
        artist: String,
        album: String,
    ) = com.ghostify.player.core.Song(
        id = id,
        title = title,
        artists = artist,
        album = album,
        durationMs = null,
        filePath = file.absolutePath,
        status = SongStatus.DOWNLOADED,
    )
}
