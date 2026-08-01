package com.ghostify.background

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ghostify.R
import com.ghostify.background.core.AudioFocusController
import com.ghostify.background.core.FocusPolicy

/**
 * Foreground `MediaSessionService` that is the single source of truth for audio
 * playback in Ghostify.
 *
 * Responsibilities:
 *  - Owns the `ExoPlayer` and the `MediaSession`; hands the session to controllers
 *    (UI `MediaController`, Bluetooth, Android Auto) via [onGetSession].
 *  - Runs as a foreground service via a `MediaNotification.Provider`
 *    ([PlaybackNotificationProvider]) so playback survives the app being swiped
 *    away (T-093, T-096) and works under battery saver (T-106).
 *  - Explicit audio-focus management (T-099) and becoming-noisy handling (T-098)
 *    via [AudioFocusController] / [AudioBecomingNoisyReceiver], keeping the player
 *    on `handleAudioFocus = false` so the two never fight.
 *  - Tears the service down on an explicit Stop / end-of-queue, so there is never
 *    a zombie notification (T-097).
 *  - Survives configuration changes (T-102): the player lives in the service, not
 *    the Activity, so rotation only rebinds a `MediaController`.
 *
 * Media buttons (T-101), notification actions (T-100), lockscreen controls (T-095)
 * and the seek bar all flow through the MediaSession -> player, which the
 * foreground service keeps alive.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val NOTIFICATION_ID: Int = 1
        private const val META_PLAYER_ACTIVITY = "com.ghostify.background.PLAYER_ACTIVITY"
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var focusController: AudioFocusController? = null
    private var noisyReceiver: AudioBecomingNoisyReceiver? = null
    private var playbackEverStarted = false

    override fun onCreate() {
        super.onCreate()
        // Set before any session is added so every session gets the same
        // foreground notification (with the Stop button).
        setMediaNotificationProvider(
            PlaybackNotificationProvider(this, R.drawable.ic_notification)
        )
    }

    /** Lazily builds the player + session the first time a controller asks. */
    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession {
        var s = session
        if (s == null) {
            val p = buildExoPlayer(this)
            val control = PlayerControlAdapter(p)
            val focus = AudioFocusController(AndroidAudioFocusDriver(this), control, FocusPolicy())
            control.focusController = focus
            val noisy = AudioBecomingNoisyReceiver(control)

            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) playbackEverStarted = true
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // IDLE reached after we were playing == user pressed Stop or the
                    // queue finished. Media3 dismisses the notification on idle; we
                    // additionally stop the foreground service so it can't linger (T-097).
                    if (state == Player.STATE_IDLE && playbackEverStarted) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        focusController?.abandon()
                        stopSelf()
                    }
                }
            })

            val builder = MediaSession.Builder(this, p)
            sessionActivityIntent()?.let { builder.setSessionActivity(it) }
            s = builder.build()

            session = s
            player = p
            focusController = focus
            noisyReceiver = noisy
            registerReceiver(noisy, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            playbackEverStarted = false
        }
        return s
    }

    /**
     * PendingIntent that opens the app's player screen when the notification is
     * tapped. The target activity is declared via `<meta-data>` (see the manifest)
     * so this component never hard-codes the app's UI package.
     */
    private fun sessionActivityIntent(): PendingIntent? {
        val activityName = resolvePlayerActivityName() ?: return null
        val intent = Intent().setClassName(packageName, activityName)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun resolvePlayerActivityName(): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            appInfo?.metaData?.getString(META_PLAYER_ACTIVITY)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super.onStartCommand(intent, flags, startId)

    /**
     * A foreground media service must keep playing after the Recents swipe
     * removes the task (T-096). The default [MediaSessionService.onTaskRemoved]
     * calls `stopSelf()`, which we intentionally do NOT: the foreground
     * notification and foreground state already survive task removal.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally empty — keep the foreground service alive.
    }

    override fun onDestroy() {
        noisyReceiver?.let { unregisterReceiver(it) }
        focusController?.abandon()
        player?.release()
        session?.release()
        super.onDestroy()
    }
}
