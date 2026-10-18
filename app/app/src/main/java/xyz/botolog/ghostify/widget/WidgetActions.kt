package xyz.botolog.ghostify.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.updateAll
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.botolog.ghostify.GhostifyApplication
import xyz.botolog.ghostify.background.PlaybackService
import xyz.botolog.ghostify.data.db.entity.SongEntity
import xyz.botolog.ghostify.data.model.SongStatus as DataSongStatus
import xyz.botolog.ghostify.data.repo.SongRepository
import xyz.botolog.ghostify.player.core.Song
import xyz.botolog.ghostify.player.core.SongStatus
import xyz.botolog.ghostify.ui.viewmodel.toPlayerSong as toPlaylistPlayerSong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * Widget transport actions.
 *
 * Mechanism: a Glance `ActionCallback` per button. Glance ships the callback trampoline
 * (activity + broadcast receiver) in its own manifest, so nothing extra is needed here.
 * The callback binds a Media3 [MediaController] to the foreground
 * [PlaybackService] through a [SessionToken] and issues the command — binding is what
 * starts the (non-exported) `MediaSessionService`, so the buttons work even when the app
 * UI is not open. Nothing here touches the process-wide `ExoPlayer` directly: every
 * command flows through the same MediaSession the notification and lock screen use, so
 * existing playback behaviour is unchanged.
 */

/** Parameter key identifying which transport command a button issued. */
internal val WIDGET_ACTION_KEY = ActionParameters.Key<String>("xyz.botolog.ghostify.widget.ACTION")

internal const val ACTION_TOGGLE = "toggle"
internal const val ACTION_NEXT = "next"
internal const val ACTION_PREVIOUS = "previous"

/** Play/pause toggle action for the centre button. */
fun toggleAction(): Action =
    actionRunCallback<PlayerWidgetActionCallback>(actionParametersOf(WIDGET_ACTION_KEY to ACTION_TOGGLE))

/** Skip-to-next action for the right-hand button. */
fun nextAction(): Action =
    actionRunCallback<PlayerWidgetActionCallback>(actionParametersOf(WIDGET_ACTION_KEY to ACTION_NEXT))

/** Skip-to-previous action for the left-hand button. */
fun previousAction(): Action =
    actionRunCallback<PlayerWidgetActionCallback>(actionParametersOf(WIDGET_ACTION_KEY to ACTION_PREVIOUS))

/** Issues a single transport command against the shared playback session. */
class PlayerWidgetActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[WIDGET_ACTION_KEY] ?: return
        // MediaController must be built and used on a Looper thread.
        withContext(Dispatchers.Main.immediate) {
            val appContext = context.applicationContext
            val token = SessionToken(
                appContext,
                ComponentName(appContext, PlaybackService::class.java),
            )
            val future = MediaController.Builder(appContext, token).buildAsync()
            val controller = try {
                suspendCancellableCoroutine<MediaController> { cont ->
                    future.addListener({
                        try {
                            cont.resume(future.get())
                        } catch (t: Throwable) {
                            cont.resumeWithException(t)
                        }
                    }, MoreExecutors.directExecutor())
                    cont.invokeOnCancellation { MediaController.releaseFuture(future) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "PlayerWidgetActionCallback: could not bind PlaybackService")
                MediaController.releaseFuture(future)
                return@withContext
            }
            try {
                when (command) {
                    ACTION_TOGGLE -> if (controller.isPlaying) controller.pause() else controller.play()
                    ACTION_NEXT -> controller.seekToNext()
                    ACTION_PREVIOUS -> controller.seekToPrevious()
                }
                // Keep the controller bound briefly so the command is flushed over the
                // binder before we unbind; an immediate release can drop it.
                delay(COMMAND_FLUSH_MS)
            } catch (t: Throwable) {
                Timber.e(t, "PlayerWidgetActionCallback: command $command failed")
            } finally {
                MediaController.releaseFuture(future)
            }
        }
    }

    private companion object {
        const val COMMAND_FLUSH_MS = 300L
    }
}

// ── Sleep timer ───────────────────────────────────────────────────────

/** Parameter key carrying the sleep-timer command (`start` / `cancel`). */
internal val WIDGET_SLEEP_CMD = ActionParameters.Key<String>("xyz.botolog.ghostify.widget.SLEEP_CMD")

/** Parameter key carrying the requested countdown, in milliseconds. */
internal val WIDGET_SLEEP_MS = ActionParameters.Key<Long>("xyz.botolog.ghostify.widget.SLEEP_MS")

internal const val SLEEP_CMD_START = "start"
internal const val SLEEP_CMD_CANCEL = "cancel"

