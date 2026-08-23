package xyz.botolog.ghostify.python

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.botolog.ghostify.data.model.PlaylistOrigin

class PlaylistMetadataTest {

    // ── PlaylistMetadata.fromMap ────────────────────────────────────────

    @Test
    fun fromMapParsesAllFields() {
        val map = mapOf(
            "name" to "My Playlist",
            "owner" to "Owner Name",
            "cover_url" to "https://example.com/cover.jpg",
            "description" to "A great playlist",
            "track_count" to 5,
            "origin" to "SPOTIFY",
            "tracks" to emptyList<Any?>(),
        )
        val metadata = PlaylistMetadata.fromMap(map)

        assertEquals("My Playlist", metadata.name)
        assertEquals("Owner Name", metadata.owner)
        assertEquals("https://example.com/cover.jpg", metadata.coverUrl)
        assertEquals("A great playlist", metadata.description)
        assertEquals(5, metadata.trackCount)
        assertEquals(PlaylistOrigin.SPOTIFY, metadata.origin)
        assertTrue(metadata.tracks.isEmpty())
    }

    @Test
    fun fromMapMissingOptionalFieldsUsesDefaults() {
        val map = mapOf<String, Any?>()
        val metadata = PlaylistMetadata.fromMap(map)

        assertEquals("", metadata.name)
        assertEquals("", metadata.owner)
        assertNull(metadata.coverUrl)
        assertNull(metadata.description)
        assertEquals(0, metadata.trackCount)
        assertEquals(PlaylistOrigin.SPOTIFY, metadata.origin)
        assertTrue(metadata.tracks.isEmpty())
    }

    @Test
    fun fromMapYouTubeOrigin() {
        val map = mapOf(
            "name" to "YT Playlist",
            "owner" to "Creator",
            "origin" to "YOUTUBE",
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(PlaylistOrigin.YOUTUBE, metadata.origin)
    }

    @Test
    fun fromMapUnknownOriginDefaultsToSpotify() {
        val map = mapOf(
            "name" to "Playlist",
            "owner" to "Owner",
            "origin" to "UNKNOWN",
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(PlaylistOrigin.SPOTIFY, metadata.origin)
    }

    @Test
    fun fromMapNullOriginDefaultsToSpotify() {
        val map = mapOf(
            "name" to "Playlist",
            "owner" to "Owner",
            "origin" to null,
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(PlaylistOrigin.SPOTIFY, metadata.origin)
    }

    @Test
    fun fromMapTrackCountFallsBackToTracksSize() {
        val trackMap1 = mapOf<String, Any?>("spotify_id" to "s1", "title" to "T1")
        val trackMap2 = mapOf<String, Any?>("spotify_id" to "s2", "title" to "T2")
        val map = mapOf(
            "name" to "P",
            "tracks" to listOf(trackMap1, trackMap2),
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(2, metadata.trackCount)
    }

    @Test
    fun fromMapExplicitTrackCountIsUsed() {
        val map = mapOf(
            "name" to "P",
            "track_count" to 42,
            "tracks" to emptyList<Any?>(),
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(42, metadata.trackCount)
    }

    @Test
    fun fromMapNonNumericTrackCountFallsBackToTracksSize() {
        val map = mapOf(
            "name" to "P",
            "track_count" to "not_a_number",
            "tracks" to emptyList<Any?>(),
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(0, metadata.trackCount)
    }

    @Test
    fun fromMapSkipsInvalidTrackEntries() {
        val tracks = listOf(
            mapOf<String, Any?>("spotify_id" to "s1", "title" to "Good"),
            "not_a_map",
            null,
            mapOf<String, Any?>("title" to "No Spotify ID"),
            mapOf<String, Any?>("spotify_id" to "s2", "title" to "Also Good"),
        )
        val map = mapOf(
            "name" to "P",
            "tracks" to tracks,
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertEquals(2, metadata.tracks.size)
        assertEquals("s1", metadata.tracks[0].spotifyId)
        assertEquals("s2", metadata.tracks[1].spotifyId)
    }

    @Test
    fun fromMapNullTracksList() {
        val map = mapOf(
            "name" to "P",
            "tracks" to null,
        )
        val metadata = PlaylistMetadata.fromMap(map)
        assertTrue(metadata.tracks.isEmpty())
    }

    // ── PlaylistMetadata data class ─────────────────────────────────────

    @Test
    fun playlistMetadataEquality() {
        val a = PlaylistMetadata("N", "O", null, null, 0, emptyList())
        val b = PlaylistMetadata("N", "O", null, null, 0, emptyList())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun playlistMetadataCopy() {
        val original = PlaylistMetadata("N", "O", null, null, 0, emptyList())
        val copied = original.copy(name = "New Name")
        assertEquals("New Name", copied.name)
        assertEquals("O", copied.owner)
    }

    @Test
    fun playlistMetadataDefaultOriginIsSpotify() {
        val metadata = PlaylistMetadata("N", "O", null, null, 0, emptyList())
        assertEquals(PlaylistOrigin.SPOTIFY, metadata.origin)
    }

    // ── PlaylistTrack.fromMap ───────────────────────────────────────────

    @Test
    fun trackFromMapParsesAllFields() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "spotify-123",
            "position" to 3,
            "title" to "Track Title",
            "artists" to "Artist A, Artist B",
            "album" to "Album Name",
            "duration_ms" to 210000,
            "cover_url" to "https://example.com/track.jpg",
            "yt_id" to "youtube-abc",
        )
        val track = PlaylistTrack.fromMap(map)!!

        assertEquals("spotify-123", track.spotifyId)
        assertEquals(3, track.position)
        assertEquals("Track Title", track.title)
        assertEquals("Artist A, Artist B", track.artists)
        assertEquals("Album Name", track.album)
        assertEquals(210000L, track.durationMs)
        assertEquals("https://example.com/track.jpg", track.coverUrl)
        assertEquals("youtube-abc", track.ytId)
    }

    @Test
    fun trackFromMapNullSpotifyIdReturnsNull() {
        val map = mapOf<String, Any?>(
            "title" to "No ID",
            "position" to 0,
        )
        assertNull(PlaylistTrack.fromMap(map))
    }

    @Test
    fun trackFromMapMissingOptionalFieldsUsesDefaults() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "s1",
        )
        val track = PlaylistTrack.fromMap(map)!!

        assertEquals("s1", track.spotifyId)
        assertEquals(0, track.position)
        assertEquals("", track.title)
        assertEquals("", track.artists)
        assertEquals("", track.album)
        assertEquals(0L, track.durationMs)
        assertNull(track.coverUrl)
        assertNull(track.ytId)
    }

    @Test
    fun trackFromMapNonNumericPositionDefaultsToZero() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "s1",
            "position" to "abc",
        )
        val track = PlaylistTrack.fromMap(map)!!
        assertEquals(0, track.position)
    }

    @Test
    fun trackFromMapNonNumericDurationDefaultsToZero() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "s1",
            "duration_ms" to "not_a_number",
        )
        val track = PlaylistTrack.fromMap(map)!!
        assertEquals(0L, track.durationMs)
    }

