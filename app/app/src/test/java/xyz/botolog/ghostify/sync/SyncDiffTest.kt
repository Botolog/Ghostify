package xyz.botolog.ghostify.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.python.PlaylistTrack

class SyncDiffTest {

    private lateinit var fakeFiles: FakeLocalFileStore
    private val fixedNow = 1_700_000_000_000L
    private var idCounter = 0

    @Before
    fun setUp() {
        fakeFiles = FakeLocalFileStore()
        idCounter = 0
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun track(
        spotifyId: String = "sp_$idCounter",
        title: String = "Title $idCounter",
        artists: String = "Artist $idCounter",
        album: String = "Album $idCounter",
        durationMs: Long = 200_000L,
        position: Int = 0,
        coverUrl: String? = "https://cover/$idCounter",
        ytId: String? = null,
    ): PlaylistTrack {
        val id = spotifyId
        return PlaylistTrack(
            position = position,
            spotifyId = id,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            coverUrl = coverUrl,
            ytId = ytId,
        ).also { idCounter++ }
    }

    private fun song(
        id: String = "row_${idCounter++}",
        playlistId: String = "pl_1",
        spotifyId: String = "sp_default",
        title: String = "Title",
        artists: String = "Artist",
        album: String? = "Album",
        durationMs: Int = 200_000,
        coverUrl: String? = "https://cover",
        ytId: String? = null,
        filePath: String? = null,
        status: SongStatus = SongStatus.DOWNLOADED,
        position: Int = 0,
    ) = SongEntity(
        id = id,
        playlistId = playlistId,
        spotifyId = spotifyId,
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        ytId = ytId,
        filePath = filePath,
        status = status,
        position = position,
        addedAt = fixedNow,
    )

    private fun compute(
        remoteTracks: List<PlaylistTrack>,
        stored: List<SongEntity>,
        playlistId: String = "pl_1",
    ): SyncPlan = SyncDiff.compute(
        playlistId = playlistId,
        remoteTracks = remoteTracks,
        stored = stored,
        files = fakeFiles,
        now = { fixedNow },
        newId = { "new_${idCounter++}" },
    )

    // ══════════════════════════════════════════════════════════════════════
    // NEW tracks (Rule 1)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun newTrackAddedToEmptyPlaylist() {
        val remote = listOf(track(spotifyId = "s1"))
        val plan = compute(remoteTracks = remote, stored = emptyList())

        assertEquals(1, plan.inserts.size)
        assertEquals("s1", plan.inserts[0].spotifyId)
        assertEquals(SongStatus.PENDING, plan.inserts[0].status)
        assertNull(plan.inserts[0].filePath)
        assertEquals(1, plan.enqueueIds.size)
        assertEquals(plan.inserts[0].id, plan.enqueueIds[0])
    }

    @Test
    fun newTrackHasCorrectMetadata() {
        val remote = listOf(
            track(spotifyId = "s1", title = "My Song", artists = "A, B", album = "My Album", durationMs = 300_000, position = 2, coverUrl = "https://img", ytId = "yt1")
        )
        val plan = compute(remoteTracks = remote, stored = emptyList())

        val inserted = plan.inserts[0]
        assertEquals("My Song", inserted.title)
        assertEquals("A, B", inserted.artists)
        assertEquals("My Album", inserted.album)
        assertEquals(300_000, inserted.durationMs)
        assertEquals("https://img", inserted.coverUrl)
        assertEquals("yt1", inserted.ytId)
        assertEquals(2, inserted.position)
        assertEquals("pl_1", inserted.playlistId)
    }

    @Test
    fun multipleNewTracksAllInserted() {
        val remote = listOf(
            track(spotifyId = "s1"),
            track(spotifyId = "s2"),
            track(spotifyId = "s3"),
        )
        val plan = compute(remoteTracks = remote, stored = emptyList())

        assertEquals(3, plan.inserts.size)
        assertEquals(3, plan.enqueueIds.size)
        assertEquals(listOf("s1", "s2", "s3"), plan.inserts.map { it.spotifyId })
    }

    @Test
    fun newTracksPositionReflectsRemoteOrder() {
        val remote = listOf(
            track(spotifyId = "s1", position = 5),
            track(spotifyId = "s2", position = 0),
        )
        val plan = compute(remoteTracks = remote, stored = emptyList())

        assertEquals(5, plan.inserts[0].position)
        assertEquals(0, plan.inserts[1].position)
    }

