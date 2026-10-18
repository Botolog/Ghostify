package xyz.botolog.ghostify.widget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.botolog.ghostify.GhostifyApplication
import xyz.botolog.ghostify.data.model.LibraryStats
import xyz.botolog.ghostify.player.ArtworkPersistence
import xyz.botolog.ghostify.ui.player.currentLineIndex
import xyz.botolog.ghostify.ui.player.parseLrc
import java.io.File

/**
 * The single read path for widget content.
 *
 * Two sources, in order:
 *  1. [liveSnapshot] — published by [WidgetUpdateCoordinator] while the real UI process
 *     is alive. This is fresh and cheap.
 *  2. A cold read of the **persisted** player state (`PlayerStatePersistence.load()`),
 *     the song row (`SongDao.getById`) and the cached cover file.
 *
 * The cold path deliberately touches **only** persisted state and Room. It never
 * creates `PlayerController`, the `ExoPlayer`, the `PlaybackService` or the
 * `MediaSession`: a process launched solely to draw a widget must not start audio
 * infrastructure. Live updates are *pushed* by the running app, never pulled here.
 */
object WidgetStateProvider {

    private val _snapshot = MutableStateFlow<WidgetSnapshot?>(null)

    /** Live snapshots pushed by [WidgetUpdateCoordinator]; `null` before the first push. */
    val snapshot: StateFlow<WidgetSnapshot?> = _snapshot.asStateFlow()

    /** The live snapshot, or `null` when this process has never published one. */
    val liveSnapshot: WidgetSnapshot? get() = _snapshot.value

    /** Publishes a fresh snapshot for the live process. */
    fun publish(snapshot: WidgetSnapshot) {
        _snapshot.value = snapshot
    }

    // ── Playlists (the "Playlists" launcher widget) ───────────────────
    //
    // Separate flows rather than extra `WidgetSnapshot` fields: the player snapshot's
    // `updateKey()` / `immediateKey()` decide when the *player* widgets repaint, and adding
    // playlist/stats fields there would perturb that repaint contract (and its tests). The
    // two new widgets observe these flows instead.

    private val _playlists = MutableStateFlow<List<PlaylistTile>?>(null)

    /** Live playlist tiles pushed by [WidgetUpdateCoordinator]; `null` before the first push. */
    val playlists: StateFlow<List<PlaylistTile>?> = _playlists.asStateFlow()

    /** Publishes fresh playlist tiles for the live process. */
    fun publishPlaylists(tiles: List<PlaylistTile>) {
        _playlists.value = tiles
    }

    private val _stats = MutableStateFlow<LibraryStats?>(null)

    /** Live library aggregates pushed by [WidgetUpdateCoordinator]; `null` before the first push. */
    val stats: StateFlow<LibraryStats?> = _stats.asStateFlow()

    /** Publishes fresh library aggregates for the live process. */
    fun publishStats(stats: LibraryStats) {
        _stats.value = stats
    }

    private val _lastSyncedAt = MutableStateFlow<Long?>(null)

    /** Live library last-synced timestamp; `null` until a push or a cold read resolves one. */
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    /** Publishes the library last-synced timestamp for the live process. */
    fun publishLastSyncedAt(epochMs: Long?) {
        _lastSyncedAt.value = epochMs
    }

    /** Drops the live snapshot (used by tests / future teardown). */
    fun clear() {
        _snapshot.value = null
        _playlists.value = null
        _stats.value = null
        _lastSyncedAt.value = null
    }

    /**
     * Resolves the snapshot to render: the live one when this process has it, otherwise
     * a fresh read of persisted state + Room, otherwise the empty snapshot.
     */
    suspend fun latest(context: Context): WidgetSnapshot =
        _snapshot.value ?: readPersisted(context) ?: WidgetSnapshot.EMPTY

    // ── Playlists / stats cold reads ──────────────────────────────────

    /**
     * Resolves the playlist tiles to render: the live list when this process has it,
     * otherwise a fresh Room read of the playlists table.
     *
     * Cold path only: one `playlists` query plus up to [PLAYLIST_TILE_CAP] small local-cover
     * reads. No player, no network. A missing cover (or a network-only `coverUrl`) simply
     * yields the placeholder glyph.
     */
    suspend fun latestPlaylists(context: Context): List<PlaylistTile> =
        _playlists.value ?: readPlaylists(context)

    /** Resolves the library aggregates: the live value, otherwise a Room `COUNT(*)` read. */
    suspend fun latestStats(context: Context): LibraryStats =
        _stats.value ?: readStats(context)

    /**
     * Resolves the library last-synced timestamp: the live value, otherwise the persisted
     * `SettingsRepository` value (a widget-only process never ran a sync, so it has no
     * in-memory history; see `SyncStatusHolder`).
     */
    suspend fun latestLastSyncedAt(context: Context): Long? =
        _lastSyncedAt.value ?: readLastSyncedAt(context)

