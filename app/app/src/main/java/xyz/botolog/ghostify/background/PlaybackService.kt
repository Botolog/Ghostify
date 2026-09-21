package xyz.botolog.ghostify.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import xyz.botolog.ghostify.MainActivity
import xyz.botolog.ghostify.R
import xyz.botolog.ghostify.background.core.AudioFocusController
import xyz.botolog.ghostify.background.core.FocusPolicy
import xyz.botolog.ghostify.player.PlaybackEngine
import timber.log.Timber

/**
 * Foreground `MediaLibraryService` that is the single source of truth for audio
 * playback in Ghostify.
 *
 * Responsibilities:
 *  - Hosts the shared `ExoPlayer` + `MediaLibrarySession` from [PlaybackEngine]; hands
 *    the session to controllers (UI `MediaController`, Bluetooth, Android Auto)
 *    via [onGetSession]. The UI's [xyz.botolog.ghostify.player.PlayerController] drives
 *    the same player, so there is never a second audio engine.
 *  - Runs as a foreground service via a `MediaNotification.Provider`
 *    ([PlaybackNotificationProvider]) so playback survives the app being swiped
 *    away (T-093, T-096) and works under battery saver (T-106).
 *  - Audio focus is primarily owned by ExoPlayer's automatic handling (the shared
 *    player is built with default focus attributes); the explicit
 *    [AudioFocusController] and [AudioBecomingNoisyReceiver] are kept as a
 *    complementary safety net (T-098, T-099).
 *  - Tears the service down on an explicit Stop / end-of-queue, so there is never
 *    a zombie notification (T-097).
 *  - Survives configuration changes (T-102): the player lives in the service, not
 *    the Activity, so rotation only rebinds a `MediaController`.
 *
 * Media buttons (T-101), notification actions (T-100), lockscreen controls (T-095)
 * and the seek bar all flow through the MediaSession -> player, which the
 * foreground service keeps alive.
 */
class PlaybackService : MediaLibraryService() {

    companion object {

        /** Notification channel ID used by [PlaybackNotificationProvider]. */
        const val NOTIFICATION_ID: Int = 1

        /** Notification channel ID for the foreground service notification. */
        private const val NOTIFICATION_CHANNEL_ID: String = "ghostify_playback"
    }

    private var player: ExoPlayer? = null
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private var focusController: AudioFocusController? = null
    private var noisyReceiver: AudioBecomingNoisyReceiver? = null
    private var playbackEverStarted = false

    override fun onCreate() {
        super.onCreate()
        Timber.i("PlaybackService.onCreate: START")
        setNotificationProvider()
        initSessionAndPlayer()
        Timber.i("PlaybackService.onCreate: DONE")
    }

    /** Sets the custom notification provider that includes a Shutdown button. */
    private fun setNotificationProvider() {
        try {
            Timber.i("PlaybackService.onCreate: setting notification provider")
            setMediaNotificationProvider(
                PlaybackNotificationProvider(this, R.drawable.ic_notification),
            )
            Timber.i("PlaybackService.onCreate: notification provider set")
        } catch (t: Throwable) {
            Timber.e(t, "PlaybackService.onCreate: notification provider FAILED")
            throw t
        }
    }

    /**
     * Hands out the session created eagerly in [onCreate].
     *
     * @param controllerInfo info about the connecting controller.
     * @return the shared [MediaLibraryService.MediaLibrarySession], or null if not yet created.
     */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibraryService.MediaLibrarySession? {
        Timber.i("PlaybackService.onGetSession: START, session=${session != null}")
        val s = session
        if (s == null) {
            Timber.w("PlaybackService.onGetSession: session is null")
            return null
        }
        Timber.i("PlaybackService.onGetSession: returning session")
        return s
    }

    /** Initialises the shared ExoPlayer, AudioFocusController, noisy receiver and MediaLibrarySession. */
    private fun initSessionAndPlayer() {
        if (session != null) return
        Timber.i("initSessionAndPlayer: START")
        try {
            val p = obtainPlayer()
            p.prepare()
            val control = PlayerControlAdapter(p)
            val focus = createFocusController(control)
            val noisy = AudioBecomingNoisyReceiver(control)
            p.addListener(buildAutoStopListener(focus))
            session = buildSession(p, focus)
            assignFields(p, focus, noisy)
            registerNoisyReceiver(noisy)
            playbackEverStarted = false
            Timber.i("initSessionAndPlayer: DONE - session=${session != null}, player=${player != null}")
        } catch (t: Throwable) {
            Timber.e(t, "initSessionAndPlayer: CRASHED at step")
            throw t
        }
    }

