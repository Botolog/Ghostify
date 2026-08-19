package xyz.botolog.ghostify.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import timber.log.Timber

/**
 * Process-wide holder of THE single [ExoPlayer] and [MediaSession].
 *
 * Both the in-app UI ([PlayerController]) and the background host
 * ([xyz.botolog.ghostify.background.PlaybackService]) drive the same player/session, so
 * there is never more than one audio engine or lockscreen/notification session
 * (PROJECT.md §6.3). Lazily builds on first access — whichever component (UI or
 * service) is touched first wins; the other references the same instances.
 *
 * Release is deliberately process-scoped: the engine is torn down only when the
 * whole app shuts down, never when a single holder goes away.
 */
object PlaybackEngine {

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var session: MediaSession? = null

    @Volatile
    private var sessionActivity: PendingIntent? = null

    /** Returns THE [ExoPlayer], creating it (with default audio-focus handling) on first use. */
    fun exoPlayer(context: Context): ExoPlayer {
        Timber.i("PlaybackEngine.exoPlayer: called, existing=${exoPlayer != null}")
        exoPlayer?.let { return it }
        return synchronized(this) {
            exoPlayer ?: run {
                Timber.i("PlaybackEngine.exoPlayer: building new ExoPlayer")
                val player = ExoPlayer.Builder(context.applicationContext).build()
                exoPlayer = player
                Timber.i("PlaybackEngine.exoPlayer: ExoPlayer built OK")
                player
            }
        }
    }

    /**
     * Returns THE [MediaSession], built on the shared player. [sessionActivityClass]
     * is only used the first time the session is created; later calls are no-ops for
     * the activity because the session already exists.
     *
     * [callback] is set during the first creation only; callers that need a
     * callback must supply it on the first call (the MediaSession.Builder API
     * requires the callback to be set before `build()`).
     */
    fun session(
        context: Context,
        sessionActivityClass: Class<*>?,
        callback: MediaSession.Callback? = null,
    ): MediaSession {
        Timber.i("PlaybackEngine.session: called, existing=${session != null}")
        session?.let { return it }
        val appContext = context.applicationContext
        if (sessionActivityClass != null && sessionActivity == null) {
            Timber.i("PlaybackEngine.session: creating PendingIntent for activity")
            sessionActivity = PendingIntent.getActivity(
                appContext,
                /* requestCode = */ 0,
                Intent(appContext, sessionActivityClass),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return synchronized(this) {
            session ?: run {
                Timber.i("PlaybackEngine.session: building MediaSession with player")
                val s = MediaSession.Builder(appContext, exoPlayer(context))
                    .apply {
                        sessionActivity?.let(::setSessionActivity)
                        callback?.let { setCallback(it) }
                    }
                    .build()
                session = s
                Timber.i("PlaybackEngine.session: MediaSession built OK")
                s
            }
        }
    }

    /** Tear down the engine. Only call when every holder (UI + service) is gone. */
    fun release() {
        Timber.i("PlaybackEngine.release: START")
        synchronized(this) {
            session?.release()
            session = null
            exoPlayer?.release()
            exoPlayer = null
            sessionActivity = null
        }
    }
}