/** Starts a sleep timer of [durationMs] when the button is tapped. */
fun sleepStartAction(durationMs: Long): Action =
    actionRunCallback<SleepTimerWidgetActionCallback>(
        actionParametersOf(WIDGET_SLEEP_CMD to SLEEP_CMD_START, WIDGET_SLEEP_MS to durationMs),
    )

/** Cancels the running sleep timer when the button is tapped. */
fun sleepCancelAction(): Action =
    actionRunCallback<SleepTimerWidgetActionCallback>(
        actionParametersOf(WIDGET_SLEEP_CMD to SLEEP_CMD_CANCEL),
    )

/**
 * Drives the sleep timer from the widget.
 *
 * Unlike the transport callbacks above (which bind a `MediaController` so they work with
 * no UI process), the sleep timer lives **only** in the app-process `PlayerController` —
 * it is never persisted and has no MediaSession command, so the callback reaches the
 * container directly: `(context.applicationContext as GhostifyApplication)
 * .container.playerController`. The system only invokes an `ActionCallback` by starting
 * our process, so this still runs only on a real tap.
 *
 * Free limits: the countdown is visible on the widget only while the app process stays
 * alive, and a process death silently clears it (by design — see `SleepTimer`).
 */
class SleepTimerWidgetActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[WIDGET_SLEEP_CMD] ?: return
        val app = context.applicationContext as? GhostifyApplication ?: return
        withContext(Dispatchers.Main.immediate) {
            try {
                val controller = app.container.playerController
                when (command) {
                    SLEEP_CMD_START -> controller.startSleepTimer(
                        parameters[WIDGET_SLEEP_MS] ?: DEFAULT_SLEEP_MS,
                    )
                    SLEEP_CMD_CANCEL -> controller.cancelSleepTimer()
                }
            } catch (t: Throwable) {
                Timber.e(t, "SleepTimerWidgetActionCallback: $command failed")
            }
        }
        // The timer is app-process-only: start the live push (idempotent) and repaint now
        // so the ring reflects the new state even if the UI process was never opened.
        WidgetUpdateCoordinator.start(context)
        runCatching { SleepTimerWidget().updateAll(context) }
    }

    private companion object {
        const val DEFAULT_SLEEP_MS = 30L * 60L * 1000L
    }
}

// ── Shuffle roulette ──────────────────────────────────────────────────

/** Parameter key carrying a roulette command (`spin` / `toggle`). */
internal val WIDGET_ROULETTE_CMD = ActionParameters.Key<String>("xyz.botolog.ghostify.widget.ROULETTE_CMD")

internal const val ROULETTE_CMD_SPIN = "spin"
internal const val ROULETTE_CMD_TOGGLE = "toggle"

/** Tapping SPIN: turn shuffle on and jump to a random queue track, or start one. */
fun rouletteSpinAction(): Action =
    actionRunCallback<ShuffleRouletteActionCallback>(
        actionParametersOf(WIDGET_ROULETTE_CMD to ROULETTE_CMD_SPIN),
    )

/** Tapping the small shuffle chip: flip shuffle on/off. */
fun rouletteToggleAction(): Action =
    actionRunCallback<ShuffleRouletteActionCallback>(
        actionParametersOf(WIDGET_ROULETTE_CMD to ROULETTE_CMD_TOGGLE),
    )

/**
 * The "Spin the roulette" action.
 *
 * `spin` behaviour, using only existing player APIs:
 *  - **Queue loaded**: enable shuffle (idempotent) and `skipToMediaItem(random)` — a jump
 *    to a random position in the (already shuffled) queue.
 *  - **Empty queue**: a minimal cross-library random start — one
 *    `SongDao.getRandomDownloaded()` row (`ORDER BY RANDOM() LIMIT 1`) is turned into a
 *    one-song queue via `playPlaylist`. This is deliberately *not* a full cross-library
 *    shuffle: it starts a single random downloaded track and lets the normal queue rules
 *    take over from there.
 *
 * Limits: like the sleep callback it reaches the container on tap (allowed for actions);
 * the provider path is untouched. Randomness uses [Random], so repeated spins can repeat
 * a track (a real slot machine would avoid the current one, but doing so adds a new
 * "play next random" API the player does not yet have).
 */
class ShuffleRouletteActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val command = parameters[WIDGET_ROULETTE_CMD] ?: return
        val app = context.applicationContext as? GhostifyApplication ?: return
        withContext(Dispatchers.Main.immediate) {
            try {
                val controller = app.container.playerController
                when (command) {
                    ROULETTE_CMD_TOGGLE -> controller.toggleShuffle()
                    ROULETTE_CMD_SPIN -> spin(app, controller)
                }
            } catch (t: Throwable) {
                Timber.e(t, "ShuffleRouletteActionCallback: $command failed")
            }
        }
        WidgetUpdateCoordinator.start(context)
        runCatching { ShuffleRouletteWidget().updateAll(context) }
    }

    private suspend fun spin(
        app: GhostifyApplication,
        controller: xyz.botolog.ghostify.player.PlayerController,
    ) {
        val state = controller.state.value
        if (state.queue.isEmpty()) {
            val random = withContext(Dispatchers.IO) {
                app.container.database.songDao().getRandomDownloaded()
            } ?: return
            controller.playPlaylist(listOf(random.toPlayerSong()), startSongId = random.id)
            return
        }
        if (!state.shuffleEnabled) controller.setShuffleEnabled(true)
        val size = controller.state.value.queue.size
        if (size > 0) controller.skipToMediaItem(Random.nextInt(size))
    }

    /** Minimal entity → core mapping, mirroring `PlayerStatePersistence.restoreSongs`. */
    private fun SongEntity.toPlayerSong(): Song = Song(
        id = id,
        title = title,
        artists = artists,
        album = album.orEmpty(),
        durationMs = durationMs.toLong(),
        filePath = filePath,
        status = SongStatus.DOWNLOADED,
        coverUrl = coverUrl,
        coverArtLocalPath = coverArtLocalPath,
    )
}

// ── Playlist launcher tiles ───────────────────────────────────────────

/** Parameter key carrying the playlist id whose tile was tapped. */
internal val WIDGET_PLAYLIST_ID = ActionParameters.Key<String>("xyz.botolog.ghostify.widget.PLAYLIST_ID")

/** Tapping a playlist tile: start that playlist's playback. */
fun playlistTileAction(playlistId: String): Action =
    actionRunCallback<PlaylistTileActionCallback>(
        actionParametersOf(WIDGET_PLAYLIST_ID to playlistId),
    )

/**
 * The "Playlists" tile tap.
 *
 * Reaches the container on tap (allowed for actions, like the sleep-timer callback) and
 * starts the playlist through **exactly the same path** as `PlaylistDetailViewModel.playAll()`:
 * a paged `SongRepository.getDownloadedSongsBatch` read, filtered to downloaded rows with a
 * real file path, sorted by playlist position, mapped with the shared `toPlayerSong` and
 * handed to `playPlaylistLazy` (whose `loadMore` keeps paging). Reusing the existing path —
 * rather than a new "play by id" player API — is what keeps queue-building behaviour
 * identical to the in-app tap.
 *
 * A playlist with nothing downloaded is a **silent no-op** (matching the player's "nothing to
 * play" behaviour) so a stray tap cannot tear down the current queue. The provider path is
 * untouched: this is the only place the widget reads the playlist.
 */
class PlaylistTileActionCallback : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playlistId = parameters[WIDGET_PLAYLIST_ID] ?: return
        val app = context.applicationContext as? GhostifyApplication ?: return
        withContext(Dispatchers.Main.immediate) {
            try {
                play(app, playlistId)
            } catch (t: Throwable) {
                Timber.e(t, "PlaylistTileActionCallback: playing $playlistId failed")
            }
        }
        WidgetUpdateCoordinator.start(context)
        runCatching { PlaylistLauncherWidget().updateAll(context) }
    }

    private suspend fun play(app: GhostifyApplication, playlistId: String) {
        val songs = app.container.songRepository
        val firstBatch = loadBatch(songs, playlistId, offset = 0)
        if (firstBatch.isEmpty()) return
        app.container.playerController.playPlaylistLazy(
            initialBatch = firstBatch,
            playlistId = playlistId,
            loadMore = { offset ->
                loadBatch(songs, playlistId, offset).takeIf { it.isNotEmpty() }
            },
        )
    }

    /** One page of playable rows, in playlist order, as player songs. */
    private suspend fun loadBatch(
        songs: SongRepository,
        playlistId: String,
        offset: Int,
    ): List<Song> = withContext(Dispatchers.IO) {
        songs.getDownloadedSongsBatch(playlistId, BATCH_SIZE, offset)
            .filter { it.status == DataSongStatus.DOWNLOADED && !it.filePath.isNullOrBlank() }
            .sortedBy { it.position }
            .map { it.toPlaylistPlayerSong() }
    }

    private companion object {
        /** Matches `PlaylistDetailViewModel.playAll()`'s page size. */
        const val BATCH_SIZE = 200
    }
}
