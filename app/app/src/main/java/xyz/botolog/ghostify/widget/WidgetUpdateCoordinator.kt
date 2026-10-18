package xyz.botolog.ghostify.widget

import android.content.Context
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.botolog.ghostify.GhostifyApplication
import xyz.botolog.ghostify.player.ArtworkPersistence
import xyz.botolog.ghostify.player.core.PlayerUiState
import xyz.botolog.ghostify.sync.SyncStatusHolder
import xyz.botolog.ghostify.ui.player.LrcLine
import xyz.botolog.ghostify.ui.player.currentLineIndex
import xyz.botolog.ghostify.ui.player.parseLrc

/**
 * Pushes live player state into the widget.
 *
 * Started **only** from the real UI process ([xyz.botolog.ghostify.MainActivity]): it
 * subscribes to `PlayerController.state`, so it must never run for a widget-only cold
 * start (that path renders from persisted state via [WidgetStateProvider]).
 *
 * Behaviour:
 *  - A repaint-worthy snapshot is published to [WidgetStateProvider]; the live widget
 *    compositions observe that flow, so they repaint themselves.
 *  - The publish + `updateAll` repaint fires immediately on a track / play-state change
 *    and is otherwise throttled to at most once per second. It repaints every player widget:
 *    the player ([PlayerWidget]), lyrics ([LyricsWidget]), sleep timer
 *    ([SleepTimerWidget]), queue peek ([NextUpWidget]) and roulette
 *    ([ShuffleRouletteWidget]).
 *  - The **library** widgets are driven from their own sources, independent of the player:
 *    the playlists table feeds [PlaylistLauncherWidget] and the library aggregates
 *    (`LibraryStatsRepository`) plus the process-wide `SyncStatusHolder` feed
 *    [LibraryStatsWidget]. Each only repaints when its own data changes.
 *  - At start it seeds `SyncStatusHolder` from the persisted last-synced timestamp
 *    (`SettingsRepository`), so the library widget's live readout agrees with what a cold
 *    widget process reads from Room.
 *  - When the current song changes it also observes that song's lyrics
 *    (`SongDao.observeLyricsById`) and derives the active line, reusing the app's
 *    existing LRC helpers (pass 2 data, carried in the snapshot). Lyrics that arrive
 *    after the song's first state emission trigger their own repaint.
 *
 * Guarded so a second [start] call is a no-op.
 */
object WidgetUpdateCoordinator {

    private const val THROTTLE_MS = 1_000L

    private val lock = Any()

    @Volatile
    private var started = false

    private var scope: CoroutineScope? = null

    /**
     * Starts the coordinator once, for the lifetime of the process.
     *
     * @param context any context; only its application context is retained.
     */
    fun start(context: Context) {
        if (started) return
        synchronized(lock) {
            if (started) return
            started = true
        }
        val app = context.applicationContext as? GhostifyApplication
        if (app == null) {
            Timber.w("WidgetUpdateCoordinator: not a GhostifyApplication; not starting")
            return
        }
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = coordinatorScope
        coordinatorScope.launch {
            try {
                observe(app, coordinatorScope)
            } catch (t: Throwable) {
                Timber.e(t, "WidgetUpdateCoordinator: observation stopped")
            }
        }
    }