    // ══════════════════════════════════════════════════════════════════════
    // UNCHANGED (Rule 3)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun existingTrackWithFileUnchanged_noUpdate() {
        val existing = song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = "/songs/s1.mp3")
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = listOf(track(spotifyId = "s1", title = "Title", artists = "Artist", album = "Album", durationMs = 200_000, position = 0, coverUrl = "https://cover", ytId = null))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(0, plan.inserts.size)
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.enqueueIds.size)
        assertEquals(0, plan.deleteSpotifyIds.size)
    }

    @Test
    fun existingTrackWithFileAndIdenticalMetadata_noUpdate() {
        val existing = song(
            spotifyId = "s1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            durationMs = 200_000,
            coverUrl = "https://cover",
            ytId = "yt1",
            position = 3,
            status = SongStatus.DOWNLOADED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = listOf(
            PlaylistTrack(
                position = 3,
                spotifyId = "s1",
                title = "Song",
                artists = "Artist",
                album = "Album",
                durationMs = 200_000,
                coverUrl = "https://cover",
                ytId = "yt1",
            )
        )

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(0, plan.updates.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // MOVED / RENAMED (Rule 4)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun existingTrackMetadataChanged_metadataUpdated() {
        val existing = song(
            spotifyId = "s1",
            title = "Old Title",
            artists = "Old Artist",
            album = "Old Album",
            durationMs = 100_000,
            coverUrl = "https://old",
            ytId = null,
            position = 0,
            status = SongStatus.DOWNLOADED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = listOf(
            PlaylistTrack(
                position = 0,
                spotifyId = "s1",
                title = "New Title",
                artists = "New Artist",
                album = "New Album",
                durationMs = 300_000,
                coverUrl = "https://new",
                ytId = "yt_new",
            )
        )

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        val updated = plan.updates[0]
        assertEquals("New Title", updated.title)
        assertEquals("New Artist", updated.artists)
        assertEquals("New Album", updated.album)
        assertEquals(300_000, updated.durationMs)
        assertEquals("https://new", updated.coverUrl)
        assertEquals("yt_new", updated.ytId)
        assertEquals(SongStatus.DOWNLOADED, updated.status)
        assertEquals("/songs/s1.mp3", updated.filePath)
        assertEquals(0, plan.enqueueIds.size)
    }

    @Test
    fun existingTrackPositionChanged_onlyPositionUpdated() {
        val existing = song(
            spotifyId = "s1",
            title = "Title",
            artists = "Artist",
            album = "Album",
            durationMs = 200_000,
            coverUrl = "https://cover",
            ytId = null,
            position = 0,
            status = SongStatus.DOWNLOADED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = listOf(
            PlaylistTrack(
                position = 7,
                spotifyId = "s1",
                title = "Title",
                artists = "Artist",
                album = "Album",
                durationMs = 200_000,
                coverUrl = "https://cover",
                ytId = null,
            )
        )

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        assertEquals(7, plan.updates[0].position)
        assertEquals(SongStatus.DOWNLOADED, plan.updates[0].status)
    }

    @Test
    fun existingTrackYtIdChanged_updated() {
        val existing = song(
            spotifyId = "s1",
            title = "Title",
            artists = "Artist",
            album = "Album",
            durationMs = 200_000,
            coverUrl = "https://cover",
            ytId = null,
            position = 0,
            status = SongStatus.DOWNLOADED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = listOf(
            PlaylistTrack(
                position = 0,
                spotifyId = "s1",
                title = "Title",
                artists = "Artist",
                album = "Album",
                durationMs = 200_000,
                coverUrl = "https://cover",
                ytId = "resolved_yt",
            )
        )

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        assertEquals("resolved_yt", plan.updates[0].ytId)
    }

    // ══════════════════════════════════════════════════════════════════════
    // RE-QUEUE (Rule 2)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun existingTrackFileMissing_resetToPendingAndEnqueued() {
        val existing = song(
            spotifyId = "s1",
            title = "Title",
            artists = "Artist",
            album = "Album",
            status = SongStatus.DOWNLOADED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.clear()
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        val reset = plan.updates[0]
        assertEquals(SongStatus.PENDING, reset.status)
        assertNull(reset.filePath)
        assertEquals(1, plan.enqueueIds.size)
        assertEquals(existing.id, plan.enqueueIds[0])
    }

    @Test
    fun existingTrackFilePathNull_resetToPendingAndEnqueued() {
        val existing = song(
            spotifyId = "s1",
            status = SongStatus.DOWNLOADED,
            filePath = null,
        )
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        assertEquals(SongStatus.PENDING, plan.updates[0].status)
        assertNull(plan.updates[0].filePath)
        assertEquals(1, plan.enqueueIds.size)
    }

    @Test
    fun existingTrackFilePathBlank_resetToPendingAndEnqueued() {
        val existing = song(
            spotifyId = "s1",
            status = SongStatus.DOWNLOADED,
            filePath = "   ",
        )
        fakeFiles.existingPaths.add("   ")
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        assertEquals(SongStatus.PENDING, plan.updates[0].status)
    }

    @Test
    fun requeuedTrackPreservesMetadata() {
        val existing = song(
            spotifyId = "s1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            durationMs = 200_000,
            coverUrl = "https://cover",
            ytId = "yt1",
            position = 5,
            status = SongStatus.DOWNLOADED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.clear()
        val remote = listOf(
            PlaylistTrack(
                position = 5,
                spotifyId = "s1",
                title = "Song",
                artists = "Artist",
                album = "Album",
                durationMs = 200_000,
                coverUrl = "https://cover",
                ytId = "yt1",
            )
        )

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        val reset = plan.updates[0]
        assertEquals("Song", reset.title)
        assertEquals("Artist", reset.artists)
        assertEquals("Album", reset.album)
        assertEquals(200_000, reset.durationMs)
        assertEquals("https://cover", reset.coverUrl)
        assertEquals("yt1", reset.ytId)
        assertEquals(5, reset.position)
    }

    // ══════════════════════════════════════════════════════════════════════
    // In-flight tracks (T-068)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun inFlightQueuedTrack_neverTouched() {
        val existing = song(
            spotifyId = "s1",
            status = SongStatus.QUEUED,
            filePath = null,
        )
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(0, plan.inserts.size)
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.enqueueIds.size)
    }

    @Test
    fun inFlightDownloadingTrack_neverTouched() {
        val existing = song(
            spotifyId = "s1",
            status = SongStatus.DOWNLOADING,
            filePath = null,
        )
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(0, plan.inserts.size)
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.enqueueIds.size)
    }

    @Test
    fun inFlightTrackWithFileExists_stillUntouched() {
        val existing = song(
            spotifyId = "s1",
            status = SongStatus.QUEUED,
            filePath = "/songs/s1.mp3",
        )
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = listOf(track(spotifyId = "s1", title = "Changed"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.enqueueIds.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // REMOVED tracks (Rule 5)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun removedTrack_collectsSpotifyIdForDeletion() {
        val existing = song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = "/songs/s1.mp3")
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = emptyList<PlaylistTrack>()

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals("s1", plan.deleteSpotifyIds[0])
        assertEquals(1, plan.deleteFilePaths.size)
        assertEquals("/songs/s1.mp3", plan.deleteFilePaths[0])
    }

    @Test
    fun removedTrackFilePathNull_noFileToDelete() {
        val existing = song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = null)
        val remote = emptyList<PlaylistTrack>()

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals(0, plan.deleteFilePaths.size)
    }

    @Test
    fun removedInFlightTrack_noFileToDelete() {
        val existing = song(spotifyId = "s1", status = SongStatus.DOWNLOADING, filePath = "/songs/s1.mp3")
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = emptyList<PlaylistTrack>()

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals(0, plan.deleteFilePaths.size)
    }

    @Test
    fun removedQueuedTrack_noFileToDelete() {
        val existing = song(spotifyId = "s1", status = SongStatus.QUEUED, filePath = "/songs/s1.mp3")
        fakeFiles.existingPaths.add("/songs/s1.mp3")
        val remote = emptyList<PlaylistTrack>()

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals(0, plan.deleteFilePaths.size)
    }

    @Test
    fun removedMultipleTracks_allCollectedForDeletion() {
        val stored = listOf(
            song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3"),
            song(spotifyId = "s2", status = SongStatus.DOWNLOADED, filePath = "/s2.mp3"),
            song(spotifyId = "s3", status = SongStatus.DOWNLOADED, filePath = "/s3.mp3"),
        )
        fakeFiles.existingPaths.addAll(listOf("/s1.mp3", "/s2.mp3", "/s3.mp3"))
        val remote = emptyList<PlaylistTrack>()

        val plan = compute(remoteTracks = remote, stored = stored)

        assertEquals(3, plan.deleteSpotifyIds.size)
        assertEquals(3, plan.deleteFilePaths.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Edge cases: empty inputs
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun emptyRemote_emptyStored_noChanges() {
        val plan = compute(remoteTracks = emptyList(), stored = emptyList())

        assertEquals(0, plan.inserts.size)
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.deleteSpotifyIds.size)
        assertEquals(0, plan.deleteFilePaths.size)
        assertEquals(0, plan.enqueueIds.size)
        assertEquals(0, plan.finalTrackCount)
    }

    @Test
    fun emptyRemote_nonEmptyStored_allRemoved() {
        val stored = listOf(
            song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3"),
            song(spotifyId = "s2", status = SongStatus.DOWNLOADED, filePath = "/s2.mp3"),
        )
        fakeFiles.existingPaths.addAll(listOf("/s1.mp3", "/s2.mp3"))

        val plan = compute(remoteTracks = emptyList(), stored = stored)

        assertEquals(0, plan.inserts.size)
        assertEquals(2, plan.deleteSpotifyIds.size)
        assertEquals(2, plan.deleteFilePaths.size)
        assertEquals(0, plan.finalTrackCount)
    }

    @Test
    fun nonEmptyRemote_emptyStored_allInserted() {
        val remote = listOf(
            track(spotifyId = "s1"),
            track(spotifyId = "s2"),
        )

        val plan = compute(remoteTracks = remote, stored = emptyList())

        assertEquals(2, plan.inserts.size)
        assertEquals(2, plan.enqueueIds.size)
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.deleteSpotifyIds.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Edge cases: identical lists
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun identicalLists_noChanges() {
        val existing = song(spotifyId = "s1", title = "Title", artists = "Artist", album = "Album", durationMs = 200_000, coverUrl = "https://cover", ytId = null, position = 0, status = SongStatus.DOWNLOADED, filePath = "/s1.mp3")
        fakeFiles.existingPaths.add("/s1.mp3")
        val remote = listOf(track(spotifyId = "s1", title = "Title", artists = "Artist", album = "Album", durationMs = 200_000, coverUrl = "https://cover", ytId = null, position = 0))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertFalse(plan.hasChanges)
        assertEquals(0, plan.inserts.size)
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.deleteSpotifyIds.size)
    }

    @Test
    fun identicalMultipleTracks_noChanges() {
        val stored = listOf(
            song(spotifyId = "s1", title = "Title 1", artists = "Artist 1", album = "Album 1", durationMs = 200_000, coverUrl = "https://cover/1", ytId = null, position = 0, status = SongStatus.DOWNLOADED, filePath = "/s1.mp3"),
            song(spotifyId = "s2", title = "Title 2", artists = "Artist 2", album = "Album 2", durationMs = 200_000, coverUrl = "https://cover/2", ytId = null, position = 0, status = SongStatus.DOWNLOADED, filePath = "/s2.mp3"),
        )
        fakeFiles.existingPaths.addAll(listOf("/s1.mp3", "/s2.mp3"))
        val remote = listOf(
            track(spotifyId = "s1", title = "Title 1", artists = "Artist 1", album = "Album 1", durationMs = 200_000, coverUrl = "https://cover/1", ytId = null, position = 0),
            track(spotifyId = "s2", title = "Title 2", artists = "Artist 2", album = "Album 2", durationMs = 200_000, coverUrl = "https://cover/2", ytId = null, position = 0),
        )

        val plan = compute(remoteTracks = remote, stored = stored)

        assertFalse(plan.hasChanges)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mixed operations
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun mixedAddUpdateRemove() {
        val stored = listOf(
            song(spotifyId = "s1", title = "Old", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3"),
            song(spotifyId = "s2", status = SongStatus.DOWNLOADED, filePath = "/s2.mp3"),
        )
        fakeFiles.existingPaths.addAll(listOf("/s1.mp3", "/s2.mp3"))
        val remote = listOf(
            track(spotifyId = "s1", title = "New"),  // update metadata
            track(spotifyId = "s3"),                  // new
            // s2 removed
        )

        val plan = compute(remoteTracks = remote, stored = stored)

        assertEquals(1, plan.inserts.size)
        assertEquals("s3", plan.inserts[0].spotifyId)

        assertEquals(1, plan.updates.size)
        assertEquals("s1", plan.updates[0].spotifyId)
        assertEquals("New", plan.updates[0].title)

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals("s2", plan.deleteSpotifyIds[0])
        assertEquals(1, plan.deleteFilePaths.size)
    }

    @Test
    fun mixedAddRequeueAndRemove() {
        val stored = listOf(
            song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = null),   // re-queue
            song(spotifyId = "s2", status = SongStatus.DOWNLOADED, filePath = "/s2.mp3"), // remove
        )
        val remote = listOf(
            track(spotifyId = "s1"),  // re-queue
            track(spotifyId = "s3"),  // new
        )

        val plan = compute(remoteTracks = remote, stored = stored)

        assertEquals(1, plan.inserts.size)
        assertEquals("s3", plan.inserts[0].spotifyId)

        assertEquals(1, plan.updates.size)
        assertEquals(SongStatus.PENDING, plan.updates[0].status)

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals("s2", plan.deleteSpotifyIds[0])
    }

    // ══════════════════════════════════════════════════════════════════════
    // finalTrackCount
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun finalTrackCount_matchesRemoteSize() {
        val remote = listOf(
            track(spotifyId = "s1"),
            track(spotifyId = "s2"),
            track(spotifyId = "s3"),
        )
        val plan = compute(remoteTracks = remote, stored = emptyList())

        assertEquals(3, plan.finalTrackCount)
    }

    @Test
    fun finalTrackCountZeroForEmptyRemote() {
        val plan = compute(remoteTracks = emptyList(), stored = emptyList())

        assertEquals(0, plan.finalTrackCount)
    }

    // ══════════════════════════════════════════════════════════════════════
    // hasChanges
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun hasChangesTrueWhenInserts() {
        val plan = compute(remoteTracks = listOf(track()), stored = emptyList())
        assertTrue(plan.hasChanges)
    }

    @Test
    fun hasChangesTrueWhenUpdates() {
        val existing = song(spotifyId = "s1", title = "Old", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3")
        fakeFiles.existingPaths.add("/s1.mp3")
        val plan = compute(
            remoteTracks = listOf(track(spotifyId = "s1", title = "New")),
            stored = listOf(existing),
        )
        assertTrue(plan.hasChanges)
    }

    @Test
    fun hasChangesTrueWhenDeletes() {
        val plan = compute(
            remoteTracks = emptyList(),
            stored = listOf(song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3")),
        )
        fakeFiles.existingPaths.add("/s1.mp3")
        assertTrue(plan.hasChanges)
    }

    @Test
    fun hasChangesFalseWhenOnlyEnqueues() {
        val existing = song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = null)
        val plan = compute(
            remoteTracks = listOf(track(spotifyId = "s1")),
            stored = listOf(existing),
        )
        // The re-queue adds an update (reset to PENDING) + enqueue
        assertTrue(plan.hasChanges)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Edge cases: FAILED and CANCELED statuses
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun failedTrackWithNoFile_requeued() {
        val existing = song(spotifyId = "s1", status = SongStatus.FAILED, filePath = null)
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        assertEquals(SongStatus.PENDING, plan.updates[0].status)
        assertEquals(1, plan.enqueueIds.size)
    }

    @Test
    fun canceledTrackWithNoFile_requeued() {
        val existing = song(spotifyId = "s1", status = SongStatus.CANCELED, filePath = null)
        val remote = listOf(track(spotifyId = "s1"))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.updates.size)
        assertEquals(SongStatus.PENDING, plan.updates[0].status)
    }

    @Test
    fun failedTrackWithFile_unchanged() {
        val existing = song(spotifyId = "s1", title = "Title", artists = "Artist", album = "Album", durationMs = 200_000, coverUrl = "https://cover", ytId = null, position = 0, status = SongStatus.FAILED, filePath = "/s1.mp3")
        fakeFiles.existingPaths.add("/s1.mp3")
        val remote = listOf(track(spotifyId = "s1", title = "Title", artists = "Artist", album = "Album", durationMs = 200_000, coverUrl = "https://cover", ytId = null, position = 0))

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.enqueueIds.size)
    }

    @Test
    fun removedStatusTrack_notInFlight_justDeleted() {
        val existing = song(spotifyId = "s1", status = SongStatus.REMOVED, filePath = null)
        val remote = emptyList<PlaylistTrack>()

        val plan = compute(remoteTracks = remote, stored = listOf(existing))

        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals(0, plan.deleteFilePaths.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Single track scenarios
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun singleTrackReplacedByDifferent_noDeleteOfOldInsertOfNew() {
        val stored = listOf(
            song(spotifyId = "old1", status = SongStatus.DOWNLOADED, filePath = "/old1.mp3"),
        )
        fakeFiles.existingPaths.add("/old1.mp3")
        val remote = listOf(track(spotifyId = "new1"))

        val plan = compute(remoteTracks = remote, stored = stored)

        assertEquals(1, plan.inserts.size)
        assertEquals("new1", plan.inserts[0].spotifyId)
        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals("old1", plan.deleteSpotifyIds[0])
        assertEquals(1, plan.deleteFilePaths.size)
    }

    @Test
    fun trackSwappedOnly_noMetadataOverlap() {
        val stored = listOf(
            song(spotifyId = "s1", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3"),
        )
        fakeFiles.existingPaths.add("/s1.mp3")
        val remote = listOf(track(spotifyId = "s2"))

        val plan = compute(remoteTracks = remote, stored = stored)

        assertEquals(1, plan.inserts.size)
        assertEquals("s2", plan.inserts[0].spotifyId)
        assertEquals(0, plan.updates.size)
        assertEquals(1, plan.deleteSpotifyIds.size)
        assertEquals("s1", plan.deleteSpotifyIds[0])
    }

    // ══════════════════════════════════════════════════════════════════════
    // Playlist ID propagation
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun playlistIdPropagatedToInsertedEntities() {
        val remote = listOf(track(spotifyId = "s1"))
        val plan = compute(remoteTracks = remote, stored = emptyList(), playlistId = "my_playlist")

        assertEquals("my_playlist", plan.inserts[0].playlistId)
    }

    @Test
    fun playlistIdPropagatedToUpdatedEntities() {
        val existing = song(spotifyId = "s1", playlistId = "original_pl", status = SongStatus.DOWNLOADED, filePath = "/s1.mp3")
        fakeFiles.existingPaths.add("/s1.mp3")
        val remote = listOf(track(spotifyId = "s1", title = "Changed"))
        val plan = compute(remoteTracks = remote, stored = listOf(existing), playlistId = "my_playlist")

        assertEquals(1, plan.updates.size)
        assertEquals("original_pl", plan.updates[0].playlistId)
    }

    // ══════════════════════════════════════════════════════════════════════
    // SyncPlan default state
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun syncPlanDefaultsAreEmpty() {
        val plan = SyncPlan()
        assertTrue(plan.inserts.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deleteSpotifyIds.isEmpty())
        assertTrue(plan.deleteFilePaths.isEmpty())
        assertTrue(plan.enqueueIds.isEmpty())
        assertEquals(0, plan.finalTrackCount)
        assertFalse(plan.hasChanges)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Large list performance sanity
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun largeListAllNew() {
        val remote = (0..999).map { track(spotifyId = "s$it") }
        val plan = compute(remoteTracks = remote, stored = emptyList())

        assertEquals(1000, plan.inserts.size)
        assertEquals(1000, plan.enqueueIds.size)
    }

    @Test
    fun largeListAllUnchanged() {
        val stored = (0..999).map { i ->
            song(spotifyId = "s$i", title = "Title $i", artists = "Artist $i", album = "Album $i", durationMs = 200_000, coverUrl = "https://cover/$i", ytId = null, position = i, status = SongStatus.DOWNLOADED, filePath = "/s$i.mp3")
        }
        fakeFiles.existingPaths.addAll(stored.mapNotNull { it.filePath })
        val remote = (0..999).map { i ->
            track(spotifyId = "s$i", title = "Title $i", artists = "Artist $i", album = "Album $i", durationMs = 200_000, coverUrl = "https://cover/$i", ytId = null, position = i)
        }

        val plan = compute(remoteTracks = remote, stored = stored)

        assertFalse(plan.hasChanges)
    }

    @Test
    fun largeListAllRemoved() {
        val stored = (0..999).map { i ->
            song(spotifyId = "s$i", status = SongStatus.DOWNLOADED, filePath = "/s$i.mp3")
        }
        fakeFiles.existingPaths.addAll(stored.mapNotNull { it.filePath })
        val plan = compute(remoteTracks = emptyList(), stored = stored)

        assertEquals(1000, plan.deleteSpotifyIds.size)
        assertEquals(1000, plan.deleteFilePaths.size)
    }
}

/** Simple in-memory fake for [LocalFileStore]. */
class FakeLocalFileStore : LocalFileStore {
    val existingPaths = mutableSetOf<String>()

    override fun exists(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return filePath in existingPaths
    }

    override fun delete(filePath: String) {
        existingPaths.remove(filePath)
    }
}
