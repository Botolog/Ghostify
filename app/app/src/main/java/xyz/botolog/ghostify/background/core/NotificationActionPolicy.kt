package xyz.botolog.ghostify.background.core

import timber.log.Timber

/**
 * Pure decision logic for the media notification button set.
 *
 * The Android layer (Media3's notification provider / NotificationCompat) is
 * responsible for resolving icons and PendingIntent targets; this object owns
 * *which* buttons exist and in what order, independent of any Android runtime.
 */
object NotificationActionPolicy {

    /** Buttons that occupy the compact (collapsed) media view. Never includes STOP. */
    fun compactActions(): List<PlaybackAction> {
        Timber.i("NotificationActionPolicy.compactActions: START")
        val result = PlaybackAction.COMPACT
        Timber.i("NotificationActionPolicy.compactActions: returning $result")
        return result
    }

    /** Buttons in the expanded notification. Always includes STOP for teardown (T-097). */
    fun expandedActions(): List<PlaybackAction> {
        Timber.i("NotificationActionPolicy.expandedActions: START")
        val result = PlaybackAction.EXPANDED
        Timber.i("NotificationActionPolicy.expandedActions: returning $result")
        return result
    }

    /**
     * Whether the play/pause button should currently present the *pause* glyph
     * (i.e. media is actively playing). Drives both the button icon and the
     * `android.media.session` PLAY_PAUSE state.
     */
    fun playPauseShowsPause(isPlaying: Boolean): Boolean {
        Timber.i("NotificationActionPolicy.playPauseShowsPause: START")
        val result = isPlaying
        Timber.i("NotificationActionPolicy.playPauseShowsPause: returning $result")
        return result
    }
}
