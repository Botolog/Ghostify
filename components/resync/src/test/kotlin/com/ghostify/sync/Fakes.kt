package com.ghostify.sync

import com.ghostify.data.db.TransactionRunner
import com.ghostify.data.db.dao.PlaylistDao
import com.ghostify.data.db.dao.SongDao
import com.ghostify.data.db.entity.PlaylistEntity
import com.ghostify.data.db.entity.SongEntity
import com.ghostify.data.model.SongStatus
import com.ghostify.python.PlaylistMetadata
import com.ghostify.python.PlaylistTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [SongDao] that enforces the two invariants the diff depends on:
 *  - `UNIQUE(playlist_id, spotify_id)` — duplicate insert throws (T-064),
 *  - ordered reads by `(position, added_at)`.
 * Snapshot/restore lets [RollbackTransactionRunner] model SQLite rollback.
 */
class FakeSongDao : SongDao {
    val rows = LinkedHashMap<String, SongEntity>()

    /** When true, [insertAll] throws mid-batch (tests transactional rollback). */
    var failOnInsertAll = false

    private fun allFor(playlistId: String): List<SongEntity> = rows.values
        .filter { it.playlistId == playlistId }
        .sortedWith(compareBy<SongEntity> { it.position }.thenBy { it.addedAt })

    override suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity> = allFor(playlistId)

    override suspend fun getSongsByStatus(playlistId: String, statuses: List<SongStatus>): List<SongEntity> =
        allFor(playlistId).filter { it.status in statuses }

    override suspend fun getById(id: String): SongEntity? = rows[id]

    override suspend fun getByPlaylistAndSpotify(playlistId: String, spotifyId: String): SongEntity? =
        allFor(playlistId).firstOrNull { it.spotifyId == spotifyId }

    override suspend fun insert(song: SongEntity) {
        checkUnique(song)
        rows[song.id] = song
    }

    override suspend fun insertAll(songs: List<SongEntity>) {
        if (failOnInsertAll) throw IllegalStateException("insertAll failed (simulated)")
        songs.forEach { checkUnique(it); rows[it.id] = it }
    }

    override suspend fun update(song: SongEntity) {
        if (song.id in rows) rows[song.id] = song
    }

    override suspend fun updateAll(songs: List<SongEntity>) {
        songs.forEach { if (it.id in rows) rows[it.id] = it }
    }

    override suspend fun delete(song: SongEntity) {
        rows.remove(song.id)
    }

    override suspend fun deleteSongsForPlaylist(playlistId: String) {
        rows.values.removeAll { it.playlistId == playlistId }
    }

    override suspend fun deleteBySpotifyIds(playlistId: String, spotifyIds: List<String>) {
        rows.values.removeAll { it.playlistId == playlistId && it.spotifyId in spotifyIds }
    }

    override suspend fun updateStatus(ids: List<String>, status: SongStatus) {
        ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(status = status) } }
    }

    override suspend fun countForPlaylist(playlistId: String): Int = allFor(playlistId).size

    override suspend fun countForPlaylistByStatus(playlistId: String, status: SongStatus): Int =
        allFor(playlistId).count { it.status == status }

    override fun observeSongsForPlaylist(playlistId: String): Flow<List<SongEntity>> = flowOf(allFor(playlistId))

    override fun observeSongsByStatus(playlistId: String, statuses: List<SongStatus>): Flow<List<SongEntity>> =
        flowOf(allFor(playlistId).filter { it.status in statuses })

    override fun observeById(id: String): Flow<SongEntity?> = flowOf(rows[id])

    private fun checkUnique(song: SongEntity) {
        val duplicate = rows.values.any {
            it.playlistId == song.playlistId && it.spotifyId == song.spotifyId
        }
        if (duplicate) throw IllegalStateException("UNIQUE(playlist_id, spotify_id) violated")
    }

    fun snapshot(): Map<String, SongEntity> = HashMap(rows)

    fun restore(snapshot: Map<String, SongEntity>) {
        rows.clear()
        rows.putAll(snapshot)
    }
}

/** In-memory [PlaylistDao]. */
class FakePlaylistDao : PlaylistDao {
    val rows = LinkedHashMap<String, PlaylistEntity>()

    override suspend fun getById(id: String): PlaylistEntity? = rows[id]

    override suspend fun getBySpotifyId(spotifyId: String): PlaylistEntity? =
        rows.values.firstOrNull { it.spotifyId == spotifyId }

    override suspend fun insert(playlist: PlaylistEntity) {
        rows[playlist.id] = playlist
    }

    override suspend fun insertAll(playlists: List<PlaylistEntity>) {
        playlists.forEach { rows[it.id] = it }
    }

    override suspend fun update(playlist: PlaylistEntity) {
        if (playlist.id in rows) rows[playlist.id] = playlist
    }

    override suspend fun delete(playlist: PlaylistEntity) {
        rows.remove(playlist.id)
    }

    override suspend fun deleteById(id: String) {
        rows.remove(id)
    }

    override suspend fun count(): Int = rows.size

    override fun observeAll(): Flow<List<PlaylistEntity>> = flowOf(rows.values.toList())

    override fun observeById(id: String): Flow<PlaylistEntity?> = flowOf(rows[id])

    fun snapshot(): Map<String, PlaylistEntity> = HashMap(rows)

    fun restore(snapshot: Map<String, PlaylistEntity>) {
        rows.clear()
        rows.putAll(snapshot)
    }
}

/**
 * Models SQLite's transactional rollback: snapshots the fake stores before the
 * block and restores them if the block throws. This is what lets T-067 verify a
 * mid-write failure leaves the database unchanged.
 */
class RollbackTransactionRunner(
    private val songDao: FakeSongDao,
    private val playlistDao: FakePlaylistDao,
) : TransactionRunner {
    override suspend fun <R> withinTransaction(block: suspend () -> R): R {
        val songsSnap = songDao.snapshot()
        val playlistsSnap = playlistDao.snapshot()
        return try {
            block()
        } catch (t: Throwable) {
            songDao.restore(songsSnap)
            playlistDao.restore(playlistsSnap)
            throw t
        }
    }
}

/** Controllable [SpotifyPlaylistFetcher]. */
class FakeFetcher : SpotifyPlaylistFetcher {
    var name: String = "Playlist"
    var owner: String? = "Owner"
    var coverUrl: String? = "http://cover.example/art"
    var tracks: List<PlaylistTrack> = emptyList()
    var throwOnFetch: Throwable? = null
    var fetchCount = 0

    fun setTracks(vararg tracks: PlaylistTrack) {
        this.tracks = tracks.toList()
    }

    override suspend fun fetchPlaylist(spotifyId: String): PlaylistMetadata {
        fetchCount++
        throwOnFetch?.let { throw it }
        return PlaylistMetadata(
            name = name,
            owner = owner ?: "",
            coverUrl = coverUrl,
            description = null,
            trackCount = tracks.size,
            tracks = tracks,
        )
    }

    companion object {
        fun track(pos: Int, id: String, title: String, artists: String = "Artist", album: String = "Album") =
            PlaylistTrack(
                position = pos,
                spotifyId = id,
                title = title,
                artists = artists,
                album = album,
                durationMs = 180_000L,
                coverUrl = null,
                ytId = null,
            )
    }
}

/** Records enqueue calls. */
class FakeEnqueuer : DownloadEnqueuer {
    val calls = mutableListOf<String>()
    override suspend fun enqueuePendingDownloads(playlistId: String) {
        calls.add(playlistId)
    }
}
