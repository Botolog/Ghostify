package xyz.botolog.ghostify.sync

import xyz.botolog.ghostify.data.db.TransactionRunner
import xyz.botolog.ghostify.data.db.dao.PlaylistDao
import xyz.botolog.ghostify.data.db.dao.SongDao
import xyz.botolog.ghostify.data.model.SongStatus
import java.util.UUID
import timber.log.Timber

/**
 * Re-sync / redownload diff (PROJECT.md §6.2).
 *
 * `syncPlaylist(playlistId)` diffs the current Spotify track list against the
 * stored songs by `spotify_id`: insert new rows (PENDING), delete removed rows
 * + their local files, re-queue rows whose file is missing, update playlist
 * metadata, and skip everything else. Already-downloaded, unchanged tracks are
 * never re-downloaded.
 *
 * ## Ordering & atomicity (why this is optimal)
 *  1. **Fetch first, transaction after.** The network call happens with no DB
 *     lock held. A slow/offline fetch therefore cannot block the whole app and
 *     a failure writes nothing at all (T-067).
 *  2. **Diff inside one transaction.** Read + diff + write happen under a single
 *     SQLite transaction, which serializes writers. A concurrent
 *     download-manager claim cannot interleave mid-diff, so a track is enqueued
 *     exactly once (T-068).
 *  3. **Files after commit.** Rows are the source of truth; leftover orphan
 *     files are reclaimed by `MusicStore.clearOrphans`. Deleting a file that is
 *     already gone is a no-op (T-065).
 *  4. **In-flight protection.** `QUEUED`/`DOWNLOADING` rows are never touched or
 *     re-enqueued — a manual download owns them until it finishes (T-068).
 *
 * @property playlists DAO for the `playlists` table.
 * @property songs DAO for the `songs` table.
 * @property transactions Provides transactional write boundaries.
 * @property fetcher Fetches playlist metadata from Spotify/YouTube.
 * @property files Local file existence and deletion.
 * @property enqueuer Starts pending downloads after a successful diff.
 * @property locks Per-playlist mutex to serialize concurrent syncs.
 * @property now Clock function returning epoch millis (injectable for testing).
 * @property newId UUID generator (injectable for testing).
 */
class SyncUseCase(
    private val playlists: PlaylistDao,
    private val songs: SongDao,
    private val transactions: TransactionRunner,
    private val fetcher: SpotifyPlaylistFetcher,
    private val files: LocalFileStore,
    private val enqueuer: DownloadEnqueuer,
    private val locks: SyncLocks,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Re-syncs a single playlist against its remote origin.
     *
     * Acquires a per-playlist lock, fetches remote tracks, computes the diff,
     * applies all mutations atomically, cleans up orphaned files, and enqueues
     * any new downloads.
     *
     * @param playlistId The local playlist id to re-sync.
     * @return A [SyncResult] summarising what changed.
     * @throws SyncException.PlaylistNotFound if the playlist does not exist locally.
     * @throws SyncException.Network if the remote fetch fails.
     */
    suspend fun syncPlaylist(playlistId: String): SyncResult {
        Timber.i("SyncUseCase.syncPlaylist: START playlistId=$playlistId")
        val result = locks.withPlaylistLock(playlistId) { doSync(playlistId) }
        Timber.i("SyncUseCase.syncPlaylist: returning $result")
        return result
    }

    /**
     * Core sync logic, always called under the playlist lock.
     */
    private suspend fun doSync(playlistId: String): SyncResult {
        val playlist = playlists.getById(playlistId)
            ?: throw SyncException.PlaylistNotFound(playlistId)

        val remote = fetchRemoteTracks(playlist.spotifyId, playlist.origin)

        val plan = applyDiffWithinTransaction(playlistId, playlist, remote)

        deleteOrphanedFiles(plan.deleteFilePaths)
        enqueuePendingDownloadsIfNeeded(playlistId, plan)

        return plan.toResult()
    }

    /**
     * Fetches remote playlist tracks outside any DB transaction (T-067).
     */
    private suspend fun fetchRemoteTracks(
        spotifyId: String,
        origin: xyz.botolog.ghostify.data.model.PlaylistOrigin,
    ): xyz.botolog.ghostify.python.PlaylistMetadata =
        try {
            fetcher.fetchPlaylist(playlistId = spotifyId, origin = origin)
        } catch (e: Exception) {
            throw SyncException.Network(spotifyId, e)
        }

    /**
     * Reads current songs, computes the diff, and applies inserts/updates/deletes
     * atomically. Any DAO failure rolls the whole batch back (T-067).
     */
    private suspend fun applyDiffWithinTransaction(
        playlistId: String,
        playlist: xyz.botolog.ghostify.data.db.entity.PlaylistEntity,
        remote: xyz.botolog.ghostify.python.PlaylistMetadata,
    ): SyncPlan =
        transactions.withinTransaction {
            val stored = songs.getSongsForPlaylist(playlistId)
            val plan = SyncDiff.compute(playlistId, remote.tracks, stored, files, now, newId)

            if (plan.inserts.isNotEmpty()) songs.insertAll(plan.inserts)
            if (plan.updates.isNotEmpty()) songs.updateAll(plan.updates)
            if (plan.deleteSpotifyIds.isNotEmpty()) {
                songs.deleteBySpotifyIds(playlistId, plan.deleteSpotifyIds)
            }

            playlists.update(
                playlist.copy(
                    name = remote.name,
                    owner = remote.owner,
                    coverUrl = remote.coverUrl,
                    trackCount = plan.finalTrackCount,
                    lastSyncedAt = now(),
                ),
            )

            plan
        }

    /**
     * Deletes orphaned local files after the transaction commits (see class docs).
     */
    private fun deleteOrphanedFiles(filePaths: List<String>) {
        for (path in filePaths) {
            runCatching { files.delete(path) }
        }
    }

    /**
     * Kicks off downloads for inserted + re-queued tracks.
     *
     * The enqueuer claims PENDING → QUEUED atomically, so a concurrent manual
     * download can never double-download a track (T-068).
     */
    private suspend fun enqueuePendingDownloadsIfNeeded(playlistId: String, plan: SyncPlan) {
        if (plan.enqueueIds.isNotEmpty()) {
            enqueuer.enqueuePendingDownloads(playlistId)
        }
    }

    /**
     * Converts a [SyncPlan] into a human-readable [SyncResult].
     */
    private fun SyncPlan.toResult(): SyncResult {
        val resets = updates.count { it.status == SongStatus.PENDING && it.filePath == null }
        return SyncResult(
            added = inserts.size,
            removed = deleteSpotifyIds.size,
            requeued = resets,
            metadataUpdated = updates.size - resets,
            filesDeleted = deleteFilePaths.size,
        )
    }
}