    private suspend fun observe(app: GhostifyApplication, scope: CoroutineScope) {
        val container = app.container
        val playerWidget = PlayerWidget()
        val lyricsWidget = LyricsWidget()
        val sleepTimerWidget = SleepTimerWidget()
        val nextUpWidget = NextUpWidget()
        val shuffleRouletteWidget = ShuffleRouletteWidget()
        val playlistWidget = PlaylistLauncherWidget()
        val libraryWidget = LibraryStatsWidget()
        val artwork = ArtworkPersistence(app)

        var lyricsSongId: String? = null
        var lyricsJob: Job? = null
        var parsedLyrics: List<LrcLine> = emptyList()
        var rawLyrics: String? = null
        var latestState: PlayerUiState = PlayerUiState()

        var lastKey: String? = null
        var lastImmediateKey: String? = null
        var lastRefreshMs = 0L

        // Publishes the latest state + lyrics and repaints every widget when something
        // visible changed: immediately on a track / play-state change, otherwise at most
        // once per second.
        suspend fun publish(state: PlayerUiState) {
            val songId = state.currentItem?.mediaId
            // Prefer the bytes already held by the player; fall back to the cached cover file.
            val coverBytes = state.currentItem?.artworkBytes
                ?: songId?.let { id ->
                    withContext(Dispatchers.IO) { artwork.coverFor(id)?.readBytes() }
                }

            val hasTiming = parsedLyrics.isNotEmpty()
            val snapshot = snapshotFromPlayerState(
                state = state,
                coverBytes = coverBytes,
                lyricsLines = widgetLyricsLines(parsedLyrics.map { it.text }, rawLyrics),
                currentLineIndex = if (hasTiming) currentLineIndex(parsedLyrics, state.positionMs) else -1,
            )
            val immediateKey = snapshot.immediateKey()
            val key = snapshot.updateKey()
            val now = SystemClock.elapsedRealtime()
            val mustRepaint = immediateKey != lastImmediateKey
            val throttledChanged = key != lastKey && now - lastRefreshMs >= THROTTLE_MS
            if (mustRepaint || throttledChanged) {
                lastImmediateKey = immediateKey
                lastKey = key
                lastRefreshMs = now
                // Publish only repaint-worthy snapshots: the live composition observes
                // this flow and recomposes itself, so publishing every position tick
                // would push a RemoteViews update several times a second. `updateAll`
                // additionally (re)starts a session when none is running.
                WidgetStateProvider.publish(snapshot)
                try {
                    playerWidget.updateAll(app)
                    lyricsWidget.updateAll(app)
                    sleepTimerWidget.updateAll(app)
                    nextUpWidget.updateAll(app)
                    shuffleRouletteWidget.updateAll(app)
                } catch (t: Throwable) {
                    Timber.e(t, "WidgetUpdateCoordinator: updateAll failed")
                }
            }
        }

        // ── Library widgets: playlists, aggregates and global sync ────────
        // Seed the in-memory sync holder + widget readout from the persisted timestamp, so
        // this process's live readout matches what a cold widget process reads from Room.
        scope.launch {
            val syncedAt = runCatching { container.settingsRepository.getLastSyncedAt() }.getOrNull()
            SyncStatusHolder.restore(syncedAt)
            WidgetStateProvider.publishLastSyncedAt(syncedAt)
        }

        // Playlists table → the "Playlists" widget. The cover reads touch the file system,
        // so the tile build (which reads ≤4 small local covers) runs on IO.
        scope.launch {
            try {
                container.database.playlistDao().observeAll().collect { rows ->
                    val tiles = withContext(Dispatchers.IO) {
                        playlistTilesFrom(rows) { readSmallCoverBytes(it.coverArtLocalPath) }
                    }
                    WidgetStateProvider.publishPlaylists(tiles)
                    playlistWidget.updateAll(app)
                }
            } catch (t: Throwable) {
                Timber.e(t, "WidgetUpdateCoordinator: playlist observation failed")
            }
        }

        // Library aggregates → the "Library" stats. Room only re-emits on a real write
        // (download / sync / playlist change), so every emission is worth a repaint.
        scope.launch {
            try {
                container.libraryStatsRepository.observe().collect { stats ->
                    WidgetStateProvider.publishStats(stats)
                    libraryWidget.updateAll(app)
                }
            } catch (t: Throwable) {
                Timber.e(t, "WidgetUpdateCoordinator: library stats observation failed")
            }
        }

        // Global sync status → the "Library" widget's sync readout.
        scope.launch {
            try {
                SyncStatusHolder.state.collect { sync ->
                    WidgetStateProvider.publishLastSyncedAt(sync.lastSyncedAtMs)
                    libraryWidget.updateAll(app)
                }
            } catch (t: Throwable) {
                Timber.e(t, "WidgetUpdateCoordinator: sync-status observation failed")
            }
        }

        container.playerController.state.collect { state ->
            latestState = state
            val songId = state.currentItem?.mediaId
            if (songId != lyricsSongId) {
                // Track changed: drop the previous lyrics and re-read this song's row.
                lyricsSongId = songId
                parsedLyrics = emptyList()
                rawLyrics = null
                lyricsJob?.cancel()
                lyricsJob = songId?.let { id ->
                    scope.launch {
                        try {
                            container.database.songDao().observeLyricsById(id).collect { lrc ->
                                rawLyrics = lrc
                                parsedLyrics = lrc?.let(::parseLrc).orEmpty()
                                // Lyrics usually land *after* the state that selected the
                                // song, so repaint on arrival too.
                                publish(latestState)
                            }
                        } catch (t: Throwable) {
                            Timber.e(t, "WidgetUpdateCoordinator: lyrics observation failed for $id")
                        }
                    }
                }
            }
            publish(state)
        }
    }
}