    /** Obtains the shared [ExoPlayer] from [PlaybackEngine]. */
    private fun obtainPlayer(): ExoPlayer {
        Timber.i("initSessionAndPlayer: calling PlaybackEngine.exoPlayer(this)")
        val p = PlaybackEngine.exoPlayer(this)
        Timber.i("initSessionAndPlayer: ExoPlayer obtained")
        return p
    }

    /**
     * Creates an [AudioFocusController] wired to the given [control] adapter.
     *
     * @param control the player control adapter that will receive focus-driven actions.
     */
    private fun createFocusController(control: PlayerControlAdapter): AudioFocusController {
        Timber.i("initSessionAndPlayer: creating AudioFocusController")
        val driver = AndroidAudioFocusDriver(this)
        val focus = AudioFocusController(driver, control, FocusPolicy())
        control.focusController = focus
        Timber.i("initSessionAndPlayer: AudioFocusController created")
        return focus
    }

    /**
     * Builds a [Player.Listener] that automatically stops the service when the
     * player reaches idle after playback has occurred.
     *
     * @param focus the audio-focus controller to abandon focus on stop.
     */
    private fun buildAutoStopListener(focus: AudioFocusController): Player.Listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) playbackEverStarted = true
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE && playbackEverStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                focus.abandon()
                stopSelf()
            }
        }
    }

    /**
     * Builds the [MediaLibraryService.MediaLibrarySession] via [PlaybackEngine], including a
     * browse-tree callback for Android Auto.
     *
     * @param p the shared [ExoPlayer].
     * @param focus the audio-focus controller (unused here but kept for symmetry).
     */
    private fun buildSession(p: ExoPlayer, focus: AudioFocusController): MediaLibraryService.MediaLibrarySession {
        Timber.i("initSessionAndPlayer: calling PlaybackEngine.session(this, MainActivity)")
        val libraryCallback = GhostifyMediaLibraryCallback(applicationContext)
        return PlaybackEngine.session(this, MainActivity::class.java, libraryCallback)
    }

    /** Stores the constructed objects as service-level fields. */
    private fun assignFields(
        p: ExoPlayer,
        focus: AudioFocusController,
        noisy: AudioBecomingNoisyReceiver,
    ) {
        Timber.i("initSessionAndPlayer: assigning fields")
        player = p
        focusController = focus
        noisyReceiver = noisy
    }

    /**
     * Registers the noisy-receiver for the [android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY]
     * broadcast.
     */
    private fun registerNoisyReceiver(noisy: AudioBecomingNoisyReceiver) {
        Timber.i("initSessionAndPlayer: registering noisy receiver")
        registerReceiver(noisy, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("PlaybackService.onStartCommand: START, intent=$intent, flags=$flags, startId=$startId")

        promoteToForeground()

        val result = super.onStartCommand(intent, flags, startId)
        Timber.i("PlaybackService.onStartCommand: returning $result")
        return result
    }

    /**
     * Promotes the service to foreground immediately with a placeholder notification.
     *
     * Media3's [MediaLibraryService] handles the real media notification via
     * [PlaybackNotificationProvider], but its internal dispatcher creates the
     * notification asynchronously.  On Android 14+ (targetSdk 35) the system can
     * kill the service during that window if it isn't already foreground.  Calling
     * [startForeground] here closes that race.
     *
     * Media3 will overwrite this placeholder with the full media notification
     * (with transport controls and artwork) once its dispatcher runs.
     */
    private fun promoteToForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Ghostify")
                .setContentText("Playing music...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Timber.i("PlaybackService: startForeground called successfully")
        } catch (t: Throwable) {
            Timber.e(t, "PlaybackService: startForeground FAILED")
        }
    }

    /**
     * A foreground media service must keep playing after the Recents swipe
     * removes the task (T-096). The default [MediaLibraryService.onTaskRemoved]
     * calls `stopSelf()`, which we intentionally do NOT: the foreground
     * notification and foreground state already survive task removal.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("PlaybackService.onTaskRemoved: START")
        // Intentionally empty — keep the foreground service alive.
    }

    override fun onDestroy() {
        Timber.i("PlaybackService.onDestroy: START")
        noisyReceiver?.let { unregisterReceiver(it) }
        focusController?.abandon()
        super.onDestroy()
    }
}
