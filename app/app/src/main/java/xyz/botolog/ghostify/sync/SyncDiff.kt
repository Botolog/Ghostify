package xyz.botolog.ghostify.sync

import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus
import xyz.botolog.ghostify.python.PlaylistTrack
import timber.log.Timber

/**
 * The pure re-sync diff (PROJECT.md section 6.2): given the current Spotify track list
 * and the stored song rows, decide exactly which rows to insert / update /
 * delete and which files to delete. No I/O, no side effects -- fully unit-testable.
 *
 * Rules:
 *  1. NEW -- `spotify_id` not stored, insert a row with `status = PENDING`
 *     (it will be downloaded) (T-056, T-064).
 *  2. RE-QUEUE -- stored and still in the playlist, but no usable file on disk
 *     (`file_path == null` or file missing), reset to `PENDING` so it is
 *     downloaded again (T-059, T-155).
 *  3. UNCHANGED -- stored, still present, file on disk, row untouched, status
 *     stays `DOWNLOADED`, the file is never touched (no re-download) (T-058).
 *  4. MOVED / RENAMED -- same `spotify_id`, only position/metadata updated,
 *     never treated as add/remove, never re-downloaded (T-060, T-061).
 *  5. REMOVED -- `spotify_id` no longer in the playlist, delete the local file
 *     (if any) and the row (T-057, T-063, T-065).
 *
 * In-flight rows (`QUEUED`/`DOWNLOADING`) are never touched and never enqueued:
 * a concurrent manual download owns them, and resetting them to `PENDING` would
 * let a second enqueue claim them, causing a duplicate download (T-068).
 */
object SyncDiff {

    /**
     * Computes the diff between remote tracks and locally stored songs.
     *
     * @param playlistId The playlist being synced.
     * @param remoteTracks The current ordered track list from the remote origin.
     * @param stored The currently persisted songs from the database.
     * @param files File-system accessor for checking file existence.
     * @param now Clock returning epoch millis (injectable for testing).
     * @param newId UUID generator (injectable for testing).
     * @return A [SyncPlan] describing all required mutations.
     */
    fun compute(
        playlistId: String,
        remoteTracks: List<PlaylistTrack>,
        stored: List<SongEntity>,
        files: LocalFileStore,
        now: () -> Long,
        newId: () -> String,
    ): SyncPlan {
        Timber.i(
            "SyncDiff.compute: START playlistId=$playlistId " +
                "remoteCount=${remoteTracks.size} storedCount=${stored.size}",
        )
        val storedById = stored.associate { it.spotifyId to it }
        val inserts = ArrayList<SongEntity>()
        val updates = ArrayList<SongEntity>()
        val enqueueIds = ArrayList<String>()

        classifyRemoteTracks(
            playlistId = playlistId,
            remoteTracks = remoteTracks,
            storedById = storedById,
            files = files,
            now = now,
            newId = newId,
            inserts = inserts,
            updates = updates,
            enqueueIds = enqueueIds,
        )

        val remoteIds = remoteTracks.mapTo(HashSet()) { it.spotifyId }
        val deleteSpotifyIds = ArrayList<String>()
        val deleteFilePaths = ArrayList<String>()
        collectRemovals(stored, remoteIds, deleteSpotifyIds, deleteFilePaths)

        val plan = SyncPlan(
            inserts = inserts,
            updates = updates,
            deleteSpotifyIds = deleteSpotifyIds,
            deleteFilePaths = deleteFilePaths,
            enqueueIds = enqueueIds,
            finalTrackCount = remoteTracks.size,
        )
        Timber.i(
            "SyncDiff.compute: returning plan(" +
                "inserts=${plan.inserts.size}, updates=${plan.updates.size}, " +
                "deletes=${plan.deleteSpotifyIds.size}, enqueues=${plan.enqueueIds.size})",
        )
        return plan
    }

    /**
     * Iterates remote tracks and classifies each as NEW, RE-QUEUE, UNCHANGED,
     * or MOVED/RENAMED, populating [inserts], [updates], and [enqueueIds].
     */
    private fun classifyRemoteTracks(
        playlistId: String,
        remoteTracks: List<PlaylistTrack>,
        storedById: Map<String, SongEntity>,
        files: LocalFileStore,
        now: () -> Long,
        newId: () -> String,
        inserts: MutableList<SongEntity>,
        updates: MutableList<SongEntity>,
        enqueueIds: MutableList<String>,
    ) {
        for (track in remoteTracks) {
            val existing = storedById[track.spotifyId]
            if (existing == null) {
                classifyNewTrack(playlistId, track, newId(), now(), inserts, enqueueIds)
            } else if (existing.isInFlight()) {
                // Never clobber a download in progress (T-068).
            } else {
                classifyExistingTrack(existing, track, files, updates, enqueueIds)
            }
        }
    }

