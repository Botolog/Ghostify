package xyz.botolog.ghostify.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import timber.log.Timber

/**
 * Process-wide holder of THE single [ExoPlayer] and [MediaLibraryService.MediaLibrarySession].
 *
 * Both the in-app UI ([PlayerController]) and the background host
 * ([xyz.botolog.ghostify.background.PlaybackService]) drive the same player/session, so
 * there is never more than one audio engine or lockscreen/notification session
 * (PROJECT.md 6.3). Lazily builds on first access — whichever component (UI or
 * service) is touched first wins; the other references the same instances.
 *
 * Release is deliberately process-scoped: the engine is torn down only when the
 * whole app shuts down, never when a single holder goes away.
 */
object PlaybackEngine {

    /** Pending intent flag combining update-current and immutable. */
    private const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var session: MediaLibraryService.MediaLibrarySession? = null

    @Volatile
    private var sessionActivity: PendingIntent? = null

    /**
     * Returns THE [ExoPlayer], creating it (with default audio-focus handling) on first use.
     *
     * @param context used to obtain the application context for building the player.
     */
    fun exoPlayer(context: Context): ExoPlayer {
        Timber.i("PlaybackEngine.exoPlayer: called, existing=${exoPlayer != null}")
        exoPlayer?.let { return it }
        return synchronized(this) {
            exoPlayer ?: buildExoPlayer(context.applicationContext)
        }
    }

    /** Creates and stores the process-wide [ExoPlayer] instance. */
    private fun buildExoPlayer(appContext: Context): ExoPlayer {
        Timber.i("PlaybackEngine.exoPlayer: building new ExoPlayer")
        val player = ExoPlayer.Builder(appContext).build()
        player.addListener(PlayerErrorListener())
        exoPlayer = player
        Timber.i("PlaybackEngine.exoPlayer: ExoPlayer built OK")
        return player
    }

    /**
     * Listens for player errors and ensures they are visible to Android Auto and other
     * MediaSession controllers via [androidx.media3.session.MediaSession] STATE_ERROR.
     *
     * When [ExoPlayer] hits an unrecoverable error it transitions to STATE_IDLE. Media3's
     * built-in MediaSession already maps this to `PlaybackStateCompat.STATE_ERROR`, but
     * an explicit listener gives us logging and a hook for future recovery logic.
     */
    private class PlayerErrorListener : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "PlayerErrorListener: onPlayerError code=${error.errorCode}")
        }
    }

    /**
     * Returns THE [MediaLibraryService.MediaLibrarySession], built on the shared player.
     *
     * [sessionActivityClass] is only used the first time the session is created; later calls
     * are no-ops for the activity because the session already exists.
     *
     * [callback] is set during the first creation only; callers that need a callback must
     * supply it on the first call.
     *
     * @param context used to obtain the application context.
     * @param sessionActivityClass optional activity class for the session tap-through.
     * @param callback optional library session callback for browse tree and custom commands.
     */
    fun session(
        context: Context,
        sessionActivityClass: Class<*>?,
        callback: MediaLibraryService.MediaLibrarySession.Callback? = null,
    ): MediaLibraryService.MediaLibrarySession {
        Timber.i("PlaybackEngine.session: called, existing=${session != null}")
        session?.let { return it }
        val appContext = context.applicationContext
        initSessionActivity(appContext, sessionActivityClass)
        return synchronized(this) {
            session ?: buildMediaLibrarySession(appContext, callback)
        }
    }

    /** Creates the [PendingIntent] that launches [sessionActivityClass] on notification tap. */
    private fun initSessionActivity(appContext: Context, sessionActivityClass: Class<*>?) {
        if (sessionActivityClass == null || sessionActivity != null) return
        Timber.i("PlaybackEngine.session: creating PendingIntent for activity")
        sessionActivity = PendingIntent.getActivity(
            appContext,
            /* requestCode = */ 0,
            Intent(appContext, sessionActivityClass),
            PENDING_INTENT_FLAGS,
        )
    }

    /**
     * Builds and stores the [MediaLibraryService.MediaLibrarySession] bound to the shared ExoPlayer.
     *
     * The callback must be provided at construction time (it is a required constructor parameter
     * of [MediaLibraryService.MediaLibrarySession.Builder] and cannot be set later).
     */
    private fun buildMediaLibrarySession(
        appContext: Context,
        callback: MediaLibraryService.MediaLibrarySession.Callback?,
    ): MediaLibraryService.MediaLibrarySession {
        Timber.i("PlaybackEngine.session: building MediaLibrarySession with player")
        val cb = callback ?: NoOpLibraryCallback
        val builder = MediaLibraryService.MediaLibrarySession.Builder(
            appContext,
            exoPlayer(appContext),
            cb,
        )
        sessionActivity?.let { builder.setSessionActivity(it) }
        val s = builder.build()
        session = s
        Timber.i("PlaybackEngine.session: MediaLibrarySession built OK")
        return s
    }

    /**
     * Tears down the engine.
     *
     * Only call when every holder (UI + service) is gone.
     */
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

    /**
     * No-op fallback callback when no real callback is provided.
     * Returns default results from the parent interface.
     */
    private object NoOpLibraryCallback : MediaLibraryService.MediaLibrarySession.Callback
}
