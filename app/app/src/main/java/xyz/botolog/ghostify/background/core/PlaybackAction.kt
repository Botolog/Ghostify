package xyz.botolog.ghostify.background.core

/**
 * Media transport actions exposed in the lockscreen / notification.
 *
 * Order in [EXPANDED] is the render order used for the compact view; the expanded
 * view additionally surfaces [STOP] so the user can tear down playback from the
 * notification without hunting for the app (T-097, T-100).
 */
enum class PlaybackAction {

    /** Jump to the previous track. */
    PREVIOUS,

    /** Toggle between play and pause. */
    PLAY_PAUSE,

    /** Jump to the next track. */
    NEXT,

    /** Stop playback and tear down the foreground service. */
    STOP,
    ;

    companion object {

        /** Buttons shown in the compact (headset / Android Auto row) view. */
        val COMPACT: List<PlaybackAction> = listOf(PREVIOUS, PLAY_PAUSE, NEXT)

        /** Buttons shown in the expanded media notification. */
        val EXPANDED: List<PlaybackAction> = COMPACT + STOP
    }
}
