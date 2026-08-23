package xyz.botolog.ghostify.trackdownload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.download.DownloadStatus
import xyz.botolog.ghostify.download.SongRecord

/**
 * JVM unit tests for [buildMetaPayload] — the Kotlin→Python `meta` wire
 * contract (flat map of primitives; see MetaPayload.kt).
 */
class MetaPayloadTest {

    // ── Happy path ─────────────────────────────────────────────────

    @Test
    fun `happy path maps all contract fields`() {
        val meta = buildMetaPayload(
            song(
                title = "Neon Skyline",
                artists = "Artist A, Artist B",
                album = "Midnight Drive",
                durationMs = 213_000,
                coverUrl = "https://example.com/cover.jpg",
            )
        )

        assertEquals("Neon Skyline", meta["name"])
        assertEquals(arrayListOf("Artist A", "Artist B"), meta["artists"])
        assertEquals("Midnight Drive", meta["album"])
        assertEquals(213.0, meta["duration_sec"])
        assertEquals("https://example.com/cover.jpg", meta["image_url"])
    }

    @Test
    fun `duration_ms converts to seconds`() {
        val meta = buildMetaPayload(song(durationMs = 201_234))
        assertEquals(201.234, meta["duration_sec"])
    }

    @Test
    fun `artists value is a java util ArrayList`() {
        val meta = buildMetaPayload(song(artists = "Artist A, Artist B"))
        val artists = meta[KEY_ARTISTS]
        assertTrue("Expected ArrayList but was ${artists?.javaClass}", artists is ArrayList<*>)
    }

    @Test
    fun `multi artist splits on the real join separator`() {
        val meta = buildMetaPayload(
            song(artists = listOf("Artist A", "Artist B", "Artist C").joinToString(ARTIST_JOIN_SEPARATOR))
        )
        assertEquals(arrayListOf("Artist A", "Artist B", "Artist C"), meta[KEY_ARTISTS])
    }

    @Test
    fun `single artist without separator becomes one-element list`() {
        val meta = buildMetaPayload(song(artists = "Artist A"))
        assertEquals(arrayListOf("Artist A"), meta[KEY_ARTISTS])
    }

    // ── Unknown-value fallbacks ────────────────────────────────────

    @Test
    fun `null coverUrl maps to null image_url`() {
        val meta = buildMetaPayload(song(coverUrl = null))
        assertTrue(meta.containsKey(KEY_IMAGE_URL))
        assertNull(meta[KEY_IMAGE_URL])
    }

    @Test
    fun `blank coverUrl maps to null image_url`() {
        val meta = buildMetaPayload(song(coverUrl = "   "))
        assertNull(meta[KEY_IMAGE_URL])
    }

    @Test
    fun `null album maps to empty string`() {
        val meta = buildMetaPayload(song(album = null))
        assertEquals("", meta[KEY_ALBUM])
    }

    @Test
    fun `blank album maps to empty string`() {
        val meta = buildMetaPayload(song(album = "  "))
        assertEquals("", meta[KEY_ALBUM])
    }

    @Test
    fun `zero durationMs maps to zero seconds`() {
        val meta = buildMetaPayload(song(durationMs = 0))
        assertEquals(0.0, meta[KEY_DURATION_SEC])
    }

    // ── Wire shape ─────────────────────────────────────────────────

    @Test
    fun `map contains exactly the five contract keys`() {
        val meta = buildMetaPayload(song())
        assertEquals(
            setOf("name", "artists", "album", "duration_sec", "image_url"),
            meta.keys,
        )
    }

    // ── Fixtures ───────────────────────────────────────────────────

    private companion object {
        const val KEY_ARTISTS = "artists"
        const val KEY_ALBUM = "album"
        const val KEY_DURATION_SEC = "duration_sec"
        const val KEY_IMAGE_URL = "image_url"
    }

    private fun song(
        title: String = "Title",
        artists: String = "Artist",
        album: String? = "Album",
        durationMs: Long = 200_000,
        coverUrl: String? = "https://example.com/cover.jpg",
    ): SongRecord = SongRecord(
        id = "s1",
        playlistId = "pl1",
        spotifyId = "sp1",
        title = title,
        artists = artists,
        position = 0,
        status = DownloadStatus.PENDING,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
    )
}
