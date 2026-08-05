package com.ghostify.sync

import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus
import com.ghostify.python.PlaylistTrack
import timber.log.Timber

/**
 * The pure re-sync diff (PROJECT.md §6.2): given the current Spotify track list
 * and the stored song rows, decide exactly which rows to insert / update /
 * delete and which files to delete. No I/O, no side effects — fully unit-testable.
 *
 * Rules:
 *  1. NEW — `spotify_id` not stored → insert a row with `status = PENDING`
 *     (it will be downloaded) (T-056, T-064).
 *  2. RE-QUEUE — stored and still in the playlist, but no usable file on disk
 *     (`file_path == null` or file missing) → reset to `PENDING` so it is
 *     downloaded again (T-059, T-155).
 *  3. UNCHANGED — stored, still present, file on disk → row untouched, status
 *     stays `DOWNLOADED`, the file is never touched (no re-download) (T-058).
 *  4. MOVED / RENAMED — same `spotify_id` → only position/metadata updated,
 *     never treated as add/remove, never re-downloaded (T-060, T-061).
 *  5. REMOVED — `spotify_id` no longer in the playlist → delete the local file
 *     (if any) and the row (T-057, T-063, T-065).
 *
 * In-flight rows (`QUEUED`/`DOWNLOADING`) are never touched and never enqueued:
 * a concurrent manual download owns them, and resetting them to `PENDING` would
 * let a second enqueue claim them → duplicate download (T-068).
 */
object SyncDiff {

    fun compute(
        playlistId: String,
        remoteTracks: List<PlaylistTrack>,
        stored: List<SongEntity>,
        files: LocalFileStore,
        now: () -> Long,
        newId: () -> String,
    ): SyncPlan {
        Timber.i("SyncDiff.compute: START playlistId=$playlistId remoteCount=${remoteTracks.size} storedCount=${stored.size}")
        val storedById = stored.associate { it.spotifyId to it }
        val inserts = ArrayList<SongEntity>()
        val updates = ArrayList<SongEntity>()
        val enqueueIds = ArrayList<String>()

        // 1) New tracks + 2) re-queue + 3) untouched + 4) moved/renamed.
        for (track in remoteTracks) {
            val existing = storedById[track.spotifyId]
            if (existing == null) {
                val song = track.toSong(playlistId, newId(), now())
                inserts.add(song)
                enqueueIds.add(song.id)
            } else if (existing.isInFlight()) {
                // Never clobber a download in progress (T-068).
            } else {
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
        }

        // 5) Removed tracks.
        val currentIds = remoteTracks.mapTo(HashSet()) { it.spotifyId }
        val deleteSpotifyIds = ArrayList<String>()
        val deleteFilePaths = ArrayList<String>()
        for (song in stored) {
            if (song.spotifyId !in currentIds) {
                deleteSpotifyIds.add(song.spotifyId)
                // In-flight removed songs are still owned by the downloader
                // (they have no written file yet anyway) — leave their disk
                // alone; an orphan is reclaimed by MusicStore.clearOrphans.
                if (!song.isInFlight() && !song.filePath.isNullOrBlank()) {
                    deleteFilePaths.add(song.filePath)
                }
            }
        }

        val plan = SyncPlan(
            inserts = inserts,
            updates = updates,
            deleteSpotifyIds = deleteSpotifyIds,
            deleteFilePaths = deleteFilePaths,
            enqueueIds = enqueueIds,
            finalTrackCount = remoteTracks.size,
        )
        Timber.i("SyncDiff.compute: returning plan(inserts=${plan.inserts.size}, updates=${plan.updates.size}, deletes=${plan.deleteSpotifyIds.size}, enqueues=${plan.enqueueIds.size})")
        return plan
    }

    private fun SongEntity.isInFlight(): Boolean =
        status == SongStatus.DOWNLOADING || status == SongStatus.QUEUED

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
 * The write plan produced by [SyncDiff.compute]. `updates` mixes pure
 * metadata/position changes with status resets (both are UPDATE statements).
 */
data class SyncPlan(
    val inserts: List<SongEntity> = emptyList(),
    val updates: List<SongEntity> = emptyList(),
    val deleteSpotifyIds: List<String> = emptyList(),
    val deleteFilePaths: List<String> = emptyList(),
    val enqueueIds: List<String> = emptyList(),
    val finalTrackCount: Int = 0,
) {
    val hasChanges: Boolean
        get() = inserts.isNotEmpty() || updates.isNotEmpty() || deleteSpotifyIds.isNotEmpty()
}
