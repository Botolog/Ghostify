package com.ghostify.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, thread-safe [DownloadRepository] for JVM tests. Also validates every
 * single-song transition through [SongStateMachine] so the tests would catch any
 * illegal jump performed by the manager/runner (T-044).
 */
class FakeDownloadRepository : DownloadRepository {

    private val songs = ConcurrentHashMap<String, SongRecord>()
    private val playlists = ConcurrentHashMap<String, PlaylistStatus>()
    private val songFlows = ConcurrentHashMap<String, MutableStateFlow<List<SongRecord>>>()

    // -- seeding helpers -----------------------------------------------------

    fun seedPlaylist(
        playlistId: String,
        count: Int,
        status: DownloadStatus = DownloadStatus.PENDING,
        startPosition: Int = 0,
    ): List<SongRecord> {
        val seeded = (0 until count).map { i ->
            SongRecord(
                id = "${playlistId}:s$i",
                playlistId = playlistId,
                spotifyId = "spot$i",
                title = "Song $i",
                artists = "Artist",
                position = startPosition + i,
                status = status,
            )
        }
        seeded.forEach { upsert(it) }
        return seeded
    }

    fun seedSong(song: SongRecord): SongRecord {
        upsert(song)
        return song
    }

    fun seedPlaylistStatus(playlistId: String, status: PlaylistStatus) {
        playlists[playlistId] = status
    }

    // -- query helpers -------------------------------------------------------

    fun songs(playlistId: String): List<SongRecord> =
        songs.values.filter { it.playlistId == playlistId }.sortedBy { it.position }

    fun playlistIds(): List<String> = songs.values.map { it.playlistId }.distinct().sorted()

    // -- DownloadRepository --------------------------------------------------

    override suspend fun songsFor(playlistId: String): List<SongRecord> = songs(playlistId)

    override fun observeSongs(playlistId: String): Flow<List<SongRecord>> =
        songFlows.getOrPut(playlistId) { MutableStateFlow(songs(playlistId)) }.map { list ->
            list.sortedBy { it.position }
        }

    override suspend fun getSong(songId: String): SongRecord? = songs[songId]

    override suspend fun setStatus(
        songId: String,
        status: DownloadStatus,
        filePath: String?,
        error: String?,
    ) {
        val current = songs[songId] ?: return
        SongStateMachine.requireTransition(current.status, status)
        val updated = current.copy(
            status = status,
            filePath = filePath ?: current.filePath,
            error = error,
        )
        upsert(updated)
    }

    override suspend fun setStatuses(songIds: Collection<String>, status: DownloadStatus) {
        songIds.forEach { id ->
            songs[id]?.let { upsert(it.copy(status = status)) }
        }
    }

    override suspend fun getPlaylistStatus(playlistId: String): PlaylistStatus? =
        playlists[playlistId]

    override suspend fun setPlaylistStatus(playlistId: String, status: PlaylistStatus) {
        playlists[playlistId] = status
    }

    override suspend fun allPlaylistIds(): List<String> = playlistIds()

    private fun upsert(song: SongRecord) {
        songs[song.id] = song
        songFlows.getOrPut(song.playlistId) { MutableStateFlow(emptyList()) }.value = songs(song.playlistId)
    }
}

/**
 * A [TrackDownloader] that resolves each download from a per-song behaviour map.
 * [Behavior.failuresBeforeSuccess] models transient failures (e.g. network switch
 * T-053): the track fails N times, then succeeds on the next attempt.
 */
class ScriptedDownloader : TrackDownloader {

    data class Behavior(
        /** Final result after transient failures (defaults to success). */
        val returning: TrackDownloadResult? = null,
        /** Raw exception to throw, to prove the runner maps crashes defensively. */
        val throwing: Throwable? = null,
        /** Typed error for the first [failuresBeforeSuccess] attempts. */
        val failError: DownloadError? = null,
        val progressTicks: Int = 0,
        /** How many initial attempts fail (transient failure, e.g. T-053). */
        val failuresBeforeSuccess: Int = 0,
    )

    private val behaviors = ConcurrentHashMap<String, Behavior>()
    private val attempts = ConcurrentHashMap<String, Int>()

    /** Song ids in the order `download` was invoked. */
    val downloads = java.util.Collections.synchronizedList(mutableListOf<String>())

    val progressFractions = java.util.Collections.synchronizedList(mutableListOf<Float>())

    fun behaviorFor(songId: String, behavior: Behavior) {
        behaviors[songId] = behavior
    }

    override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
        downloads.add(song.id)
        val behavior = behaviors[song.id]
        if (behavior != null && behavior.progressTicks > 0) {
            repeat(behavior.progressTicks) { i ->
                val frac = (i + 1).toFloat() / behavior.progressTicks
                progressFractions.add(frac)
                onProgress(frac)
            }
        }
        behavior?.let { b ->
            val n = attempts.merge(song.id, 1, Int::plus) ?: 1
            if (n <= b.failuresBeforeSuccess) {
                return TrackDownloadResult(
                    songId = song.id,
                    error = b.failError ?: DownloadError.Generic("transient failure"),
                )
            }
            b.throwing?.let { throw it }
            if (b.returning != null) return b.returning
        }
        return TrackDownloadResult(song.id, filePath = "/store/${song.id}.mp3")
    }

    override suspend fun cancel(songId: String) = Unit
}

/**
 * A [TrackDownloader] that blocks each track on a per-song gate until released or
 * until the calling coroutine is cancelled — used by cancel/dedupe/concurrency tests.
 */
class BlockingDownloader : TrackDownloader {

    private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Song ids in the order `download` was invoked. */
    val downloads = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun release(songId: String) {
        gates.remove(songId)?.complete(Unit)
    }

    fun releaseAll() {
        gates.keys.toList().forEach { release(it) }
    }

    override suspend fun download(song: SongRecord, onProgress: (Float) -> Unit): TrackDownloadResult {
        downloads.add(song.id)
        onProgress(0f)
        val gate = gates.getOrPut(song.id) { CompletableDeferred() }
        gate.await() // cancellable: a cancelled run throws CancellationException here
        onProgress(1f)
        return TrackDownloadResult(song.id, filePath = "/store/${song.id}.mp3")
    }

    override suspend fun cancel(songId: String) {
        gates.remove(songId)?.complete(Unit)
    }
}