    @Test
    fun trackFromMapDoubleDurationTruncatedToLong() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "s1",
            "duration_ms" to 210000.7,
        )
        val track = PlaylistTrack.fromMap(map)!!
        assertEquals(210000L, track.durationMs)
    }

    @Test
    fun trackFromMapNegativePosition() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "s1",
            "position" to -1,
        )
        val track = PlaylistTrack.fromMap(map)!!
        assertEquals(-1, track.position)
    }

    @Test
    fun trackFromMapLargeValues() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "s1",
            "position" to 999999,
            "duration_ms" to 86_400_000L,
        )
        val track = PlaylistTrack.fromMap(map)!!
        assertEquals(999999, track.position)
        assertEquals(86_400_000L, track.durationMs)
    }

    @Test
    fun trackFromMapEmptyStrings() {
        val map = mapOf<String, Any?>(
            "spotify_id" to "",
            "title" to "",
            "artists" to "",
            "album" to "",
        )
        val track = PlaylistTrack.fromMap(map)!!
        assertEquals("", track.spotifyId)
        assertEquals("", track.title)
        assertEquals("", track.artists)
        assertEquals("", track.album)
    }

    // ── PlaylistTrack data class ────────────────────────────────────────

    @Test
    fun playlistTrackEquality() {
        val a = PlaylistTrack(0, "s1", "T", "A", "B", 100L, null, null)
        val b = PlaylistTrack(0, "s1", "T", "A", "B", 100L, null, null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun playlistTrackCopy() {
        val original = PlaylistTrack(0, "s1", "T", "A", "B", 100L, null, null)
        val copied = original.copy(position = 5, title = "New Title")
        assertEquals(5, copied.position)
        assertEquals("New Title", copied.title)
        assertEquals("s1", copied.spotifyId)
    }
}