    /**
     * Handles a brand-new track not present locally: creates a PENDING row
     * and marks it for download.
     */
    private fun classifyNewTrack(
        playlistId: String,
        track: PlaylistTrack,
        id: String,
        addedAt: Long,
        inserts: MutableList<SongEntity>,
        enqueueIds: MutableList<String>,
    ) {
        val song = track.toSong(playlistId, id, addedAt)
        inserts.add(song)
        enqueueIds.add(song.id)
    }

    /**
     * Handles a track that already exists locally: updates metadata/position
     * or re-queues if the file is missing.
     */
    private fun classifyExistingTrack(
        existing: SongEntity,
        track: PlaylistTrack,
        files: LocalFileStore,
        updates: MutableList<SongEntity>,
        enqueueIds: MutableList<String>,
    ) {
        val candidate = existing.applyMetadata(track)
        if (files.exists(existing.filePath)) {
            // File usable: only metadata/position may have changed.
            if (candidate != existing) updates.add(candidate)
        } else {
            // Re-queue: drop the stale path, reset to PENDING.
            val reset = candidate.copy(status = SongStatus.PENDING, filePath = null)
            if (reset != existing) updates.add(reset)
            enqueueIds.add(existing.id)
        }
    }

    /**
     * Finds stored songs no longer present in the remote list and collects
     * them for deletion.
     */
    private fun collectRemovals(
        stored: List<SongEntity>,
        remoteIds: HashSet<String>,
        deleteSpotifyIds: MutableList<String>,
        deleteFilePaths: MutableList<String>,
    ) {
        for (song in stored) {
            if (song.spotifyId !in remoteIds) {
                deleteSpotifyIds.add(song.spotifyId)
                // In-flight removed songs are still owned by the downloader
                // (they have no written file yet anyway) -- leave their disk
                // alone; an orphan is reclaimed by MusicStore.clearOrphans.
                if (!song.isInFlight() && !song.filePath.isNullOrBlank()) {
                    deleteFilePaths.add(song.filePath)
                }
            }
        }
    }

    /** Returns `true` when the song is currently owned by the download manager. */
    private fun SongEntity.isInFlight(): Boolean =
        status == SongStatus.DOWNLOADING || status == SongStatus.QUEUED

    /** Converts a remote [PlaylistTrack] into a new [SongEntity] with PENDING status. */
    private fun PlaylistTrack.toSong(playlistId: String, id: String, addedAt: Long): SongEntity =
        SongEntity(
            id = id,
            playlistId = playlistId,
            spotifyId = spotifyId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs.toInt(),
            coverUrl = coverUrl,
            ytId = ytId,
            filePath = null,
            status = SongStatus.PENDING,
            position = position,
            addedAt = addedAt,
        )

    /** Applies current remote metadata/position, preserving identity + file state. */
    private fun SongEntity.applyMetadata(track: PlaylistTrack): SongEntity = copy(
        title = track.title,
        artists = track.artists,
        album = track.album,
        durationMs = track.durationMs.toInt(),
        coverUrl = track.coverUrl,
        ytId = track.ytId,
        position = track.position,
    )
}

/**
 * The write plan produced by [SyncDiff.compute].
 *
 * [updates] mixes pure metadata/position changes with status resets (both are
 * UPDATE statements). The [hasChanges] property provides a quick check for
 * whether the plan contains any mutations.
 */
data class SyncPlan(
    val inserts: List<SongEntity> = emptyList(),
    val updates: List<SongEntity> = emptyList(),
    val deleteSpotifyIds: List<String> = emptyList(),
    val deleteFilePaths: List<String> = emptyList(),
    val enqueueIds: List<String> = emptyList(),
    val finalTrackCount: Int = 0,
) {
    /** Whether this plan contains any database mutations. */
    val hasChanges: Boolean
        get() = inserts.isNotEmpty() || updates.isNotEmpty() || deleteSpotifyIds.isNotEmpty()
}