    /** Cold playlist read: the `playlists` table, mapped to capped tiles with tiny covers. */
    private suspend fun readPlaylists(context: Context): List<PlaylistTile> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? GhostifyApplication ?: return@withContext emptyList()
        val rows = try {
            app.container.database.playlistDao().observeAll().first()
        } catch (t: Throwable) {
            Timber.e(t, "WidgetStateProvider: playlist read failed")
            emptyList()
        }
        playlistTilesFrom(rows) { readSmallCoverBytes(it.coverArtLocalPath) }
    }

    /** Cold stats read: the library aggregates from Room's scalar counts. */
    private suspend fun readStats(context: Context): LibraryStats = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? GhostifyApplication ?: return@withContext LibraryStats.EMPTY
        try {
            app.container.libraryStatsRepository.get()
        } catch (t: Throwable) {
            Timber.e(t, "WidgetStateProvider: library stats read failed")
            LibraryStats.EMPTY
        }
    }

    /** Cold last-synced read from the `settings` key/value store. */
    private suspend fun readLastSyncedAt(context: Context): Long? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? GhostifyApplication ?: return@withContext null
        try {
            app.container.settingsRepository.getLastSyncedAt()
        } catch (t: Throwable) {
            Timber.e(t, "WidgetStateProvider: last-synced read failed")
            null
        }
    }

    /**
     * Cold path: persisted state + Room + cover file only. No player is constructed.
     */
    private suspend fun readPersisted(context: Context): WidgetSnapshot? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? GhostifyApplication
        if (app == null) {
            Timber.w("WidgetStateProvider: application is not GhostifyApplication; rendering empty")
            return@withContext null
        }
        val container = app.container
        val saved = try {
            container.playerStatePersistence.load()
        } catch (t: Throwable) {
            Timber.e(t, "WidgetStateProvider: load persisted state failed")
            null
        } ?: return@withContext null
        val songId = saved.currentSongId?.takeIf { it.isNotBlank() } ?: return@withContext null
        val song = try {
            container.database.songDao().getById(songId)
        } catch (t: Throwable) {
            Timber.e(t, "WidgetStateProvider: song lookup failed for $songId")
            null
        } ?: return@withContext null

        val parsedLyrics = song.lyrics?.let(::parseLrc).orEmpty()

        val queueIds = saved.queueSongIds
        val currentIndex = queueIds.indexOf(songId)

        WidgetSnapshot(
            songId = songId,
            title = song.title,
            artist = song.artists,
            album = song.album,
            // A cold process is, by definition, not producing audio.
            isPlaying = false,
            positionMs = saved.positionMs,
            durationMs = song.durationMs.toLong(),
            hasQueue = queueIds.isNotEmpty(),
            coverBytes = readCoverBytes(context, songId, song.coverArtLocalPath),
            // Lyrics come straight from the songs row (Room only): synced LRC when the
            // row has timestamps, otherwise the static plain-text fallback.
            lyricsLines = if (parsedLyrics.isNotEmpty()) {
                parsedLyrics.map { it.text }
            } else {
                plainLyricsLines(song.lyrics)
            },
            currentLineIndex = if (parsedLyrics.isNotEmpty()) {
                currentLineIndex(parsedLyrics, saved.positionMs)
            } else {
                -1
            },
            // The sleep timer is intentionally never persisted (see SleepTimerState), so a
            // cold process can never restore one: it always reports inactive here. The
            // countdown is only visible while the app process that owns it is alive.
            sleepTimerActive = false,
            nextUp = readPersistedNextUp(container.database.songDao(), queueIds, songId),
            queueCount = queueIds.size,
            currentQueueIndex = currentIndex,
            shuffleEnabled = saved.shuffleEnabled,
        )
    }

    /**
     * Rebuilds the first few upcoming rows for the cold path from the saved queue ids.
     *
     * Text only, and capped by [COLD_NEXT_UP_CAP] so a cold widget launch spends a couple
     * of `SongDao.getById` reads rather than walking the whole queue. Rows that no longer
     * resolve (deleted songs) are simply skipped.
     */
    private suspend fun readPersistedNextUp(
        songDao: xyz.botolog.ghostify.data.db.dao.SongDao,
        queueIds: List<String>,
        currentSongId: String,
    ): List<NextUpItem> {
        val ids = nextUpQueueIds(queueIds, currentSongId)
        if (ids.isEmpty()) return emptyList()
        val startIndex = queueIds.indexOfFirst { it == ids.first() }.coerceAtLeast(0)
        return ids.mapIndexedNotNull { offset, id ->
            val row = try {
                songDao.getById(id)
            } catch (t: Throwable) {
                Timber.e(t, "WidgetStateProvider: next-up lookup failed for $id")
                null
            } ?: return@mapIndexedNotNull null
            NextUpItem(
                title = row.title,
                artist = row.artists,
                durationMs = row.durationMs.toLong(),
                indexInQueue = startIndex + offset,
            )
        }
    }

    /** Reads the local cover bytes: the stored local path first, then `covers/<id>.jpg`. */
    private fun readCoverBytes(context: Context, songId: String, coverArtLocalPath: String?): ByteArray? {
        val fromColumn = coverArtLocalPath
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { File(it).takeIf(File::isFile)?.readBytes() }.getOrNull() }
        if (fromColumn != null && fromColumn.isNotEmpty()) return fromColumn
        return runCatching {
            ArtworkPersistence(context).coverFor(songId)?.readBytes()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
