package com.ghostify.sync

import com.ghostify.data.db.TransactionRunner
import com.ghostify.data.db.dao.PlaylistDao
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.model.SongStatus
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

    suspend fun syncPlaylist(playlistId: String): SyncResult {
        Timber.i("SyncUseCase.syncPlaylist: START playlistId=$playlistId")
        val result = locks.withPlaylistLock(playlistId) { doSync(playlistId) }
        Timber.i("SyncUseCase.syncPlaylist: returning $result")
        return result
    }

    private suspend fun doSync(playlistId: String): SyncResult {
        val playlist = playlists.getById(playlistId)
            ?: throw SyncException.PlaylistNotFound(playlistId)

        // 1) Network I/O — never inside a DB transaction (T-067).
        val remote = try {
            fetcher.fetchPlaylist(playlist.spotifyId)
        } catch (e: Exception) {
            throw SyncException.Network(playlist.spotifyId, e)
        }

        // 2) Read + diff + write atomically. Any DAO failure rolls the whole
        //    batch back: no partial rows, no half-updated playlist (T-067).
        val plan = transactions.withinTransaction {
            val stored = songs.getSongsForPlaylist(playlistId)
            val p = SyncDiff.compute(playlistId, remote.tracks, stored, files, now, newId)
            if (p.inserts.isNotEmpty()) songs.insertAll(p.inserts)
            if (p.updates.isNotEmpty()) songs.updateAll(p.updates)
            if (p.deleteSpotifyIds.isNotEmpty()) {
                songs.deleteBySpotifyIds(playlistId, p.deleteSpotifyIds)
            }
            playlists.update(
                playlist.copy(
                    name = remote.name,
                    owner = remote.owner,
                    coverUrl = remote.coverUrl,
                    trackCount = p.finalTrackCount,
                    lastSyncedAt = now(),
                )
            )
            p
        }

        // 3) Disk cleanup after the commit (see class docs).
        for (path in plan.deleteFilePaths) {
            runCatching { files.delete(path) }
        }

        // 4) Kick off downloads for inserted + re-queued tracks. The enqueuer
        //    claims PENDING -> QUEUED atomically, so a concurrent manual
        //    download can never double-download a track (T-068).
        if (plan.enqueueIds.isNotEmpty()) {
            enqueuer.enqueuePendingDownloads(playlistId)
        }

        return plan.toResult()
    }

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
